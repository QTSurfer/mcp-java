package com.qtsurfer.mcp;

import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.mcp.service.BacktestingServiceStub;
import com.qtsurfer.mcp.service.SdkBacktestingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the QTSurfer MCP server.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar qtsurfer-mcp-java.jar [options]
 *
 *   Options:
 *     --url   &lt;base-url&gt;   API base URL  (default: https://api.qtsurfer.com/v1
 *                           or QTS_URL env var)
 *     --token &lt;jwt&gt;        Bearer token  (default: QTS_TOKEN env var)
 *     --stub               Use the in-memory stub instead of the real SDK
 *                           (useful for local testing without a QTSurfer account)
 * </pre>
 *
 * <h2>Claude Code / Claude Desktop</h2>
 * <pre>
 * {
 *   "mcpServers": {
 *     "qtsurfer": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/qtsurfer-mcp-java.jar",
 *                "--token", "&lt;your-token&gt;"]
 *     }
 *   }
 * }
 * </pre>
 */
public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private static final String DEFAULT_URL = "https://api.qtsurfer.com/v1";

  public static void main(String[] args) {
    if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
      printUsage();
      return;
    }

    String url   = getArg(args, "--url",   env("QTS_URL", DEFAULT_URL));
    String token = getArg(args, "--token", env("QTS_TOKEN", null));
    boolean stub = hasFlag(args, "--stub");

    McpServerRunner runner;
    if (stub) {
      log.info("Starting with in-memory stub (no backend connection)");
      runner = new McpServerRunner(new BacktestingServiceStub());
    } else {
      log.info("Connecting to QTSurfer API at {}", url);
      QTSurfer qts = QTSurfer.builder()
          .baseUrl(url)
          .token(token)
          .build();
      runner = new McpServerRunner(new SdkBacktestingService(qts, url));
    }

    Runtime.getRuntime().addShutdownHook(new Thread(runner::close, "mcp-shutdown"));
    runner.run();
  }

  private static void printUsage() {
    System.out.println("qtsurfer-mcp-java " + McpServerRunner.SERVER_VERSION);
    System.out.println();
    System.out.println("Usage: java -jar qtsurfer-mcp-java.jar [options]");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --url   <base-url>  API base URL (default: " + DEFAULT_URL + ")");
    System.out.println("                      Override with QTS_URL env var");
    System.out.println("  --token <jwt>       Bearer token (default: QTS_TOKEN env var)");
    System.out.println("  --stub              Use in-memory stub (no backend required)");
    System.out.println("  --help              Print this message and exit");
    System.out.println();
    System.out.println("MCP transport: stdio (stdin/stdout JSON-RPC 2.0)");
  }

  private static String getArg(String[] args, String flag, String defaultValue) {
    for (int i = 0; i < args.length - 1; i++) {
      if (flag.equals(args[i])) return args[i + 1];
    }
    return defaultValue;
  }

  private static boolean hasFlag(String[] args, String flag) {
    for (String arg : args) {
      if (flag.equals(arg)) return true;
    }
    return false;
  }

  private static String env(String name, String defaultValue) {
    String v = System.getenv(name);
    return (v != null && !v.isBlank()) ? v : defaultValue;
  }
}
