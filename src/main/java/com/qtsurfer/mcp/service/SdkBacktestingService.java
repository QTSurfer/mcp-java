package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.QTSurfer;
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
 * {@link BacktestingService} backed by {@link QTSurfer} SDK. Job state is kept in memory
 * for the lifetime of the process — only jobs submitted in this session are visible.
 *
 * <p>Submit flow: compile → prepare+execute (async). {@link #submitBacktest} blocks only on
 * compilation (fast), then continues prepare+execute in the background. The returned job ID can
 * be polled via {@link #getJobStatus}.
 */
public class SdkBacktestingService implements BacktestingService {

  private static final Logger log = LoggerFactory.getLogger(SdkBacktestingService.class);

  private final QTSurfer qts;
  private final String baseUrl;
  private final Map<String, SessionJob> jobs = new ConcurrentHashMap<>();

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

  public SdkBacktestingService(QTSurfer qts, String baseUrl) {
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
  public Optional<JobSummary> getJobStatus(String jobId) {
    SessionJob job = jobs.get(jobId);
    if (job == null) return Optional.empty();
    ResultMap sdkResult = job.resultRef().get();
    JobResult result = sdkResult != null ? toJobResult(sdkResult) : null;
    return Optional.of(new JobSummary(job.jobId(), job.instrument(), job.exchangeId(),
        job.status(), job.submittedAt(), result));
  }

  private static JobResult toJobResult(ResultMap r) {
    return new JobResult(
        r.getPnlTotal(), r.getTotalTrades(), r.getWinRate(),
        r.getSharpeRatio(), r.getSortinoRatio(), r.getCagr(),
        r.getMaxDrawdown(), r.getMaxDrawdownPercent(),
        r.getSignalCount() != null ? r.getSignalCount().longValue() : null,
        r.getHostName(), r.getIops());
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

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) cause = cause.getCause();
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
