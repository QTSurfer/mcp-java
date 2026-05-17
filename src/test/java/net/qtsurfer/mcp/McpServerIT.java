package net.qtsurfer.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.qtsurfer.mcp.service.BacktestingServiceStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies the McpServerRunner wires the expected tools into
 * McpSyncServer. Uses null I/O streams so no real stdio is consumed.
 */
class McpServerIT {

  McpServerRunner runner;

  @BeforeEach
  void setUp() {
    runner = new McpServerRunner(
        new java.io.ByteArrayInputStream(new byte[0]),
        java.io.OutputStream.nullOutputStream(),
        new BacktestingServiceStub());
  }

  @AfterEach
  void tearDown() { runner.close(); }

  @Test
  void serverInfoIsCorrect() {
    var info = runner.getServer().getServerInfo();
    assertThat(info.name()).isEqualTo(McpServerRunner.SERVER_NAME);
    assertThat(info.version()).isEqualTo(McpServerRunner.SERVER_VERSION);
  }

  @Test
  void registersExactlyFiveTools() {
    assertThat(runner.getServer().listTools()).hasSize(5);
  }

  @Test
  void allExpectedToolsPresent() {
    List<String> names = runner.getServer().listTools().stream().map(Tool::name).toList();
    assertThat(names).containsExactlyInAnyOrder(
        "list_exchanges", "list_instruments", "submit_backtest", "get_job_status", "list_jobs");
  }

  @Test
  void toolsCapabilityEnabled() {
    assertThat(runner.getServer().getServerCapabilities().tools()).isNotNull();
  }

  @Test
  void allToolsHaveDescriptions() {
    runner.getServer().listTools().forEach(t ->
        assertThat(t.description()).as("tool %s", t.name()).isNotBlank());
  }
}
