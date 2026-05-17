package net.qtsurfer.mcp.service;

import net.qtsurfer.api.client.model.Exchange;
import net.qtsurfer.api.client.model.InstrumentDetail;
import net.qtsurfer.mcp.model.JobStatus;
import net.qtsurfer.mcp.model.JobSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub for local development and unit / integration testing.
 * No network calls — state is in the JVM heap for the lifetime of the instance.
 */
public class BacktestingServiceStub implements BacktestingService {

  private final Map<String, JobSummary> jobs = new ConcurrentHashMap<>();

  @Override
  public List<Exchange> listExchanges() {
    return List.of(
        new Exchange().id("binance").name("Binance").description("Binance spot exchange"),
        new Exchange().id("binancefutures").name("Binance Futures")
            .description("Binance perpetual futures exchange"));
  }

  @Override
  public List<InstrumentDetail> listInstruments(String exchangeId) {
    if ("binancefutures".equals(exchangeId)) {
      return List.of(
          new InstrumentDetail().id("BTC/USDT:USDT").base("BTC").quote("USDT"),
          new InstrumentDetail().id("ETH/USDT:USDT").base("ETH").quote("USDT"));
    }
    return List.of(
        new InstrumentDetail().id("BTC/USDT").base("BTC").quote("USDT"),
        new InstrumentDetail().id("ETH/USDT").base("ETH").quote("USDT"),
        new InstrumentDetail().id("BNB/USDT").base("BNB").quote("USDT"),
        new InstrumentDetail().id("SOL/USDT").base("SOL").quote("USDT"));
  }

  @Override
  public String submitBacktest(
      String strategyCode, String exchangeId, String instrument, String from, String to) {
    if (strategyCode == null || strategyCode.isBlank()) {
      throw new IllegalArgumentException("strategyCode is required");
    }
    if (exchangeId == null || exchangeId.isBlank()) {
      throw new IllegalArgumentException("exchangeId is required");
    }
    if (instrument == null || instrument.isBlank()) {
      throw new IllegalArgumentException("instrument is required");
    }
    String jobId = "bt-" + UUID.randomUUID().toString().substring(0, 8);
    jobs.put(
        jobId,
        new JobSummary(jobId, instrument, exchangeId, JobStatus.EXECUTING, Instant.now().toString()));
    return jobId;
  }

  @Override
  public Optional<JobSummary> getJobStatus(String jobId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  @Override
  public List<JobSummary> listJobs(JobStatus status) {
    List<JobSummary> all = new ArrayList<>(jobs.values());
    if (status != null) all.removeIf(j -> j.status() != status);
    return all;
  }
}
