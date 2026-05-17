package net.qtsurfer.mcp.service;

import net.qtsurfer.api.client.model.Exchange;
import net.qtsurfer.api.client.model.InstrumentDetail;
import net.qtsurfer.mcp.model.JobStatus;
import net.qtsurfer.mcp.model.JobSummary;

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

  /** Current status of a job. Empty if the job ID is unknown to this session. */
  Optional<JobSummary> getJobStatus(String jobId);

  /**
   * Jobs submitted in this session, optionally filtered by status.
   *
   * @param status {@code null} returns all jobs
   */
  List<JobSummary> listJobs(JobStatus status);
}
