package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.binary.BinaryDownload;
import com.qtsurfer.api.client.model.PoolDetail;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.EquityCurveResult;
import com.qtsurfer.api.client.model.CreateDatasetRequest;
import com.qtsurfer.api.client.model.DataSourceType;
import com.qtsurfer.api.client.model.Dataset;
import com.qtsurfer.api.client.model.DatasetCreated;
import com.qtsurfer.api.client.model.DatasetImportCreated;
import com.qtsurfer.api.client.model.DatasetImportDexRequest;
import com.qtsurfer.api.client.model.DatasetImportRequest;
import com.qtsurfer.api.client.model.DatasetImportState;
import com.qtsurfer.api.client.model.DatasetUploadSession;
import com.qtsurfer.api.client.model.DatasetUploadState;
import com.qtsurfer.api.client.model.DatasetWithLinks;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.ScalarStrategyParamValue;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.client.model.StrategySummary;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.client.model.Account;
import com.qtsurfer.api.client.model.AccountUsage;
import com.qtsurfer.api.client.model.LiveListResponse;
import com.qtsurfer.api.client.model.LiveParamsUpdateResult;
import com.qtsurfer.api.client.model.LiveCommandResult;
import com.qtsurfer.api.client.model.LiveRun;
import com.qtsurfer.api.client.model.LiveRunCompact;
import com.qtsurfer.api.client.model.LiveRunDetail;
import com.qtsurfer.api.client.model.LiveRunWithStream;
import com.qtsurfer.api.client.model.LiveSignalPage;
import com.qtsurfer.api.client.model.LiveStreamRevoked;
import com.qtsurfer.api.client.model.LiveStreamUrl;
import com.qtsurfer.api.client.model.PublicLiveListResponse;
import com.qtsurfer.api.client.model.StartLiveRequest;
import com.qtsurfer.api.client.model.UpdateLiveRequest;
import com.qtsurfer.api.client.model.SendLiveCommandRequest;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestOutcome;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.BoundedEquityCurve;
import com.qtsurfer.api.sdk.DownloadFormat;
import com.qtsurfer.api.sdk.Sweep;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepOptions;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.UpdateLiveParamsRequestBuilder;
import com.qtsurfer.api.sdk.ValidationOutcome;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.api.sdk.errors.QTSCanceledError;
import com.qtsurfer.mcp.model.EquityPoint;
import com.qtsurfer.mcp.model.DatasetSummary;
import com.qtsurfer.mcp.model.DatasetUploadResult;
import com.qtsurfer.mcp.model.DatasetUploadStatus;
import com.qtsurfer.mcp.model.DatasetImportResult;
import com.qtsurfer.mcp.model.DatasetImportStatus;
import com.qtsurfer.mcp.model.StrategyCompilation;
import com.qtsurfer.mcp.model.StrategyProperty;
import com.qtsurfer.mcp.model.JobResult;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;
import com.qtsurfer.mcp.model.MarketDataDownload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
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
  private final UploadRoot uploadRoot;
  private final UploadRoot downloadRoot;
  private final Map<String, SessionJob> jobs = new ConcurrentHashMap<>();
  private final Map<String, SessionSweep> sweeps = new ConcurrentHashMap<>();

  @Override
  public Account getAccount() {
    return qts.getAccount();
  }

  @Override
  public AccountUsage getAccountUsage() {
    return qts.getAccountUsage();
  }

  @Override
  public LiveRunWithStream startLive(String strategyId, StartLiveRequest request) {
    return qts.startLive(strategyId, request);
  }

  @Override
  public LiveRunWithStream getLive(String strategyId) {
    return qts.getLive(strategyId);
  }

  @Override
  public LiveRunDetail getLiveRun(String runId) {
    return qts.getLiveRun(runId);
  }

  @Override
  public LiveRun stopLive(String strategyId) {
    return qts.stopLive(strategyId);
  }

  @Override
  public LiveStreamUrl rotateLiveStream(String runId) {
    return qts.rotateLiveStream(runId);
  }

  @Override
  public LiveStreamRevoked revokeLiveStream(String runId) {
    return qts.revokeLiveStream(runId);
  }

  @Override
  public LiveListResponse listLive(String cursor, Integer limit) {
    return qts.listLive(cursor, limit);
  }

  @Override
  public PublicLiveListResponse listPublicLive(String cursor, Integer limit) {
    return qts.listPublicLive(cursor, limit);
  }

  @Override
  public LiveRunCompact updateLive(String runId, UpdateLiveRequest request) {
    return qts.updateLive(runId, request);
  }

  @Override
  public LiveParamsUpdateResult updateLiveParams(String runId, Map<String, Object> params) {
    return qts.updateLiveParams(runId, UpdateLiveParamsRequestBuilder.builder().params(params));
  }

  @Override
  public LiveCommandResult sendLiveCommand(String runId, SendLiveCommandRequest request) {
    return qts.sendLiveCommand(runId, request);
  }

  @Override
  public LiveSignalPage getLiveSignals(
      String runId, Long sinceMs, String instrument, String cursor, Integer limit) {
    return qts.getLiveSignals(runId, sinceMs, instrument, cursor, limit);
  }

  /** Internal record tracking a submitted job. */
  private record SessionJob(
      String jobId,
      String instrument,
      String exchangeId,
      String submittedAt,
      CompletableFuture<Void> future,
      Backtest backtest,
      AtomicReference<ResultMap> resultRef,
      AtomicReference<Failure> failure) {

    JobStatus status() {
      return resolveStatus(failure.get(), future.isCompletedExceptionally(), future.isDone(),
          backtest == null ? null : backtest.state());
    }

    /** The platform's reason when the run ended badly, otherwise {@code null}. */
    String failureDetail() {
      Failure f = failure.get();
      return f == null ? null : f.detail();
    }
  }

  /** How a submitted run ended when it did not complete, and the reason the platform gave. */
  record Failure(JobStatus status, String detail) {}

  /**
   * The lifecycle state of a submitted run.
   *
   * <p>The await future is not a reliable signal on its own: the failure is observed and recorded
   * as a {@link Failure} so the future itself can complete normally, which made a run whose
   * preparation was rejected read as {@link JobStatus#COMPLETED}. A recorded failure therefore
   * decides first, then a cancellation the SDK has already marked, and only a future that is done
   * with neither is a completed run.
   *
   * @param failure     the failure recorded when the await future ended badly, or {@code null}
   * @param exceptional whether the future itself completed exceptionally
   * @param done        whether the future is done
   * @param state       the SDK's local snapshot, or {@code null} while the strategy compiles
   */
  static JobStatus resolveStatus(
      Failure failure, boolean exceptional, boolean done, Backtest.State state) {
    if (failure != null) return failure.status();
    if (exceptional) return JobStatus.FAILED;
    if (state == Backtest.State.CANCELED) return JobStatus.CANCELED;
    if (done) return JobStatus.COMPLETED;
    if (state == null) return JobStatus.COMPILING;
    return switch (state) {
      case EXECUTING -> JobStatus.EXECUTING;
      case COMPLETED -> JobStatus.COMPLETED;
      case FAILED    -> JobStatus.FAILED;
      case CANCELED  -> JobStatus.CANCELED;
    };
  }

  /** Whether an await failure is the run being cancelled rather than failing. */
  static boolean isCancellation(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof QTSCanceledError || t instanceof CancellationException) return true;
    }
    return false;
  }

  /** Session provenance required to address a retained curve through the SDK. */
  private record SessionSweep(Sweep sweep, String exchangeId, DataSourceType type) {}

  public SdkBacktestingService(AuthenticatedClient qts, String baseUrl) {
    this(qts, baseUrl, null, null);
  }

  /** Create the SDK-backed service with an optional operator-approved upload directory. */
  public SdkBacktestingService(AuthenticatedClient qts, String baseUrl, Path uploadRoot) {
    this(qts, baseUrl, uploadRoot, null);
  }

  /** Create the SDK-backed service with independently guarded upload and download directories. */
  public SdkBacktestingService(
      AuthenticatedClient qts, String baseUrl, Path uploadRoot, Path downloadRoot) {
    this.qts = qts;
    this.baseUrl = baseUrl;
    this.uploadRoot = uploadRoot == null ? null : new UploadRoot(uploadRoot);
    this.downloadRoot = downloadRoot == null ? null : new UploadRoot(downloadRoot);
  }

  // ---- datasets -------------------------------------------------------------

  @Override
  public StrategyCompilation compileStrategy(String strategyCode) {
    try {
      com.qtsurfer.api.sdk.Strategy strategy = qts.compile(strategyCode).join();
      return new StrategyCompilation(strategy.id(), strategy.declaredProperties().stream()
          .map(property -> new StrategyProperty(property.getName(), property.getDescription(),
              property.getDefaultValue(), property.getReflected(), property.getMin(), property.getMax(),
              property.getStep()))
          .toList(), strategy.requires());
    } catch (Exception e) {
      throw new RuntimeException("Compilation failed: " + rootMessage(e), e);
    }
  }

  @Override
  public List<DatasetSummary> listDatasets(boolean includeDeleted) {
    return qts.getDatasets(includeDeleted).stream().map(SdkBacktestingService::datasetSummary).toList();
  }

  @Override
  public Optional<DatasetSummary> getDataset(String datasetId) {
    try {
      return Optional.of(datasetSummary(qts.getDataset(datasetId)));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public void deleteDataset(String datasetId) {
    qts.deleteDataset(datasetId);
  }

  @Override
  public DatasetUploadResult uploadDataset(
      String datasetId, String name, String instrument, String filePath) {
    Path file = guardedUploadFile(filePath);
    try {
      String targetDatasetId;
      String uploadId;
      if (datasetId == null || datasetId.isBlank()) {
        if (name == null || name.isBlank() || instrument == null || instrument.isBlank()) {
          throw new IllegalArgumentException("name and instrument are required when datasetId is absent");
        }
        DatasetCreated created = qts.createDataset(new CreateDatasetRequest().name(name).instrument(instrument));
        qts.uploadDatasetFile(created, file);
        targetDatasetId = created.getDatasetId();
        uploadId = created.getUploadId();
      } else {
        DatasetUploadSession session = qts.openDatasetUpload(datasetId);
        qts.uploadDatasetFile(session, file);
        targetDatasetId = datasetId;
        uploadId = session.getUploadId();
      }
      String jobId = qts.finalizeDatasetUpload(targetDatasetId, uploadId).getJobId();
      return new DatasetUploadResult(targetDatasetId, uploadId, jobId, Files.size(file));
    } catch (IOException e) {
      throw new IllegalStateException("Could not read guarded upload file", e);
    }
  }

  @Override
  public Optional<DatasetUploadStatus> getDatasetUpload(String datasetId, String uploadId) {
    try {
      DatasetUploadState state = qts.getDatasetUpload(datasetId, uploadId);
      return Optional.of(uploadStatus(datasetId, state));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public String finalizeDatasetUpload(String datasetId, String uploadId) {
    return qts.finalizeDatasetUpload(datasetId, uploadId).getJobId();
  }

  @Override
  public DatasetImportResult importDataset(String name, String instrument, String from, String to,
      String network, String protocol, String version, String contract) {
    if ((protocol == null) != (version == null)) {
      throw new IllegalArgumentException("protocol and version must be provided together");
    }
    DatasetImportDexRequest dex = new DatasetImportDexRequest()
        .network(DatasetImportDexRequest.NetworkEnum.valueOf(network.toUpperCase()))
        .contract(contract);
    if (protocol != null) {
      dex.id(DatasetImportDexRequest.IdEnum.valueOf(protocol.toUpperCase()))
          .version(DatasetImportDexRequest.VersionEnum.valueOf(version.toUpperCase()));
    }
    return importDataset(new DatasetImportRequest()
        .name(name).instrument(instrument).from(OffsetDateTime.parse(from)).to(OffsetDateTime.parse(to))
        .type(DatasetImportRequest.TypeEnum.DEX).dex(dex));
  }

  @Override
  public DatasetImportResult importDataset(DatasetImportRequest request) {
    DatasetImportCreated created = qts.importDataset(request);
    return new DatasetImportResult(created.getDatasetId(), created.getImportId(), created.getJobId(),
        String.valueOf(created.getStatus()), created.getCadence());
  }

  @Override
  public Optional<DatasetImportStatus> getDatasetImport(String datasetId, String importId) {
    // A failed read propagates: the tool reports its cause. Swallowing it here read every error,
    // a body the client could not parse included, as an import that does not exist.
    DatasetImportState state = qts.getDatasetImport(datasetId, importId);
    if (state == null) return Optional.empty();
    return Optional.of(new DatasetImportStatus(datasetId, state.getImportId(), String.valueOf(state.getStatus()),
        state.getJobId(), state.getError(), state.getVersion() == null ? null : state.getVersion().getId()));
  }

  private Path guardedUploadFile(String filePath) {
    if (uploadRoot == null) {
      throw new IllegalStateException(
          "Dataset upload is disabled: configure --upload-root or QTSURFER_UPLOAD_ROOT");
    }
    return uploadRoot.resolveFile(filePath);
  }

  private static DatasetSummary datasetSummary(Dataset dataset) {
    return new DatasetSummary(dataset.getDatasetId(), dataset.getName(), dataset.getInstrument(),
        dataset.getType() == null ? null : dataset.getType().getValue(),
        dataset.getCurrentVersionId(), stringify(dataset.getFrom()), stringify(dataset.getTo()),
        dataset.getCadence(), null, stringify(dataset.getDeletedAt()));
  }

  private static DatasetSummary datasetSummary(DatasetWithLinks dataset) {
    return new DatasetSummary(dataset.getDatasetId(), dataset.getName(), dataset.getInstrument(),
        dataset.getType() == null ? null : dataset.getType().getValue(),
        dataset.getCurrentVersionId(), stringify(dataset.getFrom()), stringify(dataset.getTo()),
        dataset.getCadence(), stringify(dataset.getDataFormat()), stringify(dataset.getDeletedAt()));
  }

  private static DatasetUploadStatus uploadStatus(String datasetId, DatasetUploadState state) {
    var version = state.getVersion();
    return new DatasetUploadStatus(datasetId, state.getUploadId(), String.valueOf(state.getStatus()),
        state.getJobId(), version == null ? null : version.getId(), version == null ? null : version.getRows(),
        version == null ? null : version.getBytes(), version == null ? null : version.getCadence(),
        version == null ? null : version.getGaps(), version == null ? null : version.getLargestGapSteps());
  }

  private static String stringify(Object value) {
    return value == null ? null : value.toString();
  }

  @Override
  public List<Exchange> listExchanges() {
    return qts.getExchanges();
  }

  @Override
  public List<InstrumentDetail> listInstruments(String exchangeId, String segment) {
    return segment == null || segment.isBlank()
        ? qts.getInstruments(exchangeId) : qts.getInstruments(exchangeId, segment);
  }

  @Override
  public List<PoolDetail> listPools(String exchangeId, String segment) {
    return segment == null || segment.isBlank()
        ? qts.getPools(exchangeId) : qts.getPools(exchangeId, segment);
  }

  @Override
  public MarketDataDownload downloadTickers(
      String exchangeId, String base, String quote, String hour, String format,
      String outputPath, boolean overwrite) {
    return downloadMarketData(exchangeId, base, quote, hour, format, outputPath, overwrite, true);
  }

  @Override
  public MarketDataDownload downloadKlines(
      String exchangeId, String base, String quote, String hour, String format,
      String outputPath, boolean overwrite) {
    return downloadMarketData(exchangeId, base, quote, hour, format, outputPath, overwrite, false);
  }

  private MarketDataDownload downloadMarketData(
      String exchangeId, String base, String quote, String hour, String format,
      String outputPath, boolean overwrite, boolean tickers) {
    if (downloadRoot == null) {
      throw new IllegalStateException(
          "Market-data download is disabled: configure --download-root or QTSURFER_DOWNLOAD_ROOT");
    }
    DownloadFormat downloadFormat = parseDownloadFormat(format);
    Path output = downloadRoot.resolveOutputFile(outputPath, overwrite);
    Path temporary;
    try {
      temporary = Files.createTempFile(output.getParent(), ".qtsurfer-download-", ".part");
    } catch (IOException e) {
      throw new IllegalStateException("Could not create guarded download output", e);
    }
    try (BinaryDownload response = tickers
        ? qts.downloadTickersWithMetadata(exchangeId, base, quote, hour, downloadFormat)
        : qts.downloadKlinesWithMetadata(exchangeId, base, quote, hour, downloadFormat)) {
      long bytes = Files.copy(response.body(), temporary, StandardCopyOption.REPLACE_EXISTING);
      if (overwrite) {
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } else {
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
      }
      return new MarketDataDownload(Path.of(outputPath).normalize().toString(), bytes,
          downloadFormat.wire().wireValue(), response.contentType().orElse(null),
          response.timestampUnit().orElse(null), response.dataStatus().orElse(null),
          response.dataThrough().orElse(null));
    } catch (IOException e) {
      throw new IllegalStateException("Could not stream market-data download to guarded output", e);
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        log.warn("Could not remove partial market-data download {}", temporary);
      }
    }
  }

  private static DownloadFormat parseDownloadFormat(String format) {
    if (format == null || format.isBlank() || "lastra".equalsIgnoreCase(format)) {
      return DownloadFormat.LASTRA;
    }
    if ("parquet".equalsIgnoreCase(format)) return DownloadFormat.PARQUET;
    throw new IllegalArgumentException("format must be lastra or parquet");
  }

  @Override
  public String submitBacktest(BacktestRequest sdkRequest) {

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
      backtest = strategy.executeBacktest(sdkRequest, BacktestOptions.defaults()).join();
    } catch (Exception e) {
      throw new RuntimeException("Backtest submission failed: " + rootMessage(e), e);
    }

    String jobId = backtest.id();
    String submittedAt = Instant.now().toString();
    String finalJobId = jobId;
    AtomicReference<ResultMap> resultRef = new AtomicReference<>();
    AtomicReference<Failure> failureRef = new AtomicReference<>();

    // Track the await future; capture ResultMap when execution completes. A failure is kept, not
    // just logged: this future completes normally either way, so the job's status reads it back.
    CompletableFuture<Void> future = backtest.await()
        .thenAccept(sdkResult -> {
          resultRef.set(sdkResult);
          log.info("Job {} completed — pnl={} trades={}", finalJobId,
              sdkResult.getPnlTotal(), sdkResult.getTotalTrades());
        })
        .exceptionally(err -> {
          String detail = rootMessage(err);
          failureRef.set(new Failure(
              isCancellation(err) ? JobStatus.CANCELED : JobStatus.FAILED, detail));
          log.warn("Job {} ended without a result: {}", finalJobId, detail);
          return null;
        });

    String source = sdkRequest.datasetId() == null ? sdkRequest.instrument()
        : "dataset:" + sdkRequest.datasetId()
            + (sdkRequest.datasetVersionId() == null ? "" : ":" + sdkRequest.datasetVersionId());
    jobs.put(jobId, new SessionJob(
        jobId, source, sdkRequest.exchangeId(), submittedAt, future, backtest, resultRef, failureRef));
    log.info("Submitted backtest {} ({} {} {} → {})", jobId, sdkRequest.exchangeId(), source,
        sdkRequest.from(), sdkRequest.to());
    return jobId;
  }

  @Override
  public Optional<JobSummary> getJobStatus(String jobId, String exchangeId) {
    return getJobStatus(jobId, exchangeId, DataSourceType.TICKER);
  }

  @Override
  public Optional<JobSummary> getJobStatus(String jobId, String exchangeId, DataSourceType type) {
    SessionJob job = jobs.get(jobId);
    if (job == null) {
      if (exchangeId == null || exchangeId.isBlank()) return Optional.empty();
      return fromPlatform(jobId, exchangeId, type == null ? DataSourceType.TICKER : type);
    }
    ResultMap sdkResult = job.resultRef().get();
    JobResult result = sdkResult != null ? toJobResult(sdkResult) : null;
    return Optional.of(new JobSummary(job.jobId(), job.instrument(), job.exchangeId(),
        job.status(), job.submittedAt(), result, null, job.failureDetail()));
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
   * @throws RuntimeException when the platform does not answer for this job, exchange and type
   */
  private Optional<JobSummary> fromPlatform(String jobId, String exchangeId, DataSourceType type) {
    BacktestOutcome outcome;
    try {
      outcome = qts.getBacktestResult(exchangeId, type, jobId);
    } catch (Exception e) {
      throw new RuntimeException(
          "The platform did not return job " + jobId + " on exchange " + exchangeId
              + " as a " + type.getValue() + " run. A result is addressed by exchange, type and job,"
              + " so either the job id is unknown, or the exchange or the type is the wrong one —"
              + " an id carried to the wrong exchange or type does not name the same run."
              + " Details: " + rootMessage(e), e);
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
    // The platform's reason only means something for a run that ended without a result; a
    // completed one can carry an informational detail that would read as an error.
    String detail = state != null && (status == JobStatus.FAILED || status == JobStatus.CANCELED)
        ? state.getStatusDetail() : null;
    return Optional.of(
        new JobSummary(jobId, instrument, exchangeId, status, startedAt, result,
            state == null ? null : state.getRetryable(), detail));
  }

  private static JobResult toJobResult(ResultMap r) {
    List<EquityPoint> curve = toEquityPoints(r.getEquityCurve());
    return new JobResult(
        r.getPnlTotal(), r.getTotalTrades(), r.getWinRate(),
        r.getSharpeRatio(), r.getSortinoRatio(), r.getCagr(),
        r.getMaxDrawdown(), r.getMaxDrawdownPercent(),
        r.getSignalCount() != null ? r.getSignalCount().longValue() : null,
        r.getHostName(), r.getIops(), curve, resultParams(r.getParams()));
  }

  private static Map<String, Object> resultParams(Map<String, ScalarStrategyParamValue> params) {
    if (params == null || params.isEmpty()) return Map.of();
    Map<String, Object> values = new LinkedHashMap<>();
    params.forEach((name, value) -> values.put(name, value == null ? null : value.getActualInstance()));
    return Map.copyOf(values);
  }

  /** Convert either supported inline curve encoding into the MCP's compact point representation. */
  private static List<EquityPoint> toEquityPoints(EquityCurveResult curve) {
    if (curve == null) return List.of();
    if (curve.getPoints() != null) {
      return curve.getPoints().stream()
          .filter(point -> point.getTimestamp() != null && point.getEquity() != null)
          .map(point -> new EquityPoint(point.getTimestamp(), point.getEquity()))
          .toList();
    }
    if (curve.getTimestamps() == null || curve.getEquities() == null) return List.of();
    int count = Math.min(curve.getTimestamps().size(), curve.getEquities().size());
    List<EquityPoint> points = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      Long timestamp = curve.getTimestamps().get(index);
      Double equity = curve.getEquities().get(index);
      if (timestamp != null && equity != null) points.add(new EquityPoint(timestamp, equity));
    }
    return List.copyOf(points);
  }

  @Override
  public boolean cancelBacktest(String jobId) {
    SessionJob job = jobs.get(jobId);
    if (job == null || job.status() != JobStatus.EXECUTING) return false;
    return job.backtest().cancel();
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
    sweeps.put(sweep.id(), new SessionSweep(sweep, request.exchangeId(), request.type()));
    log.info("Submitted sweep {} ({} {} {} → {})", sweep.id(),
        request.exchangeId(), request.instrument(), request.from(), request.to());
    return sweep.getAccepted();
  }

  @Override
  public Optional<ExecuteSweepResult> getSweepStatus(String sweepId) {
    SessionSweep sessionSweep = sweeps.get(sweepId);
    // null/null asks for the platform's own default view: ranked, plateau-ordered.
    return sessionSweep == null ? Optional.empty() : Optional.of(sessionSweep.sweep().getResults(null, null));
  }

  @Override
  public Optional<BoundedEquityCurve> getSweepRunEquityCurve(
      String sweepId, int runIx, Integer maxResample) {
    SessionSweep sessionSweep = sweeps.get(sweepId);
    if (sessionSweep == null) return Optional.empty();
    Sweep sweep = sessionSweep.sweep();
    return Optional.of(qts.getBoundedSweepRunEquityCurve(
        sessionSweep.exchangeId(), sessionSweep.type(), sweep.requestId(), sweepId, runIx, maxResample));
  }

  @Override
  public boolean cancelSweep(String sweepId) {
    SessionSweep sessionSweep = sweeps.get(sweepId);
    return sessionSweep != null && sessionSweep.sweep().cancel();
  }

  @Override
  public Optional<SweepSensitivity> getSweepSensitivity(String sweepId, SweepObjective objective) {
    SessionSweep sessionSweep = sweeps.get(sweepId);
    return sessionSweep == null ? Optional.empty()
        : Optional.of(sessionSweep.sweep().getSensitivity(objective));
  }

  // ---- strategies ------------------------------------------------------------

  @Override
  public ValidationOutcome validateStrategy(String strategyId) {
    return qts.validateStrategy(strategyId);
  }

  @Override
  public Optional<StrategyState> getStrategy(String strategyId) {
    try {
      return Optional.of(qts.getStrategyState(strategyId));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<StrategySummary> listStrategies(boolean includeDeleted) {
    return qts.getStrategies(includeDeleted);
  }

  @Override
  public void deleteStrategy(String strategyId) {
    qts.deleteStrategy(strategyId);
  }

  @Override
  public String getStrategyCode(String strategyId) {
    return qts.getStrategyCode(strategyId);
  }

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) cause = cause.getCause();
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
