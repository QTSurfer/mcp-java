package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestOutcome;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.Sweep;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepOptions;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.mcp.model.EquityPoint;
import com.qtsurfer.mcp.model.JobResult;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link BacktestingService} backed by an {@link AuthenticatedClient} (sdk-java). Job and sweep
 * handles are kept in memory for the lifetime of the process.
 *
 * <p>The {@code AuthenticatedClient} owns the apikey → JWT exchange and proactively
 * re-mints the token shortly before its known TTL elapses, plus refreshes and retries
 * once on an unexpected 401 — but only for calls routed through {@code AuthenticatedClient}
 * itself, which here is just {@link #submitBacktest}'s compile step. The prepare/execute/poll
 * stages after it run through the {@code Strategy}/{@code Backtest} handles returned by
 * compile, which hold their own reference to the workflow and do not go through
 * {@code AuthenticatedClient}'s refresh policy — a session idle long enough mid-poll can
 * still see a stale-token failure there. This service never touches the bearer token
 * directly either way.
 *
 * <p>Submit flow: compile → prepare+execute (async). {@link #submitBacktest} blocks only on
 * compilation (fast), then continues prepare+execute in the background. The returned job ID can
 * be polled via {@link #getJobStatus}.
 *
 * <p>A job id this process never submitted has no local handle, and
 * {@link #getJobStatus} then reads it straight off the platform — see
 * {@link #fromPlatform}. Sweeps have no such route: every sweep read goes through the
 * {@link Sweep} handle its submission returned, so a sweep id from elsewhere is not answerable.
 */
public class SdkBacktestingService implements BacktestingService {

  private static final Logger log = LoggerFactory.getLogger(SdkBacktestingService.class);

  private final AuthenticatedClient qts;
  private final String baseUrl;
  private final Map<String, SessionJob> jobs = new ConcurrentHashMap<>();
  private final Map<String, Sweep> sweeps = new ConcurrentHashMap<>();

  /** Internal record tracking a submitted job. */
  private record SessionJob(
      String jobId,
      String instrument,
      String exchangeId,
      String submittedAt,
      CompletableFuture<Void> future,
      Backtest backtest,
      AtomicReference<ResultMap> resultRef) {

    JobStatus status() {
      if (future.isCompletedExceptionally()) return JobStatus.FAILED;
      if (future.isDone()) return JobStatus.COMPLETED;
      if (backtest == null) return JobStatus.COMPILING;
      return switch (backtest.state()) {
        case EXECUTING -> JobStatus.EXECUTING;
        case COMPLETED -> JobStatus.COMPLETED;
        case FAILED    -> JobStatus.FAILED;
        case CANCELED  -> JobStatus.CANCELED;
      };
    }
  }

  public SdkBacktestingService(AuthenticatedClient qts, String baseUrl) {
    this.qts = qts;
    this.baseUrl = baseUrl;
  }

  @Override
  public List<Exchange> listExchanges() {
    return qts.exchanges();
  }

  @Override
  public List<InstrumentDetail> listInstruments(String exchangeId) {
    return qts.instruments(exchangeId);
  }

  @Override
  public String submitBacktest(
      String strategyCode, String exchangeId, String instrument, String from, String to) {
    BacktestRequest sdkRequest = BacktestRequest.builder()
        .strategy(strategyCode)
        .exchangeId(exchangeId)
        .instrument(instrument)
        .from(from)
        .to(to)
        .build();

    // Compile first (blocking — fast, gives us early error on bad source)
    com.qtsurfer.api.sdk.Strategy strategy;
    try {
      strategy = qts.compile(sdkRequest).join();
    } catch (Exception e) {
      throw new RuntimeException("Compilation failed: " + rootMessage(e), e);
    }

    // Submit execution (non-blocking — returns Backtest handle with job ID)
    Backtest backtest;
    try {
      backtest = strategy.backtest(sdkRequest, BacktestOptions.defaults()).join();
    } catch (Exception e) {
      throw new RuntimeException("Backtest submission failed: " + rootMessage(e), e);
    }

    String jobId = backtest.id();
    String submittedAt = Instant.now().toString();
    String finalJobId = jobId;
    AtomicReference<ResultMap> resultRef = new AtomicReference<>();

    // Track the await future; capture ResultMap when execution completes
    CompletableFuture<Void> future = backtest.await()
        .thenAccept(sdkResult -> {
          resultRef.set(sdkResult);
          log.info("Job {} completed — pnl={} trades={}", finalJobId,
              sdkResult.getPnlTotal(), sdkResult.getTotalTrades());
        })
        .exceptionally(err -> { log.warn("Job {} failed: {}", finalJobId, rootMessage(err)); return null; });

    jobs.put(jobId, new SessionJob(jobId, instrument, exchangeId, submittedAt, future, backtest, resultRef));
    log.info("Submitted backtest {} ({} {} {} → {})", jobId, exchangeId, instrument, from, to);
    return jobId;
  }

  @Override
  public Optional<JobSummary> getJobStatus(String jobId, String exchangeId) {
    SessionJob job = jobs.get(jobId);
    if (job == null) {
      if (exchangeId == null || exchangeId.isBlank()) return Optional.empty();
      return fromPlatform(jobId, exchangeId);
    }
    ResultMap sdkResult = job.resultRef().get();
    JobResult result = sdkResult != null ? toJobResult(sdkResult) : null;
    return Optional.of(new JobSummary(job.jobId(), job.instrument(), job.exchangeId(),
        job.status(), job.submittedAt(), result));
  }

  /**
   * Read a job this process never submitted straight off the platform.
   *
   * <p>The SDK answers with a sealed {@link BacktestOutcome}, and its four variants map onto
   * {@link JobStatus} one for one: {@code Completed} → {@link JobStatus#COMPLETED},
   * {@code Failed} → {@link JobStatus#FAILED}, {@code Aborted} → {@link JobStatus#CANCELED},
   * {@code InProgress} → {@link JobStatus#EXECUTING}. The last one is not an approximation:
   * the id addresses an execute job, so compilation and preparation are already behind it and
   * {@link JobStatus#COMPILING} and {@link JobStatus#PREPARING} cannot apply.
   *
   * <p>Failed and aborted runs are answers rather than errors here, exactly as the SDK reports
   * them. What does raise is a pair the platform will not answer for. The SDK's error type
   * carries no status code, so an unrecognised pair and a backend failure cannot be told apart
   * from the response — the message names both possibilities rather than guessing one.
   *
   * @throws RuntimeException when the platform does not answer for this job and exchange
   */
  private Optional<JobSummary> fromPlatform(String jobId, String exchangeId) {
    BacktestOutcome outcome;
    try {
      outcome = qts.backtestResult(exchangeId, jobId);
    } catch (Exception e) {
      throw new RuntimeException(
          "The platform did not return job " + jobId + " on exchange " + exchangeId
              + ". A result is addressed by both, so either the job id is unknown or the"
              + " exchange is the wrong one — an id carried to the wrong exchange does not"
              + " name the same run. Details: " + rootMessage(e), e);
    }
    JobStatus status;
    if (outcome instanceof BacktestOutcome.Completed) {
      status = JobStatus.COMPLETED;
    } else if (outcome instanceof BacktestOutcome.Failed) {
      status = JobStatus.FAILED;
    } else if (outcome instanceof BacktestOutcome.Aborted) {
      status = JobStatus.CANCELED;
    } else {
      status = JobStatus.EXECUTING;
    }
    ResultMap results = outcome.results();
    JobState state = outcome.state();
    // Only a completed run's numbers are final; anything earlier is a partial account of itself.
    JobResult result =
        status == JobStatus.COMPLETED && results != null ? toJobResult(results) : null;
    String instrument = results != null && results.getInstrument() != null
        ? results.getInstrument() : "unknown";
    String startedAt = state != null && state.getStartTime() != null
        ? state.getStartTime().toString() : "unknown";
    return Optional.of(
        new JobSummary(jobId, instrument, exchangeId, status, startedAt, result));
  }

  private static JobResult toJobResult(ResultMap r) {
    List<EquityPoint> curve = r.getEquityCurve() == null ? List.of()
        : r.getEquityCurve().stream()
            .filter(p -> p.getTimestamp() != null && p.getEquity() != null)
            .map(p -> new EquityPoint(p.getTimestamp(), p.getEquity()))
            .toList();
    return new JobResult(
        r.getPnlTotal(), r.getTotalTrades(), r.getWinRate(),
        r.getSharpeRatio(), r.getSortinoRatio(), r.getCagr(),
        r.getMaxDrawdown(), r.getMaxDrawdownPercent(),
        r.getSignalCount() != null ? r.getSignalCount().longValue() : null,
        r.getHostName(), r.getIops(), curve);
  }

  @Override
  public List<JobSummary> listJobs(JobStatus status) {
    List<JobSummary> result = new ArrayList<>();
    for (SessionJob job : jobs.values()) {
      JobSummary summary = new JobSummary(
          job.jobId(), job.instrument(), job.exchangeId(), job.status(), job.submittedAt());
      if (status == null || summary.status() == status) {
        result.add(summary);
      }
    }
    return result;
  }

  // ---- sweeps ---------------------------------------------------------------

  @Override
  public ExecuteSweepAccepted submitSweep(SweepRequest request) {
    Sweep sweep;
    try {
      sweep = qts.sweep(request, SweepOptions.defaults()).join();
    } catch (Exception e) {
      throw new RuntimeException("Sweep submission failed: " + rootMessage(e), e);
    }
    sweeps.put(sweep.id(), sweep);
    log.info("Submitted sweep {} ({} {} {} → {})", sweep.id(),
        request.exchangeId(), request.instrument(), request.from(), request.to());
    return sweep.accepted();
  }

  @Override
  public Optional<ExecuteSweepResult> getSweepStatus(String sweepId) {
    Sweep sweep = sweeps.get(sweepId);
    // null/null asks for the platform's own default view: ranked, plateau-ordered.
    return sweep == null ? Optional.empty() : Optional.of(sweep.results(null, null));
  }

  @Override
  public boolean cancelSweep(String sweepId) {
    Sweep sweep = sweeps.get(sweepId);
    return sweep != null && sweep.cancel();
  }

  @Override
  public Optional<SweepSensitivity> getSweepSensitivity(String sweepId, SweepObjective objective) {
    Sweep sweep = sweeps.get(sweepId);
    return sweep == null ? Optional.empty() : Optional.of(sweep.sensitivity(objective));
  }

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) cause = cause.getCause();
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
