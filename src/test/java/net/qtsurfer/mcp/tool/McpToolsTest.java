package net.qtsurfer.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import net.qtsurfer.mcp.service.BacktestingServiceStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    tools = McpTools.build(service);
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
  void registersExactlyFiveTools() {
    assertThat(tools).hasSize(5);
  }

  @Test
  void toolNamesAreCorrect() {
    var names = tools.stream().map(t -> t.tool().name()).toList();
    assertThat(names).containsExactlyInAnyOrder(
        "list_exchanges", "list_instruments", "submit_backtest", "get_job_status", "list_jobs");
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
  void getJobStatusReturnsNotFoundForUnknownId() {
    assertThat(textOf(call("get_job_status", Map.of("jobId", "bt-unknown"))))
        .contains("not found");
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
}
