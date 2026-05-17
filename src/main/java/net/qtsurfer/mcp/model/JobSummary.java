package net.qtsurfer.mcp.model;

/**
 * Lightweight view of a backtest job returned by status / list queries.
 *
 * @param jobId       server-assigned execution identifier
 * @param instrument  CCXT-style instrument (e.g. {@code BTC/USDT})
 * @param exchangeId  exchange (e.g. {@code binance})
 * @param status      current lifecycle state
 * @param submittedAt ISO-8601 submission timestamp
 * @param result      execution metrics, non-null only when {@code status} is COMPLETED
 */
public record JobSummary(
    String jobId, String instrument, String exchangeId, JobStatus status,
    String submittedAt, JobResult result) {

  /** Convenience constructor for jobs that have not yet completed. */
  public JobSummary(String jobId, String instrument, String exchangeId,
                    JobStatus status, String submittedAt) {
    this(jobId, instrument, exchangeId, status, submittedAt, null);
  }

  @Override
  public String toString() {
    return "jobId=" + jobId
        + " instrument=" + instrument
        + " exchange=" + exchangeId
        + " status=" + status
        + " submitted=" + submittedAt;
  }
}
