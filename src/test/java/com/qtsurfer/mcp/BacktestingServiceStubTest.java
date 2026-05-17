package com.qtsurfer.mcp;

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
    var summary = stub.getJobStatus(jobId);
    assertThat(summary).isPresent();
    assertThat(summary.get().status()).isEqualTo(JobStatus.EXECUTING);
    assertThat(summary.get().instrument()).isEqualTo("ETH/USDT");
    assertThat(summary.get().exchangeId()).isEqualTo("binance");
  }

  @Test
  void getJobStatusReturnsEmptyForUnknownId() {
    assertThat(stub.getJobStatus("bt-unknown")).isEmpty();
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
}
