package com.qtsurfer.mcp.service;

import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.mcp.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status a submitted run reports. The await future is consumed so that it completes normally
 * whether the run succeeded or not, which once made a run whose preparation the platform rejected
 * read as COMPLETED — a green light with no results behind it.
 */
class SdkBacktestingServiceStatusTest {

  private static final SdkBacktestingService.Failure REJECTED = new SdkBacktestingService.Failure(
      JobStatus.FAILED, "This strategy reads kline data, but the run was submitted against ticker data.");

  @Test
  void aRecordedFailureIsFailedEvenThoughTheFutureCompletedNormally() {
    assertThat(SdkBacktestingService.resolveStatus(REJECTED, false, true, Backtest.State.EXECUTING))
        .isEqualTo(JobStatus.FAILED);
  }

  @Test
  void aRecordedCancellationIsCanceledNotFailed() {
    var canceled = new SdkBacktestingService.Failure(JobStatus.CANCELED, "cancelled");
    assertThat(SdkBacktestingService.resolveStatus(canceled, false, true, Backtest.State.EXECUTING))
        .isEqualTo(JobStatus.CANCELED);
  }

  @Test
  void aFutureThatFinishedWithNoFailureIsCompleted() {
    assertThat(SdkBacktestingService.resolveStatus(null, false, true, Backtest.State.COMPLETED))
        .isEqualTo(JobStatus.COMPLETED);
  }

  @Test
  void aFutureThatCompletedExceptionallyIsFailed() {
    assertThat(SdkBacktestingService.resolveStatus(null, true, true, Backtest.State.EXECUTING))
        .isEqualTo(JobStatus.FAILED);
  }

  @Test
  void cancelMarksTheRunBeforeItsFutureSettles() {
    assertThat(SdkBacktestingService.resolveStatus(null, false, false, Backtest.State.CANCELED))
        .isEqualTo(JobStatus.CANCELED);
  }

  @Test
  void aRunStillInFlightFollowsTheSdkSnapshot() {
    assertThat(SdkBacktestingService.resolveStatus(null, false, false, null))
        .isEqualTo(JobStatus.COMPILING);
    assertThat(SdkBacktestingService.resolveStatus(null, false, false, Backtest.State.EXECUTING))
        .isEqualTo(JobStatus.EXECUTING);
  }

  @Test
  void aCancellationAnywhereInTheCauseChainIsNotAFailure() {
    assertThat(SdkBacktestingService.isCancellation(
        new CompletionException(new CancellationException("polling cancelled")))).isTrue();
    assertThat(SdkBacktestingService.isCancellation(
        new CompletionException(new IllegalStateException("prepare rejected")))).isFalse();
  }
}
