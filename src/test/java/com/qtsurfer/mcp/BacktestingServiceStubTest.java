package com.qtsurfer.mcp;

import com.qtsurfer.api.sdk.ParamAxis;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.service.BacktestingServiceStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestingServiceStubTest {

  BacktestingServiceStub stub;

  @BeforeEach
  void setUp() { stub = new BacktestingServiceStub(); }

  // ---- listExchanges ------------------------------------------------------

  @Test
  void listExchangesReturnsNonEmptyList() {
    assertThat(stub.listExchanges()).isNotEmpty();
  }

  @Test
  void listExchangesIncludesBinance() {
    var ids = stub.listExchanges().stream().map(e -> e.getId()).toList();
    assertThat(ids).contains("binance");
  }

  @Test
  void listExchangesAllHaveNames() {
    stub.listExchanges().forEach(e ->
        assertThat(e.getName()).as("name for %s", e.getId()).isNotBlank());
  }

  // ---- listInstruments ----------------------------------------------------

  @Test
  void listInstrumentsReturnsNonEmptyForBinance() {
    assertThat(stub.listInstruments("binance")).isNotEmpty();
  }

  @Test
  void listInstrumentsReturnsDifferentSetForFutures() {
    var spot = stub.listInstruments("binance").stream().map(i -> i.getId()).toList();
    var perp = stub.listInstruments("binancefutures").stream().map(i -> i.getId()).toList();
    assertThat(spot).doesNotContainAnyElementsOf(perp);
  }

  @Test
  void listInstrumentsAllHaveBaseAndQuote() {
    stub.listInstruments("binance").forEach(i -> {
      assertThat(i.getBase()).as("base for %s", i.getId()).isNotBlank();
      assertThat(i.getQuote()).as("quote for %s", i.getId()).isNotBlank();
    });
  }

  // ---- submitBacktest -----------------------------------------------------

  @Test
  void submitBacktestReturnsJobId() {
    assertThat(stub.submitBacktest("// code", "binance", "BTC/USDT", "2024-01-01", "2024-03-31"))
        .startsWith("bt-");
  }

  @Test
  void submitBacktestRejectsBlankStrategy() {
    assertThatThrownBy(() -> stub.submitBacktest("", "binance", "BTC/USDT", "2024-01-01", "2024-03-31"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strategyCode");
  }

  @Test
  void getJobStatusReturnsExecutingAfterSubmit() {
    String jobId = stub.submitBacktest("// code", "binance", "ETH/USDT", "2024-01-01", "2024-01-31");
    var summary = stub.getJobStatus(jobId, null);
    assertThat(summary).isPresent();
    assertThat(summary.get().status()).isEqualTo(JobStatus.EXECUTING);
    assertThat(summary.get().instrument()).isEqualTo("ETH/USDT");
    assertThat(summary.get().exchangeId()).isEqualTo("binance");
  }

  @Test
  void getJobStatusReturnsEmptyForUnknownId() {
    assertThat(stub.getJobStatus("bt-unknown", null)).isEmpty();
  }

  // ---- cancelBacktest -------------------------------------------------------

  @Test
  void cancelBacktestIsIdempotentAfterTheFirstCall() {
    String jobId = stub.submitBacktest("// c", "binance", "BTC/USDT", "2024-01-01", "2024-01-31");
    assertThat(stub.cancelBacktest(jobId)).isTrue();
    assertThat(stub.cancelBacktest(jobId)).isFalse();
    assertThat(stub.getJobStatus(jobId, null).get().status()).isEqualTo(JobStatus.CANCELED);
  }

  @Test
  void cancelBacktestReturnsFalseForAnUnknownJob() {
    assertThat(stub.cancelBacktest("bt-unknown")).isFalse();
  }

  @Test
  void listJobsReturnsAll() {
    stub.submitBacktest("// c", "binance", "BTC/USDT", "2024-01-01", "2024-01-31");
    stub.submitBacktest("// c", "binance", "ETH/USDT", "2024-01-01", "2024-01-31");
    assertThat(stub.listJobs(null)).hasSize(2);
  }

  @Test
  void listJobsFiltersCorrectly() {
    stub.submitBacktest("// c", "binance", "BTC/USDT", "2024-01-01", "2024-01-31");
    assertThat(stub.listJobs(JobStatus.EXECUTING)).hasSize(1);
    assertThat(stub.listJobs(JobStatus.COMPLETED)).isEmpty();
  }

  // ---- sweeps -------------------------------------------------------------

  private static SweepRequest sweepRequest(int folds) {
    SweepRequest.Builder builder = SweepRequest.builder()
        .strategy("// code").exchangeId("binance").instrument("BTC/USDT")
        .from("2024-01-01").to("2024-03-31")
        .param("rsiPeriod", ParamAxis.range(1, 40, 1));
    if (folds > 0) builder.walkForward(folds);
    return builder.build();
  }

  @Test
  void submitSweepReportsTheGridSize() {
    var accepted = stub.submitSweep(sweepRequest(0));
    assertThat(accepted.getSweepId()).startsWith("sw-");
    assertThat(accepted.getTotalRuns()).isEqualTo(40);
    assertThat(accepted.getWalkForward()).isNull();
  }

  @Test
  void submitSweepWithFoldsAnnouncesWalkForward() {
    var accepted = stub.submitSweep(sweepRequest(3));
    assertThat(accepted.getWalkForward()).isNotNull();
    assertThat(accepted.getWalkForward().getFolds()).isEqualTo(3);
    assertThat(accepted.getTotalRuns()).isEqualTo(120);
  }

  @Test
  void sweepLeaderboardIsTruncatedAndSaysHowManyRowsExist() {
    String sweepId = stub.submitSweep(sweepRequest(0)).getSweepId();
    var result = stub.getSweepStatus(sweepId).orElseThrow();
    assertThat(result.getLeaderboard()).hasSize(25);
    assertThat(result.getLeaderboardSize()).isEqualTo(40);
    assertThat(result.getTruncated()).isTrue();
  }

  @Test
  void walkForwardSweepAnswersOneRowPerFold() {
    String sweepId = stub.submitSweep(sweepRequest(3)).getSweepId();
    var result = stub.getSweepStatus(sweepId).orElseThrow();
    assertThat(result.getLeaderboard()).hasSize(3);
    assertThat(result.getWalkForward()).isNotNull();
    assertThat(result.getLeaderboard().get(2).getRunIx()).isEqualTo(2);
    assertThat(result.getLeaderboard().get(0).getPlateauScore()).isNull();
  }

  @Test
  void cancelSweepIsIdempotentAfterTheFirstCall() {
    String sweepId = stub.submitSweep(sweepRequest(0)).getSweepId();
    assertThat(stub.cancelSweep(sweepId)).isTrue();
    assertThat(stub.cancelSweep(sweepId)).isFalse();
  }

  @Test
  void cancelSweepReturnsFalseForAnUnknownSweep() {
    assertThat(stub.cancelSweep("sw-unknown")).isFalse();
  }

  @Test
  void sweepSensitivityHonoursTheRequestedObjective() {
    String sweepId = stub.submitSweep(sweepRequest(0)).getSweepId();
    var sensitivity = stub.getSweepSensitivity(sweepId, SweepObjective.SORTINO).orElseThrow();
    assertThat(sensitivity.getObjective().getValue()).isEqualTo("sortino");
    assertThat(sensitivity.getMarginals()).hasSize(1);
  }

  @Test
  void sweepReadsAreEmptyForAnUnknownSweep() {
    assertThat(stub.getSweepStatus("sw-unknown")).isEmpty();
    assertThat(stub.getSweepSensitivity("sw-unknown", null)).isEmpty();
  }
}
