package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.StrategySummary;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.client.model.Account;
import com.qtsurfer.api.client.model.AccountUsage;
import com.qtsurfer.api.client.model.LiveListResponse;
import com.qtsurfer.api.client.model.LiveParamsUpdateResult;
import com.qtsurfer.api.client.model.LiveRun;
import com.qtsurfer.api.client.model.LiveRunCompact;
import com.qtsurfer.api.client.model.LiveSignalPage;
import com.qtsurfer.api.client.model.PublicLiveListResponse;
import com.qtsurfer.api.client.model.StartLiveRequest;
import com.qtsurfer.api.client.model.UpdateLiveRequest;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.ValidationOutcome;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.BoundedEquityCurve;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;
import com.qtsurfer.mcp.model.DatasetSummary;
import com.qtsurfer.mcp.model.DatasetUploadResult;
import com.qtsurfer.mcp.model.DatasetUploadStatus;
import com.qtsurfer.mcp.model.DatasetImportResult;
import com.qtsurfer.mcp.model.DatasetImportStatus;
import com.qtsurfer.mcp.model.StrategyCompilation;
import com.qtsurfer.mcp.model.MarketDataDownload;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain interface for the QTSurfer capabilities exposed as MCP tools.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link SdkBacktestingService} — delegates to {@code qtsurfer-sdk-java}.
 *   <li>{@link BacktestingServiceStub} — in-memory stub for local dev and tests.
 * </ul>
 *
 * <p>Backtests and sweeps differ in how far they can be followed from a fresh process.
 * A backtest result is addressable on the platform by exchange plus job id, so
 * {@link #getJobStatus} can answer for a run this process did not start. A sweep is only
 * reachable through the handle its submission returned, so the sweep read, cancel and
 * sensitivity calls answer for this session's sweeps and nothing else.
 */
public interface BacktestingService {

  /** Compile source and return the registered strategy plus its declared tuning properties. */
  StrategyCompilation compileStrategy(String strategyCode);

  /** List datasets owned by the authenticated caller. */
  List<DatasetSummary> listDatasets();

  /** Read one dataset owned by the authenticated caller. */
  Optional<DatasetSummary> getDataset(String datasetId);

  /** Delete one caller-owned dataset. */
  void deleteDataset(String datasetId);

  /** Create or version a dataset, upload a guarded file, and request asynchronous ingest. */
  DatasetUploadResult uploadDataset(String datasetId, String name, String instrument, String filePath);

  /** Read a dataset upload's ingest status. */
  Optional<DatasetUploadStatus> getDatasetUpload(String datasetId, String uploadId);

  /** Retry finalization after an upload that succeeded before the MCP call failed. */
  String finalizeDatasetUpload(String datasetId, String uploadId);

  /** Start an external DEX-history import. */
  DatasetImportResult importDataset(String name, String instrument, String from, String to,
      String network, String protocol, String version, String contract);

  /** Read one external import's fetch and ingest state. */
  Optional<DatasetImportStatus> getDatasetImport(String datasetId, String importId);

  /** List available exchanges on the platform. */
  List<Exchange> listExchanges();

  /**
   * List instruments available on the given exchange, including data availability.
   *
   * @param exchangeId exchange identifier (e.g. {@code "binance"})
   */
  default List<InstrumentDetail> listInstruments(String exchangeId) {
    return listInstruments(exchangeId, null);
  }

  /** List instruments, optionally restricted to one platform market segment. */
  List<InstrumentDetail> listInstruments(String exchangeId, String segment);

  /** Stream one hourly ticker segment to a guarded local output file. */
  MarketDataDownload downloadTickers(
      String exchangeId, String base, String quote, String hour, String format,
      String outputPath, boolean overwrite);

  /** Stream one hourly kline segment to a guarded local output file. */
  MarketDataDownload downloadKlines(
      String exchangeId, String base, String quote, String hour, String format,
      String outputPath, boolean overwrite);

  /**
   * Compile and submit a backtest job. Returns the server-assigned job ID.
   *
   * @param request complete SDK request, including exactly one instrument or dataset source
   * @throws IllegalArgumentException on invalid input
   * @throws RuntimeException on backend error
   */
  String submitBacktest(BacktestRequest request);

  /** Submit a normal exchange-instrument backtest for compatibility with existing callers. */
  default String submitBacktest(
      String strategyCode, String exchangeId, String instrument, String from, String to) {
    return submitBacktest(BacktestRequest.builder().strategy(strategyCode).exchangeId(exchangeId)
        .instrument(instrument).from(from).to(to).build());
  }

  /**
   * Current status of a backtest job.
   *
   * <p>Jobs submitted in this session are answered from local state. Any other job id is
   * looked up on the platform, which addresses a result by exchange as well as job — so only
   * those need {@code exchangeId}, and it is unused for the rest.
   *
   * @param jobId      execution identifier
   * @param exchangeId exchange the run was submitted against; may be {@code null} for a job
   *                   this session submitted, required for any other
   * @return empty when the job is unknown to this session and no {@code exchangeId} was given,
   *         or when the platform does not recognise the pair
   */
  Optional<JobSummary> getJobStatus(String jobId, String exchangeId);

  /**
   * Ask the platform to stop a backtest submitted in this session.
   *
   * <p>Session-scoped, like {@link #cancelSweep}: the SDK's cancel hook lives on the
   * {@code Backtest} handle submission returned, so a job id from elsewhere has no handle to
   * cancel through, regardless of whether {@link #getJobStatus} can read it off the platform.
   *
   * @param jobId execution identifier
   * @return {@code true} if the call stopped a job that was still running, {@code false} if the
   *         job is unknown to this session or had already reached a terminal state
   */
  boolean cancelBacktest(String jobId);

  /**
   * Jobs submitted in this session, optionally filtered by status.
   *
   * <p>Session-scoped by construction: the API exposes no operation that lists a caller's
   * jobs, so there is nothing to fall back to for jobs submitted elsewhere.
   *
   * @param status {@code null} returns all jobs
   */
  List<JobSummary> listJobs(JobStatus status);

  /**
   * Compile the strategy, prepare the dataset and submit a parameter sweep. Blocks until the
   * platform accepts the sweep, because the sweep id does not exist before then.
   *
   * @param request the grid, the instrument and the window
   * @return the platform's acceptance: sweep id, grid size, effective seed, and whether this
   *         submission enqueued anything
   * @throws IllegalArgumentException on invalid input
   * @throws RuntimeException on backend error
   */
  ExecuteSweepAccepted submitSweep(SweepRequest request);

  /**
   * Re-read the leaderboard of a sweep submitted in this session, in the platform's default
   * view. Readable while the sweep is still running, where it carries the rows finished so far.
   *
   * @param sweepId sweep identifier
   * @return empty when the sweep is unknown to this session
   */
  Optional<ExecuteSweepResult> getSweepStatus(String sweepId);

  /**
   * Read one retained sweep-run curve through the SDK's normalized, bounded façade.
   *
   * <p>Session-scoped: the sweep handle preserves the request and exchange provenance required
   * by the API. The returned curve never exposes generated API response models.
   *
   * @param sweepId sweep identifier
   * @param runIx trial index in the sweep
   * @param maxResample optional requested point ceiling; {@code null} uses the SDK default
   * @return the normalized bounded curve, or empty when the sweep is unknown to this session
   * @throws IllegalArgumentException if {@code maxResample} is outside the SDK safety bound
   */
  Optional<BoundedEquityCurve> getSweepRunEquityCurve(
      String sweepId, int runIx, Integer maxResample);

  /**
   * Ask the platform to stop a sweep submitted in this session between parameter vectors.
   * Rows already finished stay readable.
   *
   * @param sweepId sweep identifier
   * @return {@code true} if the call stopped a sweep that was still running, {@code false} if
   *         the sweep is unknown to this session or had already stopped
   */
  boolean cancelSweep(String sweepId);

  /**
   * Read how the objective responds to each swept axis for a sweep submitted in this session.
   *
   * @param sweepId   sweep identifier
   * @param objective metric to aggregate; {@code null} uses the objective the sweep was
   *                  submitted with
   * @return empty when the sweep is unknown to this session
   */
  Optional<SweepSensitivity> getSweepSensitivity(String sweepId, SweepObjective objective);

  // ---- strategies ------------------------------------------------------------

  /** Request validation of a registered strategy or return its already-known state. */
  ValidationOutcome validateStrategy(String strategyId);

  /** Read the current detailed state of a registered strategy. */
  Optional<StrategyState> getStrategy(String strategyId);

  /**
   * List every strategy registered under the account behind this session's API key, most
   * recently compiled first. Unlike jobs and sweeps this is account-scoped, not session-scoped:
   * it answers for strategies compiled through any client, not just this session's calls.
   *
   * <p>Deliberately cheaper than reading each strategy individually: every entry carries the
   * same {@code compiledAt} / {@code requiredSources} provenance a full strategy state would,
   * but not validation state, so listing stays cheap no matter how many strategies exist.
   *
   * @return the caller's registered strategies; empty when the account has none, never an error
   */
  List<StrategySummary> listStrategies();

  /**
   * Release a registered strategy. Not undone by recompiling the same source afterward — that
   * registers a brand-new strategy under a new id, it does not "undelete" this one. Backtests
   * already run against this strategy are completely unaffected.
   *
   * @param strategyId id of a registered strategy
   * @throws IllegalArgumentException on invalid input
   * @throws RuntimeException if the platform has no such strategy for this caller, or on backend
   *         error
   */
  void deleteStrategy(String strategyId);

  /**
   * Fetch the exact source last registered for a strategy id — the same text originally
   * compiled, whitespace and comments included.
   *
   * @param strategyId id of a registered strategy
   * @return the raw strategy source last registered for this id
   * @throws IllegalArgumentException on invalid input
   * @throws RuntimeException if the platform has no such strategy for this caller, or on backend
   *         error
   */
  String getStrategyCode(String strategyId);

  Account getAccount();

  AccountUsage getAccountUsage();

  LiveRun startLive(String strategyId, StartLiveRequest request);

  LiveRun getLive(String strategyId);

  LiveRun stopLive(String strategyId);

  LiveListResponse listLive(String cursor, Integer limit);

  PublicLiveListResponse listPublicLive(String cursor, Integer limit);

  LiveRunCompact updateLive(String runId, UpdateLiveRequest request);

  LiveParamsUpdateResult updateLiveParams(String runId, Map<String, Object> params);

  LiveSignalPage getLiveSignals(String runId, Long sinceMs, String instrument,
      String cursor, Integer limit);
}
