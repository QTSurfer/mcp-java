package com.qtsurfer.mcp.model;

/**
 * Lightweight view of a backtest job returned by status / list queries.
 *
 * @param jobId        server-assigned execution identifier
 * @param instrument   CCXT-style instrument (e.g. {@code BTC/USDT})
 * @param exchangeId   exchange (e.g. {@code binance})
 * @param status       current lifecycle state
 * @param submittedAt  ISO-8601 submission timestamp
 * @param result       execution metrics, non-null only when {@code status} is COMPLETED
 * @param retryable    whether the same request can succeed on retry, when the API says so
 * @param statusDetail why the run ended without a result, when it did and the platform said why
 */
public record JobSummary(
    String jobId, String instrument, String exchangeId, JobStatus status,
    String submittedAt, JobResult result, Boolean retryable, String statusDetail) {

  /** Preserve the constructor that predates {@code statusDetail}. */
  public JobSummary(String jobId, String instrument, String exchangeId, JobStatus status,
      String submittedAt, JobResult result, Boolean retryable) {
    this(jobId, instrument, exchangeId, status, submittedAt, result, retryable, null);
  }

  /** Preserve the original completed-job constructor. */
  public JobSummary(String jobId, String instrument, String exchangeId, JobStatus status,
      String submittedAt, JobResult result) {
    this(jobId, instrument, exchangeId, status, submittedAt, result, null);
  }

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
        + " submitted=" + submittedAt
        + (retryable == null ? "" : " retryable=" + retryable)
        + (statusDetail == null ? "" : " detail=" + statusDetail);
  }
}
