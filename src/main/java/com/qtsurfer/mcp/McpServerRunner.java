package com.qtsurfer.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import com.qtsurfer.mcp.service.BacktestingService;
import com.qtsurfer.mcp.tool.McpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Configures and runs the QTSurfer MCP server.
 *
 * <p>The stdio transport reads from the provided streams — inject {@code System.in} /
 * {@code System.out} for production, or piped streams for integration tests.
 */
public final class McpServerRunner {

  static final String SERVER_NAME = "qtsurfer-mcp";
  static final String SERVER_VERSION = "0.2.1";

  private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

  private final McpSyncServer server;

  public McpServerRunner(BacktestingService backtestingService) {
    this(System.in, System.out, backtestingService);
  }

  /** Testable constructor — inject streams to avoid touching real stdin/stdout. */
  McpServerRunner(InputStream in, OutputStream out, BacktestingService backtestingService) {
    McpJsonMapper mapper = McpJsonDefaults.getMapper();
    McpServerTransportProvider transport = new StdioServerTransportProvider(mapper, in, out);
    server = McpServer.sync(transport)
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .instructions(
            "QTSurfer backtesting tools. "
                + "Start with list_instruments to see available exchanges and markets, "
                + "then call submit_backtest with your strategy code. "
                + "Poll results with get_job_status.")
        .capabilities(ServerCapabilities.builder().tools(false).build())
        .tools(McpTools.build(backtestingService))
        .build();
    log.info("McpServerRunner ready: {} {}", SERVER_NAME, SERVER_VERSION);
  }

  /** Block until the transport closes (client disconnects or stdin EOF). */
  public void run() {
    log.info("QTSurfer MCP server {} listening on stdio", SERVER_VERSION);
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("QTSurfer MCP server stopped");
  }

  public void close() {
    server.closeGracefully();
  }

  McpSyncServer getServer() {
    return server;
  }
}
