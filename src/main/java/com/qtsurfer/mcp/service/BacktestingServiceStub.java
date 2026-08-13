package com.qtsurfer.mcp.service;

import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.SweepHeatmap;
import com.qtsurfer.api.client.model.SweepHeatmapCell;
import com.qtsurfer.api.client.model.SweepMarginal;
import com.qtsurfer.api.client.model.SweepMarginalPoint;
import com.qtsurfer.api.client.model.SweepProgress;
import com.qtsurfer.api.client.model.SweepRunRow;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.client.model.WalkForwardFold;
import com.qtsurfer.api.client.model.WalkForwardResult;
import com.qtsurfer.api.sdk.ParamAxis;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.WalkForwardSpec;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub for local development and unit / integration testing.
 * No network calls — state is in the JVM heap for the lifetime of the instance.
 *
 * <p>The sweep side answers with synthetic but structurally faithful payloads: a leaderboard
 * longer than any sane display cap, a {@code truncated} flag, plateau scores with neighbour
 * counts, and — when the request asks for it — a walk-forward block with one row per fold.
 * That is what lets the offline tests exercise the capping and the walk-forward shape without
 * a backend.
 */
public class BacktestingServiceStub implements BacktestingService {

  /** How many leaderboard rows the stub's synthetic sweep carries, at most. */
  private static final int STUB_LEADERBOARD_ROWS = 25;
  /** How many pair surfaces the stub's synthetic sensitivity carries, at most. */
  private static final int STUB_HEATMAP_CAP = 3;

  private final Map<String, JobSummary> jobs = new ConcurrentHashMap<>();
  private final Map<String, ExecuteSweepResult> sweeps = new ConcurrentHashMap<>();
  private final Map<String, SweepSensitivity> sensitivities = new ConcurrentHashMap<>();

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

  /**
   * {@inheritDoc}
   *
   * <p>There is no platform behind the stub, so a job it never issued stays unknown whatever
   * {@code exchangeId} says.
   */
  @Override
  public Optional<JobSummary> getJobStatus(String jobId, String exchangeId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  @Override
  public boolean cancelBacktest(String jobId) {
    JobSummary job = jobs.get(jobId);
    if (job == null || job.status() != JobStatus.EXECUTING) return false;
    jobs.put(jobId, new JobSummary(
        jobId, job.instrument(), job.exchangeId(), JobStatus.CANCELED, job.submittedAt()));
    return true;
  }

  @Override
  public List<JobSummary> listJobs(JobStatus status) {
    List<JobSummary> all = new ArrayList<>(jobs.values());
    if (status != null) all.removeIf(j -> j.status() != status);
    return all;
  }

  // ---- sweeps ---------------------------------------------------------------

  @Override
  public ExecuteSweepAccepted submitSweep(SweepRequest request) {
    if (request.strategy().isBlank()) {
      throw new IllegalArgumentException("strategyCode is required");
    }
    if (request.exchangeId().isBlank()) {
      throw new IllegalArgumentException("exchangeId is required");
    }
    if (request.instrument().isBlank()) {
      throw new IllegalArgumentException("instrument is required");
    }
    String sweepId = "sw-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, List<Object>> grid = enumerate(request.params());
    int gridSize = grid.values().stream().mapToInt(List::size).reduce(1, (a, b) -> a * b);
    WalkForwardSpec wf = request.walkForward();

    sweeps.put(sweepId, syntheticResult(sweepId, gridSize, wf));
    sensitivities.put(sweepId, syntheticSensitivity(sweepId, grid));

    ExecuteSweepAccepted accepted = new ExecuteSweepAccepted()
        .sweepId(sweepId)
        .requestId("prep-" + sweepId)
        .totalRuns(wf == null ? gridSize : gridSize * wf.folds())
        .shards(1)
        .seed(request.seed() != null ? request.seed() : 42L)
        .queued(true);
    if (wf != null) {
      accepted.walkForward(new com.qtsurfer.api.client.model.WalkForwardAccepted()
          .folds(wf.folds())
          .inSamplePct(wf.inSamplePct() != null ? wf.inSamplePct() : 70)
          .totalRuns(gridSize * wf.folds()));
    }
    return accepted;
  }

  @Override
  public Optional<ExecuteSweepResult> getSweepStatus(String sweepId) {
    return Optional.ofNullable(sweeps.get(sweepId));
  }

  @Override
  public boolean cancelSweep(String sweepId) {
    ExecuteSweepResult result = sweeps.get(sweepId);
    if (result == null || result.getStatus() == ExecuteSweepResult.StatusEnum.CANCELLED) {
      return false;
    }
    result.setStatus(ExecuteSweepResult.StatusEnum.CANCELLED);
    return true;
  }

  @Override
  public Optional<SweepSensitivity> getSweepSensitivity(String sweepId, SweepObjective objective) {
    SweepSensitivity sensitivity = sensitivities.get(sweepId);
    if (sensitivity == null) return Optional.empty();
    if (objective != null) {
      sensitivity.setObjective(
          SweepSensitivity.ObjectiveEnum.fromValue(objective.wire()));
    }
    return Optional.of(sensitivity);
  }

  // ---- synthetic sweep payloads ---------------------------------------------

  /**
   * Expand each axis into the values a sweep of this grid would actually try.
   *
   * <p>Sorted by axis name. {@code SweepRequest} holds its grid in an immutable map whose
   * iteration order is not the submission order, so anything downstream that depends on axis
   * order — which pair surfaces survive a cap, most visibly — has to impose one of its own or
   * it is not reproducible from one run to the next.
   */
  private static Map<String, List<Object>> enumerate(Map<String, ParamAxis> params) {
    Map<String, List<Object>> out = new LinkedHashMap<>();
    new TreeMap<>(params).forEach((name, axis) -> {
      List<Object> values = new ArrayList<>();
      if (axis instanceof ParamAxis.Range r) {
        for (double v = r.from(); v <= r.to() + 1e-9; v += r.step()) {
          values.add(v);
        }
      } else if (axis instanceof ParamAxis.Values v) {
        values.addAll(v.values());
      }
      out.put(name, values);
    });
    return out;
  }

  private static ExecuteSweepResult syntheticResult(
      String sweepId, int gridSize, WalkForwardSpec wf) {
    ExecuteSweepResult result = new ExecuteSweepResult()
        .sweepId(sweepId)
        .status(ExecuteSweepResult.StatusEnum.COMPLETED)
        .objective(ExecuteSweepResult.ObjectiveEnum.SHARPE)
        .order(ExecuteSweepResult.OrderEnum.RANKED)
        .ranking(ExecuteSweepResult.RankingEnum.PLATEAU)
        .progress(new SweepProgress()
            .done((long) gridSize).total(gridSize)
            .aborted(0L).shardCount(1).pendingShards(0).failedShards(0L));

    if (wf != null) {
      int folds = wf.folds();
      List<SweepRunRow> rows = new ArrayList<>(folds);
      List<WalkForwardFold> foldRows = new ArrayList<>(folds);
      for (int i = 0; i < folds; i++) {
        // runIx carries the fold index on a walk-forward sweep, not a grid position.
        SweepRunRow row = new SweepRunRow()
            .runIx(i).rank(i + 1)
            .sharpe(1.5 - i * 0.1).sortino(1.9 - i * 0.1)
            .pnl(120.0 - i * 10).pnlPct(12.0 - i).maxDdPct(-6.5 - i)
            .trades(80L - i).winRate(55.0 - i).aborted(false);
        rows.add(row);
        foldRows.add(new WalkForwardFold()
            .foldIx(i).vectorsRun(gridSize)
            .inSampleSharpe(1.9 - i * 0.1).outOfSample(row));
      }
      result.leaderboard(rows).leaderboardSize(folds).truncated(false);
      result.walkForward(new WalkForwardResult()
          .folds(folds)
          .inSamplePct(wf.inSamplePct() != null ? wf.inSamplePct() : 70)
          .completedFolds(folds)
          .paramDrift(0.25)
          .results(foldRows));
      return result;
    }

    int carried = Math.min(gridSize, STUB_LEADERBOARD_ROWS);
    List<SweepRunRow> rows = new ArrayList<>(carried);
    for (int i = 0; i < carried; i++) {
      rows.add(new SweepRunRow()
          .runIx(i).rank(i + 1)
          .plateauScore(1.4 - i * 0.03)
          .neighbourCount(i == 0 ? 0 : Math.min(4, i))
          .deflatedSharpe(0.97 - i * 0.02)
          .sharpe(1.6 - i * 0.03).sortino(2.0 - i * 0.03)
          .pnl(150.0 - i * 4).pnlPct(15.0 - i * 0.4).cagr(0.21 - i * 0.005)
          .maxDdPct(-7.0 - i * 0.2).trades(90L - i).winRate(57.0 - i * 0.3)
          .belowTradeFloor(false).aborted(false).runtimeMs(1200L + i));
    }
    return result
        .leaderboard(rows)
        .leaderboardSize(gridSize)
        .truncated(gridSize > carried)
        .pbo(0.32)
        .pboSplits(8);
  }

  private static SweepSensitivity syntheticSensitivity(
      String sweepId, Map<String, List<Object>> grid) {
    List<SweepMarginal> marginals = new ArrayList<>();
    grid.forEach((name, values) -> {
      SweepMarginal marginal = new SweepMarginal().param(name);
      for (int i = 0; i < values.size(); i++) {
        marginal.addPointsItem(new SweepMarginalPoint()
            .value(values.get(i)).count(4)
            .best(1.5 - i * 0.05).mean(0.9 - i * 0.04).worst(0.2 - i * 0.03));
      }
      marginals.add(marginal);
    });

    List<String> names = new ArrayList<>(grid.keySet());
    List<SweepHeatmap> heatmaps = new ArrayList<>();
    int pairs = 0;
    boolean dropped = false;
    for (int a = 0; a < names.size(); a++) {
      for (int b = a + 1; b < names.size(); b++) {
        if (pairs++ >= STUB_HEATMAP_CAP) { dropped = true; continue; }
        heatmaps.add(new SweepHeatmap()
            .paramA(names.get(a)).paramB(names.get(b))
            .addCellsItem(new SweepHeatmapCell()
                .valueA(grid.get(names.get(a)).get(0))
                .valueB(grid.get(names.get(b)).get(0))
                .count(2).best(1.4).mean(0.8)));
      }
    }
    return new SweepSensitivity()
        .sweepId(sweepId)
        .status(SweepSensitivity.StatusEnum.COMPLETED)
        .objective(SweepSensitivity.ObjectiveEnum.SHARPE)
        .rowsAnalysed(marginals.isEmpty() ? 0 : 24)
        .marginals(marginals)
        .heatmaps(heatmaps)
        .heatmapsTruncated(dropped);
  }
}
