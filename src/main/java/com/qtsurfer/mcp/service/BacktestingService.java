package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;

import java.util.List;
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

  /** List available exchanges on the platform. */
  List<Exchange> listExchanges();

  /**
   * List instruments available on the given exchange, including data availability.
   *
   * @param exchangeId exchange identifier (e.g. {@code "binance"})
   */
  List<InstrumentDetail> listInstruments(String exchangeId);

  /**
   * Compile and submit a backtest job. Returns the server-assigned job ID.
   *
   * @param strategyCode Java source of the strategy to compile
   * @param exchangeId   exchange identifier (e.g. {@code "binance"})
   * @param instrument   CCXT instrument (e.g. {@code "BTC/USDT"})
   * @param from         ISO-8601 start date
   * @param to           ISO-8601 end date
   * @throws IllegalArgumentException on invalid input
   * @throws RuntimeException on backend error
   */
  String submitBacktest(
      String strategyCode, String exchangeId, String instrument, String from, String to);

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
}
