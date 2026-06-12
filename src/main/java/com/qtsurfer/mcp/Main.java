package com.qtsurfer.mcp;

import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.api.sdk.errors.QTSAuthError;
import com.qtsurfer.mcp.service.BacktestingServiceStub;
import com.qtsurfer.mcp.service.SdkBacktestingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Entry point for the QTSurfer MCP server.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar qtsurfer-mcp-java.jar [options]
 *
 *   Options:
 *     --url    &lt;base-url&gt;  API base URL  (default: https://api.qtsurfer.com/v1
 *                           or QTS_URL env var)
 *     --apikey &lt;key&gt;       Long-lived API key (default: QTSURFER_APIKEY env var)
 *     --stub                Use the in-memory stub instead of the real SDK
 *                           (useful for local testing without a QTSurfer account)
 * </pre>
 *
 * <h2>Authentication</h2>
 * The server reads a long-lived API key from {@code QTSURFER_APIKEY} (or the
 * {@code --apikey} flag) and exchanges it for a short-lived JWT via
 * {@link QTSurfer#auth(String)}. The SDK transparently refreshes the JWT on
 * expiry for the lifetime of the process, so the MCP server can run for days
 * without manual rotation.
 *
 * <h2>Claude Code / Claude Desktop</h2>
 * <pre>
 * {
 *   "mcpServers": {
 *     "qtsurfer": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/qtsurfer-mcp-java.jar"],
 *       "env": { "QTSURFER_APIKEY": "&lt;your-api-key&gt;" }
 *     }
 *   }
 * }
 * </pre>
 */
public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private static final String DEFAULT_URL = "https://api.qtsurfer.com/v1";

  /** Counts how many times an authenticated session is minted — used by tests. */
  static final java.util.concurrent.atomic.AtomicInteger authCount =
      new java.util.concurrent.atomic.AtomicInteger();

  public static void main(String[] args) {
    int exit = run(args);
    if (exit != 0) System.exit(exit);
  }

  /**
   * Testable entry point. Returns the process exit code instead of calling
   * {@link System#exit(int)} so tests can assert on the failure-path without
   * killing the JVM.
   */
  static int run(String[] args) {
    injectBundledIntermediates();
    if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
      printUsage();
      return 0;
    }

    String url    = getArg(args, "--url",    env("QTS_URL", DEFAULT_URL));
    String apikey = getArg(args, "--apikey", env("QTSURFER_APIKEY", null));
    boolean stub  = hasFlag(args, "--stub");

    McpServerRunner runner;
    if (stub) {
      log.info("Starting with in-memory stub (no backend connection)");
      runner = new McpServerRunner(new BacktestingServiceStub(), "stub");
    } else {
      if (apikey == null || apikey.isBlank()) {
        System.err.println(
            "error: QTSURFER_APIKEY env var (or --apikey flag) is required.");
        System.err.println(
            "       Issue an API key via the QTSurfer web app, then set it");
        System.err.println(
            "       in your MCP client config as { \"QTSURFER_APIKEY\": \"...\" }.");
        return 2;
      }
      log.info("Connecting to QTSurfer API at {}", url);
      AuthenticatedClient qts;
      try {
        qts = authenticate(apikey, url);
      } catch (QTSAuthError e) {
        System.err.println("error: authentication failed: " + e.getMessage());
        System.err.println(
            "       Verify QTSURFER_APIKEY is a valid, unrevoked API key for " + url);
        return 3;
      }
      runner = new McpServerRunner(new SdkBacktestingService(qts, url), url);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(runner::close, "mcp-shutdown"));
    runner.run();
    return 0;
  }

  /**
   * Single point that mints an authenticated session — kept as a static method
   * so the auth-count assertion in unit tests has one well-defined call site.
   * The SDK handles refresh-on-401 internally for the lifetime of the returned
   * {@link AuthenticatedClient}.
   */
  static AuthenticatedClient authenticate(String apikey, String baseUrl) {
    authCount.incrementAndGet();
    AuthOptions opts = AuthOptions.builder().baseUrl(baseUrl).build();
    return QTSurfer.auth(apikey, opts);
  }

  /**
   * GraalVM native images cannot AIA-chase at runtime. When the server omits an
   * intermediate CA from its TLS chain, the handshake fails with HTTP 0.
   * This method loads bundled intermediate certs and adds them as trust anchors
   * in a composite SSLContext set as the JVM default, so HttpClient picks them up.
   */
  private static void injectBundledIntermediates() {
    try {
      KeyStore extras = KeyStore.getInstance(KeyStore.getDefaultType());
      extras.load(null, null);
      int added = 0;
      for (String name : new String[]{"google-we1"}) {
        try (InputStream in = Main.class.getResourceAsStream("/tls/" + name + ".pem")) {
          if (in == null) continue;
          X509Certificate cert = (X509Certificate)
              CertificateFactory.getInstance("X.509").generateCertificate(in);
          extras.setCertificateEntry(name, cert);
          added++;
        }
      }
      if (added == 0) return;

      TrustManagerFactory platformTmf = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      platformTmf.init((KeyStore) null);
      X509TrustManager platformTm = (X509TrustManager) platformTmf.getTrustManagers()[0];

      TrustManagerFactory extrasTmf = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      extrasTmf.init(extras);
      X509TrustManager extrasTm = (X509TrustManager) extrasTmf.getTrustManagers()[0];

      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[]{new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
          platformTm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
          try {
            platformTm.checkServerTrusted(chain, authType);
          } catch (java.security.cert.CertificateException e) {
            extrasTm.checkServerTrusted(chain, authType);
          }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return platformTm.getAcceptedIssuers();
        }
      }}, null);
      SSLContext.setDefault(ctx);
      log.debug("Injected {} bundled intermediate CA(s) into SSLContext", added);
    } catch (Exception e) {
      log.warn("Failed to inject bundled TLS intermediates: {}", e.getMessage());
    }
  }

  private static void printUsage() {
    System.out.print(
        "\n"
        + "    ██████    ███████████  █████████                          ██████                    \n"
        + "  ███░░░░███ ░█░░░███░░░█ ███░░░░░███                        ███░░███                   \n"
        + " ███    ░░███░   ░███  ░ ░███    ░░░  █████ ████ ████████   ░███ ░░░   ██████  ████████ \n"
        + "░███     ░███    ░███    ░░█████████ ░░███ ░███ ░░███░░███ ███████    ███░░███░░███░░███\n"
        + "░███   ██░███    ░███     ░░░░░░░░███ ░███ ░███  ░███ ░░░ ░░░███░    ░███████  ░███ ░░░ \n"
        + "░░███ ░░████     ░███     ███    ░███ ░███ ░███  ░███       ░███     ░███░░░   ░███     \n"
        + " ░░░██████░██    █████   ░░█████████  ░░████████ █████      █████    ░░██████  █████    \n"
        + "   ░░░░░░ ░░    ░░░░░     ░░░░░░░░░    ░░░░░░░░ ░░░░░      ░░░░░      ░░░░░░  ░░░░░     \n"
        + "\n");
    String commit = BuildInfo.GIT_COMMIT.equals("dev") ? "" : "-" + BuildInfo.GIT_COMMIT;
    String buildTime = BuildInfo.BUILD_TIME.isEmpty() ? "" : "  build: " + BuildInfo.BUILD_TIME;
    System.out.println(McpServerRunner.SERVER_NAME + " " + McpServerRunner.SERVER_VERSION
        + commit + buildTime);
    System.out.println();
    System.out.println("Usage:");
    System.out.println("  java -jar qtsurfer-mcp-java.jar [options]   (fat JAR)");
    System.out.println("  ./qtsurfer-mcp [options]                     (native binary)");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --url    <base-url>  API base URL (default: " + DEFAULT_URL + ")");
    System.out.println("                       Override with QTS_URL env var");
    System.out.println("  --apikey <key>       Long-lived API key");
    System.out.println("                       (default: QTSURFER_APIKEY env var)");
    System.out.println("  --stub               Use in-memory stub (no backend required)");
    System.out.println("  --help               Print this message and exit");
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
