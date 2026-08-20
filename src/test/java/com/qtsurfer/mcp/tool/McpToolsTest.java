package com.qtsurfer.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import com.qtsurfer.mcp.model.EquityPoint;
import com.qtsurfer.mcp.model.JobResult;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.model.JobSummary;
import com.qtsurfer.mcp.service.BacktestingServiceStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the MCP tool handlers. Uses the in-memory stub so no Mockito
 * byte-buddy dependency is required — the stub is deterministic and fast enough
 * for this purpose, and avoids Java 25 agent-attachment issues with Mockito.
 */
class McpToolsTest {

  BacktestingServiceStub service;
  List<SyncToolSpecification> tools;

  @BeforeEach
  void setUp() {
    service = new BacktestingServiceStub();
    tools = McpTools.build(service, "https://api.qtsurfer.net/v1");
  }

  private SyncToolSpecification tool(String name) {
    return tools.stream().filter(t -> t.tool().name().equals(name)).findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  private CallToolResult call(String toolName, Map<String, Object> args) {
    // exchange is unused by all handlers — null is safe
    return tool(toolName).callHandler().apply(null, new CallToolRequest(toolName, args));
  }

  private String textOf(CallToolResult result) {
    return result.content().stream()
        .map(c -> c instanceof TextContent tc ? tc.text() : "")
        .reduce("", String::concat);
  }

  // ---- tool registration --------------------------------------------------

  @Test
  void registersExactlyFifteenTools() {
    assertThat(tools).hasSize(15);
  }

  @Test
  void toolNamesAreCorrect() {
    var names = tools.stream().map(t -> t.tool().name()).toList();
    assertThat(names).containsExactlyInAnyOrder(
        "version", "list_exchanges", "list_instruments", "submit_backtest",
        "get_job_status", "cancel_backtest", "get_equity_curve", "list_jobs",
        "submit_sweep", "get_sweep_status", "cancel_sweep", "get_sweep_sensitivity",
        "list_strategies", "delete_strategy", "get_strategy_code");
  }

  // ---- version ------------------------------------------------------------

  @Test
  void versionReturnsServerNameAndApiUrl() {
    var result = call("version", Map.of());
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result))
        .contains("qtsurfer-mcp")
        .contains("api.qtsurfer.net");
  }

  @Test
  void allToolsHaveNonBlankDescriptions() {
    tools.forEach(t -> assertThat(t.tool().description())
        .as("description for %s", t.tool().name()).isNotBlank());
  }

  // ---- list_exchanges -----------------------------------------------------

  @Test
  void listExchangesReturnsBinance() {
    assertThat(textOf(call("list_exchanges", Map.of()))).contains("binance");
  }

  @Test
  void listExchangesIsNotError() {
    assertThat(call("list_exchanges", Map.of()).isError()).isNotEqualTo(Boolean.TRUE);
  }

  // ---- list_instruments ---------------------------------------------------

  @Test
  void listInstrumentsRequiresExchangeId() {
    var result = call("list_instruments", Map.of());
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("exchangeId");
  }

  @Test
  void listInstrumentsReturnsBtcForBinance() {
    var result = call("list_instruments", Map.of("exchangeId", "binance"));
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("BTC/USDT");
  }

  @Test
  void listInstrumentsReturnsPerpForFutures() {
    var result = call("list_instruments", Map.of("exchangeId", "binancefutures"));
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("BTC/USDT:USDT");
  }

  // ---- submit_backtest ----------------------------------------------------

  @Test
  void submitBacktestReturnsJobId() {
    var result = call("submit_backtest", Map.of(
        "strategyCode", "// code", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-03-31"));
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("bt-");
  }

  @Test
  void submitBacktestReturnsErrorOnBlankStrategy() {
    var result = call("submit_backtest", Map.of(
        "strategyCode", "   ", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-03-31"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
  }

  @Test
  void submitBacktestReturnsErrorOnMissingArg() {
    // "to" is missing
    var result = call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("to");
  }

  // ---- get_job_status -----------------------------------------------------

  @Test
  void getJobStatusTellsAnAgentToSupplyExchangeIdForAnUnknownId() {
    assertThat(textOf(call("get_job_status", Map.of("jobId", "bt-unknown"))))
        .contains("was not submitted in this session")
        .contains("exchangeId");
  }

  @Test
  void getJobStatusReportsAPlatformMissWhenExchangeIdWasGiven() {
    String text = textOf(call("get_job_status",
        Map.of("jobId", "bt-unknown", "exchangeId", "binance")));
    assertThat(text).contains("the platform returned nothing").contains("binance");
  }

  @Test
  void getJobStatusReturnsDetailsAfterSubmit() {
    var submitted = call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "ETH/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    String text = textOf(submitted);
    String jobId = text.substring(text.indexOf("bt-"), text.indexOf("bt-") + 11);

    var status = call("get_job_status", Map.of("jobId", jobId));
    assertThat(textOf(status)).contains(jobId, "ETH/USDT");
  }

  // ---- cancel_backtest ------------------------------------------------------

  @Test
  void cancelBacktestStopsARunningJobOnceAndThenReportsNothingToStop() {
    var submitted = call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    String text = textOf(submitted);
    String jobId = text.substring(text.indexOf("bt-"), text.indexOf("bt-") + 11);

    assertThat(textOf(call("cancel_backtest", Map.of("jobId", jobId))))
        .contains("Cancellation requested");
    assertThat(textOf(call("cancel_backtest", Map.of("jobId", jobId))))
        .contains("was not running");
  }

  @Test
  void cancelBacktestReportsAnUnknownJob() {
    assertThat(textOf(call("cancel_backtest", Map.of("jobId", "bt-unknown"))))
        .contains("was not submitted in this session");
  }

  // ---- list_jobs ----------------------------------------------------------

  @Test
  void listJobsEmptyInitially() {
    assertThat(textOf(call("list_jobs", Map.of()))).contains("No jobs found");
  }

  @Test
  void listJobsReturnsJobsAfterSubmit() {
    call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    assertThat(textOf(call("list_jobs", Map.of()))).contains("BTC/USDT");
  }

  @Test
  void listJobsRejectsUnknownStatus() {
    var result = call("list_jobs", Map.of("status", "NOT_A_STATUS"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("Unknown status");
  }

  @Test
  void listJobsFilterByStatus() {
    call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    // Jobs start as EXECUTING in stub
    assertThat(textOf(call("list_jobs", Map.of("status", "EXECUTING")))).contains("BTC/USDT");
    assertThat(textOf(call("list_jobs", Map.of("status", "COMPLETED")))).contains("No jobs found");
  }

  // ---- equity curve -------------------------------------------------------

  /** Stub reporting one COMPLETED job ("bt-curve") with a synthetic 1000-point curve. */
  private static List<SyncToolSpecification> toolsWithCurve() {
    BacktestingServiceStub stub = new BacktestingServiceStub() {
      @Override
      public Optional<JobSummary> getJobStatus(String jobId, String exchangeId) {
        if (!"bt-curve".equals(jobId)) return super.getJobStatus(jobId, exchangeId);
        List<EquityPoint> curve = new ArrayList<>();
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 1000; i++) {
          curve.add(new EquityPoint(t0 + i * 60_000L, 100.0 + Math.sin(i / 50.0) * 10 + i * 0.01));
        }
        JobResult r = new JobResult(12.3, 5L, 60.0, 1.2, 1.5, 0.2, -5.0, -4.5, 7L, "host", 1000.0, curve);
        return Optional.of(new JobSummary(
            "bt-curve", "BTC/USDT", "binance", JobStatus.COMPLETED, "2024-01-01T00:00:00Z", r));
      }
    };
    return McpTools.build(stub, "https://api.qtsurfer.net/v1");
  }

  private static String callText(List<SyncToolSpecification> t, String name, Map<String, Object> args) {
    CallToolResult r = t.stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow()
        .callHandler().apply(null, new CallToolRequest(name, args));
    return r.content().stream()
        .map(c -> c instanceof TextContent tc ? tc.text() : "").reduce("", String::concat);
  }

  @Test
  void getEquityCurveReturnsDownsampledCompactJson() {
    String json = callText(toolsWithCurve(), "get_equity_curve",
        Map.of("jobId", "bt-curve", "maxPoints", 50));
    assertThat(json)
        .contains("\"unit\":\"epoch_ms\"")
        .contains("\"totalPoints\":1000")
        .contains("\"downsampled\":true")
        .contains("\"t\":[")
        .contains("\"equity\":[");
  }

  @Test
  void getEquityCurveReturnsNotFoundForUnknownJob() {
    assertThat(callText(toolsWithCurve(), "get_equity_curve", Map.of("jobId", "bt-missing")))
        .contains("was not submitted in this session");
  }

  @Test
  void getJobStatusOmitsCurveByDefaultAndIncludesItWhenRequested() {
    var tools = toolsWithCurve();
    assertThat(callText(tools, "get_job_status", Map.of("jobId", "bt-curve")))
        .doesNotContain("Equity curve");
    assertThat(callText(tools, "get_job_status",
        Map.of("jobId", "bt-curve", "includeEquityCurve", true)))
        .contains("Equity curve")
        .contains("\"totalPoints\":1000");
  }

  // ---- strategies ----------------------------------------------------------

  private String strategyIdFrom(String submitBacktestResultText) {
    int at = submitBacktestResultText.indexOf("st-");
    return submitBacktestResultText.substring(at, at + 11);
  }

  @Test
  void listStrategiesEmptyInitially() {
    assertThat(textOf(call("list_strategies", Map.of()))).contains("No registered strategies");
  }

  @Test
  void listStrategiesReturnsAStrategyAfterSubmit() {
    call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    assertThat(textOf(call("list_strategies", Map.of()))).contains("st-").contains("compiled");
  }

  @Test
  void getStrategyCodeReturnsTheSourceLastRegistered() {
    call("submit_backtest", Map.of(
        "strategyCode", "// unique-marker-42", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    String strategyId = strategyIdFrom(textOf(call("list_strategies", Map.of())));
    assertThat(textOf(call("get_strategy_code", Map.of("strategyId", strategyId))))
        .isEqualTo("// unique-marker-42");
  }

  @Test
  void getStrategyCodeReportsAnUnknownId() {
    var result = call("get_strategy_code", Map.of("strategyId", "st-unknown"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("No such strategy");
  }

  @Test
  void deleteStrategyRemovesItFromTheList() {
    call("submit_backtest", Map.of(
        "strategyCode", "// c", "exchangeId", "binance",
        "instrument", "BTC/USDT", "from", "2024-01-01", "to", "2024-01-31"));
    String strategyId = strategyIdFrom(textOf(call("list_strategies", Map.of())));

    assertThat(textOf(call("delete_strategy", Map.of("strategyId", strategyId))))
        .contains("deleted");
    assertThat(textOf(call("list_strategies", Map.of()))).contains("No registered strategies");
  }

  @Test
  void deleteStrategyReportsAnUnknownId() {
    var result = call("delete_strategy", Map.of("strategyId", "st-unknown"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("No such strategy");
  }

  @Test
  void deleteStrategyRequiresStrategyId() {
    var result = call("delete_strategy", Map.of());
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("strategyId");
  }

  // ---- sweeps -------------------------------------------------------------

  /** A grid of 40 vectors — more than the stub carries, and far more than the tool shows. */
  private static final Map<String, Object> WIDE_GRID =
      Map.of("rsiPeriod", Map.of("from", 1, "to", 40, "step", 1));

  private String submitSweep(Map<String, Object> params, Object walkForward) {
    Map<String, Object> args = new java.util.LinkedHashMap<>(Map.of(
        "strategyCode", "// code",
        "exchangeId", "binance",
        "instrument", "BTC/USDT",
        "from", "2024-01-01",
        "to", "2024-03-31",
        "params", params));
    if (walkForward != null) args.put("walkForward", walkForward);
    String text = textOf(call("submit_sweep", args));
    assertThat(text).as(text).contains("Sweep ID: sw-");
    int at = text.indexOf("sw-");
    return text.substring(at, at + 11);
  }

  @Test
  void submitSweepReportsGridSizeAndSeed() {
    String text = textOf(call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31", "params", WIDE_GRID)));
    assertThat(text).contains("Runs: 40").contains("Seed:").contains("get_sweep_status");
  }

  @Test
  void submitSweepRejectsAMissingGrid() {
    var result = call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("params");
  }

  @Test
  void submitSweepRejectsAnAxisThatIsNeitherRangeNorValues() {
    var result = call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31",
        "params", Map.of("rsiPeriod", Map.of("min", 1))));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("rsiPeriod");
  }

  @Test
  void submitSweepAcceptsBooleanAxesAndSamplers() {
    String text = textOf(call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31",
        "params", Map.of(
            "useTrendFilter", Map.of("values", List.of(true, false)),
            "rsiPeriod", List.of(7, 14, 21)),
        "sampler", "lhs", "samples", 4, "seed", 99, "objective", "sortino")));
    assertThat(text).contains("Runs: 6").contains("Seed: 99");
  }

  @Test
  void submitSweepRejectsAnUnknownObjective() {
    var result = call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31", "params", WIDE_GRID,
        "objective", "kelly"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("sharpe, sortino, pnl, maxdd");
  }

  @Test
  void submitSweepAnnouncesTheWalkForwardShape() {
    String text = textOf(call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31", "params", WIDE_GRID,
        "walkForward", Map.of("folds", 4, "inSamplePct", 60))));
    assertThat(text).contains("Walk-forward: 4 folds").contains("60% in-sample")
        .contains("one row per fold");
  }

  @Test
  void submitSweepRejectsFewerThanTwoFolds() {
    var result = call("submit_sweep", Map.of(
        "strategyCode", "// code", "exchangeId", "binance", "instrument", "BTC/USDT",
        "from", "2024-01-01", "to", "2024-03-31", "params", WIDE_GRID,
        "walkForward", Map.of("folds", 1)));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("folds");
  }

  @Test
  void getSweepStatusCapsTheLeaderboardAndSaysSoWithThreeNumbers() {
    String sweepId = submitSweep(WIDE_GRID, null);
    String text = textOf(call("get_sweep_status", Map.of("sweepId", sweepId)));
    assertThat(text)
        .contains("showing 10 of 25 rows carried")
        .contains("40 row(s) available")
        .contains("truncated")
        .contains("15 carried row(s) not shown");
    assertThat(text.lines().filter(l -> l.startsWith("#")).count()).isEqualTo(10);
  }

  @Test
  void getSweepStatusHonoursTopNUpToTheHardCap() {
    String sweepId = submitSweep(WIDE_GRID, null);
    assertThat(textOf(call("get_sweep_status", Map.of("sweepId", sweepId, "topN", 3)))
        .lines().filter(l -> l.startsWith("#")).count()).isEqualTo(3);
    // 500 is clamped to MAX_TOP_N, which is still more than the 25 rows carried.
    assertThat(textOf(call("get_sweep_status", Map.of("sweepId", sweepId, "topN", 500)))
        .lines().filter(l -> l.startsWith("#")).count()).isEqualTo(25);
  }

  @Test
  void getSweepStatusFlagsAnUnevidencedPlateauScore() {
    String sweepId = submitSweep(WIDE_GRID, null);
    String text = textOf(call("get_sweep_status", Map.of("sweepId", sweepId)));
    assertThat(text).contains("neighbours=0, unevidenced");
  }

  @Test
  void getSweepStatusReportsTheWalkForwardShape() {
    String sweepId = submitSweep(WIDE_GRID, Map.of("folds", 3));
    String text = textOf(call("get_sweep_status", Map.of("sweepId", sweepId)));
    assertThat(text)
        .contains("Walk-forward: 3 folds")
        .contains("runIx is the fold index")
        .contains("Param drift: 0.250")
        .doesNotContain("plateau=");
  }

  @Test
  void getSweepStatusExplainsThatSweepsAreSessionScoped() {
    String text = textOf(call("get_sweep_status", Map.of("sweepId", "sw-unknown")));
    assertThat(text).contains("was not submitted in this session")
        .contains("cannot be polled, cancelled or analysed here");
  }

  @Test
  void cancelSweepStopsARunningSweepOnceAndThenReportsNothingToStop() {
    String sweepId = submitSweep(WIDE_GRID, null);
    assertThat(textOf(call("cancel_sweep", Map.of("sweepId", sweepId))))
        .contains("Cancellation requested");
    assertThat(textOf(call("cancel_sweep", Map.of("sweepId", sweepId))))
        .contains("was not running");
  }

  @Test
  void cancelSweepReportsAnUnknownSweep() {
    assertThat(textOf(call("cancel_sweep", Map.of("sweepId", "sw-unknown"))))
        .contains("was not submitted in this session");
  }

  @Test
  void sweepSensitivityReturnsMarginalsAndNoHeatmapByDefault() {
    String sweepId = submitSweep(
        Map.of("rsiPeriod", List.of(7, 14), "emaPeriod", List.of(20, 50)), null);
    String text = textOf(call("get_sweep_sensitivity", Map.of("sweepId", sweepId)));
    assertThat(text).contains("Marginals").contains("rsiPeriod:").contains("emaPeriod:")
        .contains("best").contains("mean").contains("worst")
        .doesNotContain("Interaction");
  }

  @Test
  void sweepSensitivityReturnsOnePairWhenAsked() {
    String sweepId = submitSweep(
        Map.of("rsiPeriod", List.of(7, 14), "emaPeriod", List.of(20, 50)), null);
    String text = textOf(call("get_sweep_sensitivity",
        Map.of("sweepId", sweepId, "paramA", "rsiPeriod", "paramB", "emaPeriod")));
    assertThat(text).contains("Interaction").doesNotContain("Marginals");
  }

  @Test
  void sweepSensitivityRejectsHalfAPair() {
    var result = call("get_sweep_sensitivity",
        Map.of("sweepId", "sw-anything", "paramA", "rsiPeriod"));
    assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("must be given together");
  }

  @Test
  void sweepSensitivitySurfacesHeatmapsTruncated() {
    // Four axes make six pairs; the stub caps them, exactly as the platform does.
    String sweepId = submitSweep(Map.of(
        "a", List.of(1, 2), "b", List.of(1, 2),
        "c", List.of(1, 2), "d", List.of(1, 2)), null);
    assertThat(textOf(call("get_sweep_sensitivity", Map.of("sweepId", sweepId))))
        .contains("heatmapsTruncated is set");
  }

  @Test
  void sweepSensitivityBlamesTheCapForAMissingPairWhenTruncated() {
    // The stub keeps the first three pairs in axis-name order, so c × d is one it drops.
    String sweepId = submitSweep(Map.of(
        "a", List.of(1, 2), "b", List.of(1, 2),
        "c", List.of(1, 2), "d", List.of(1, 2)), null);
    String text = textOf(call("get_sweep_sensitivity",
        Map.of("sweepId", sweepId, "paramA", "c", "paramB", "d")));
    assertThat(text).contains("No interaction surface")
        .contains("probably dropped by the cap")
        .contains("Axes swept:");
  }

  @Test
  void sweepSensitivityExplainsAMissingPairWhenNothingWasTruncated() {
    String sweepId = submitSweep(
        Map.of("rsiPeriod", List.of(7, 14), "emaPeriod", List.of(20, 50)), null);
    String text = textOf(call("get_sweep_sensitivity",
        Map.of("sweepId", sweepId, "paramA", "rsiPeriod", "paramB", "nosuchparam")));
    assertThat(text).contains("No interaction surface")
        .contains("not among them")
        .contains("Axes swept: emaPeriod, rsiPeriod");
  }
}
