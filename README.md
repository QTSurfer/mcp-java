<h1 align="center">QTSurfer MCP · Java</h1>

<p align="center">
  <a href="https://github.com/QTSurfer/mcp-java/actions/workflows/ci.yml"><img src="https://github.com/QTSurfer/mcp-java/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/QTSurfer/mcp-java/releases/latest"><img src="https://img.shields.io/github/v/release/QTSurfer/mcp-java" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/JDK-21%2B-blue?logo=openjdk&logoColor=white" alt="JDK 21+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="https://modelcontextprotocol.io">Model Context Protocol</a> server for <a href="https://qtsurfer.com">QTSurfer</a> — exposes backtesting and market data as AI-accessible tools over stdio JSON-RPC 2.0.
</p>

---

> ### Breaking change in 0.3.0
>
> Auth swapped from short-lived JWT (`QTS_TOKEN`) to long-lived API key
> (`QTSURFER_APIKEY`). The MCP server now mints and refreshes JWTs for you
> via `sdk-java` 0.5.0, so the process can run for days under a desktop
> client without manual token rotation. See the
> [`0.3.0` changelog](CHANGELOG.md#030--2026-05-26) for the migration diff.

Run a backtesting workflow from any MCP-capable AI assistant: list exchanges, explore instruments, submit a strategy, and get full execution metrics — all without leaving the chat.

- **Stdio transport** — compatible with Claude Code, OpenAI Codex, and any MCP client.
- **Native binary** — ~17 ms startup, ~44 MB, no JVM required. Available for Linux, macOS, and Windows.
- **Fat JAR fallback** — single file, runs anywhere with JDK 21+.
- **Docker** — `docker run -i` for containerised deployments (`eclipse-temurin:21-jre-alpine`, ~230 MB).
- **Long-lived API key, refresh handled for you** — drop `QTSURFER_APIKEY` once in your MCP client config; `sdk-java` exchanges it for a JWT on startup and refreshes transparently for the lifetime of the process.
- **Backed by [`com.qtsurfer:sdk-java`](https://github.com/QTSurfer/sdk-java)** — auth, compile → prepare → execute orchestration with retry and cancellation.

## Installation

### Native binary (recommended)

Pre-built binaries are attached to every [GitHub Release](https://github.com/QTSurfer/mcp-java/releases/latest):

| Platform | Asset |
|----------|-------|
| Linux x86_64 | `qtsurfer-mcp-linux-amd64` |
| macOS arm64 (Apple Silicon) | `qtsurfer-mcp-macos-arm64` |
| Windows x86_64 | `qtsurfer-mcp-windows-amd64.exe` |

Intel Mac users: use the [fat JAR](#fat-jar) with JDK 21+.

```bash
# Linux
curl -Lo qtsurfer-mcp https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-linux-amd64
chmod +x qtsurfer-mcp

# macOS Apple Silicon
curl -Lo qtsurfer-mcp https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-macos-arm64
chmod +x qtsurfer-mcp

# Windows (PowerShell)
Invoke-WebRequest -Uri https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-windows-amd64.exe `
  -OutFile qtsurfer-mcp.exe
```

### Fat JAR

Requires **JDK 21+**. Works on any platform.

```bash
curl -LO https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-java-0.3.0.jar
java -jar qtsurfer-mcp-java-0.3.0.jar --help
```

### Docker

```bash
docker pull ghcr.io/qtsurfer/mcp-java:latest

# Run (MCP over stdio — pipe stdin/stdout)
docker run -i --rm -e QTSURFER_APIKEY=<your-api-key> ghcr.io/qtsurfer/mcp-java:latest
```

## Configuration

### Authentication

Generate a long-lived API key in the QTSurfer web app, then pass it to the
MCP server via the `QTSURFER_APIKEY` environment variable (or `--apikey`).
The server exchanges the API key for a short-lived JWT on startup and
refreshes it transparently for the lifetime of the process — no manual
rotation required.

If `QTSURFER_APIKEY` is missing or the initial exchange returns 401, the
server exits non-zero with a clear error before exposing any tools (your
MCP client UI will surface the failure).

### Claude Code (`~/.claude.json`)

**Native binary:**
```json
{
  "mcpServers": {
    "qtsurfer": {
      "type": "stdio",
      "command": "/path/to/qtsurfer-mcp",
      "env": { "QTSURFER_APIKEY": "<your-api-key>" }
    }
  }
}
```

**Fat JAR:**
```json
{
  "mcpServers": {
    "qtsurfer": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/qtsurfer-mcp-java-0.3.0.jar"],
      "env": { "QTSURFER_APIKEY": "<your-api-key>" }
    }
  }
}
```

**Docker:**
```json
{
  "mcpServers": {
    "qtsurfer": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm", "-e", "QTSURFER_APIKEY", "ghcr.io/qtsurfer/mcp-java:latest"]
    }
  }
}
```

### OpenAI Codex (`~/.codex/config.toml`)

```toml
[mcp_servers.qtsurfer]
command = "/path/to/qtsurfer-mcp"

[mcp_servers.qtsurfer.env]
QTSURFER_APIKEY = "<your-api-key>"
```

## Usage

```
Usage: qtsurfer-mcp [options]        # native binary
       java -jar qtsurfer-mcp-java.jar [options]  # fat JAR

Options:
  --url    <base-url>  API base URL (default: https://api.qtsurfer.com/v1)
                       Override with QTS_URL env var
  --apikey <key>       Long-lived API key
                       (default: QTSURFER_APIKEY env var)
  --stub               Use in-memory stub (no backend required)
  --help               Print this message and exit

MCP transport: stdio (stdin/stdout JSON-RPC 2.0)
```

## Tools

| Tool | Description |
|------|-------------|
| `list_exchanges` | List available exchanges (e.g. `binance`, `binancefutures`) |
| `list_instruments` | List instruments for an exchange with data-availability windows and market info |
| `submit_backtest` | Compile a Java strategy and submit a backtesting run; returns a job ID |
| `get_job_status` | Get status and full execution metrics for a submitted job |
| `list_jobs` | List jobs from the current session, optionally filtered by status |

### Example session

```
> list_exchanges
Available exchanges:
- binance: Binance — Binance spot exchange
- binancefutures: Binance Futures — Binance perpetual futures exchange

> list_instruments exchangeId=binance
Instruments on binance (142 total):
- BTC/USDT (last: 84250.50) data: 2026-03-17 → 2026-05-17
- ETH/USDT (last: 3120.75) data: 2026-03-17 → 2026-05-17
...

> submit_backtest exchangeId=binance instrument=BTC/USDT from=2026-05-10 to=2026-05-16 strategyCode=<...>
Backtest submitted. Job ID: abc123
Use get_job_status with jobId="abc123" to poll results.

> get_job_status jobId=abc123
Job abc123: COMPLETED
Exchange: binance | Instrument: BTC/USDT

=== Results ===
P&L:          +42.7500
Trades:       156 (win rate: 58.3%)
Sharpe:       1.245 | Sortino: 1.872
CAGR:         15.34%
Max Drawdown: 8.75%
Signals:      100000
```

## Strategy format

Strategies are written in Java and extend `AbstractTickerStrategy`. The MCP server compiles them server-side — no local Java compiler required.

```java
import com.wualabs.qtsurfer.engine.strategy.AbstractTickerStrategy;
// ... see SDK docs for the full strategy API

public class MyStrategy extends AbstractTickerStrategy {
    @Override
    protected void setupIndicators(InstrumentGroupRTIndicator indicators) {
        // define indicators and trade logic here
    }
}
```

For help writing strategies — indicators, window listeners, state management, examples, and advanced patterns — install the **[QTSurfer Strategy Skills](https://github.com/QTSurfer/strategy-skills)**:

```bash
npx skills add QTSurfer/strategy-skills
```

## Building from source

```bash
git clone https://github.com/QTSurfer/mcp-java.git
cd mcp-java

# Fat JAR
mvn package -DskipTests          # → target/mcp-java-*.jar

# Unit + integration tests
mvn verify

# Native binary (requires GraalVM 21+)
mvn -Pnative -DskipTests package native:compile-no-fork   # → target/qtsurfer-mcp

# Native via Docker (Linux x86_64, no local GraalVM needed)
docker build --platform linux/amd64 -f Dockerfile.native -t qtsurfer/mcp-native .
docker cp $(docker create qtsurfer/mcp-native):/app/qtsurfer-mcp ./qtsurfer-mcp
```

Requires JDK 21+ (GraalVM for native) and Maven 3.8+.

## License

Apache-2.0 — see [LICENSE](./LICENSE).
