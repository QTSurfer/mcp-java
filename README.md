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

Run a backtesting workflow from any MCP-capable AI assistant: list exchanges, explore instruments, submit a strategy, and get full execution metrics — all without leaving the chat.

- **Stdio transport** — compatible with Claude Code, OpenAI Codex, and any MCP client.
- **Native binary** — ~17 ms startup, ~44 MB, no JVM required. Available for Linux, macOS, and Windows.
- **Fat JAR fallback** — single file, runs anywhere with JDK 21+.
- **Docker** — `docker run -i` for containerised deployments (`eclipse-temurin:21-jre-alpine`, ~230 MB).
- **Long-lived API key, refresh handled for you** — drop `QTSURFER_APIKEY` once in your MCP client config; `sdk-java` exchanges it for a JWT on startup and refreshes transparently for the lifetime of the process.
- **Backed by [`com.qtsurfer:sdk-java`](https://github.com/QTSurfer/sdk-java)** — auth, compile → prepare → execute orchestration with retry and cancellation.

## Installation

### Linux · macOS

```bash
curl -fsSL https://raw.githubusercontent.com/QTSurfer/mcp-java/main/install.sh | bash
```

The installer detects your platform and picks the right delivery:

| Platform | What gets installed |
|---|---|
| Linux x86_64 | native binary |
| macOS arm64 (Apple Silicon) | native binary (quarantine flag removed automatically) |
| macOS x86_64 (Intel) · Linux arm64 | fat JAR + wrapper script (Java 21+ required; installer offers to install via SDKMAN if missing) |

Pin a version or override the destination:

```bash
VERSION=0.10.0 INSTALL_DIR=~/.local/bin \
  curl -fsSL https://raw.githubusercontent.com/QTSurfer/mcp-java/main/install.sh | bash
```

### Windows

```powershell
irm https://raw.githubusercontent.com/QTSurfer/mcp-java/main/install.ps1 | iex
```

Installs the native `qtsurfer-mcp-windows-amd64.exe` to `%LOCALAPPDATA%\qtsurfer-mcp` and adds it to your user PATH.  
On unsupported architectures it falls back to the fat JAR and offers to install Java 21 via `winget` if missing.

### Fat JAR (any platform, JDK 21+)

```bash
curl -LO https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-java.jar
java -jar qtsurfer-mcp-java.jar --help
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
      "args": ["-jar", "/path/to/qtsurfer-mcp-java.jar"],
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
  --url    <base-url>  API base URL (default: https://api.qtsurfer.net/v1)
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
| `list_instruments` | List instruments for an exchange with per-data-type coverage windows and market info |
| `submit_backtest` | Compile a Java strategy and submit a backtesting run; returns a job ID |
| `get_job_status` | Status and full execution metrics for a job — this session's, or any job on the platform given its `exchangeId` |
| `get_equity_curve` | Equity curve of a completed run as compact JSON, downsampled to a point budget |
| `list_jobs` | List jobs from the current session, optionally filtered by status |
| `submit_sweep` | Run one strategy across a parameter grid, optionally walk-forward validated; returns a sweep ID |
| `get_sweep_status` | Progress and a capped, plateau-ranked leaderboard for a sweep |
| `cancel_sweep` | Stop a running sweep between parameter vectors, keeping the rows already scored |
| `get_sweep_sensitivity` | Which parameter mattered: marginals per axis, or one named interaction surface |
| `list_strategies` | List every strategy registered under this account, most recently compiled first |
| `delete_strategy` | Release a registered strategy |
| `get_strategy_code` | Fetch the exact source last registered for a strategy id |

### Sweeps

A sweep runs the same strategy once per parameter vector and ranks the results as one job, which
is not the same thing as a loop of backtests. What the loop cannot produce:

- a **plateau ranking** rather than a raw one — a point's score is the worst run in its
  neighbourhood, so a spike that does not survive small parameter moves ranks low;
- a **deflated Sharpe** per row, discounting for how many vectors were tried;
- a **probability of backtest overfitting** for the search as a whole;
- **walk-forward validation**, where the answer is one row per fold scored out-of-sample instead
  of a ranked grid;
- **sensitivity marginals**, which say whether an axis moved the objective at all — a question
  the leaderboard cannot answer, since a sweep can spend its whole budget on an axis that did
  nothing and the top rows will not show it.

`submit_sweep` blocks until the platform accepts the sweep: it compiles the strategy and prepares
the dataset first, which takes as long as it takes on a long window. The three read/cancel tools
work on sweeps submitted in the current session.

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

### Example sweep

```
> submit_sweep exchangeId=binance instrument=BTC/USDT from=2026-01-01 to=2026-03-31
             params={"rsiPeriod":{"from":7,"to":28,"step":1},"stopLossPct":{"values":[1,2,3]}}
Sweep submitted. Sweep ID: sw-d4748fdb
Runs: 66 across 1 shard(s)
Seed: 42 (resubmit this seed to replay the same draw)
Poll with get_sweep_status using sweepId="sw-d4748fdb".

> get_sweep_status sweepId=sw-d4748fdb topN=3
Sweep sw-d4748fdb: COMPLETED
Objective: sharpe | Order: ranked | Ranking applied: plateau
Progress: 66/66 runs
PBO: 0.320 over 8 splits — probability the in-sample winner lands below median out-of-sample; ...

Leaderboard: showing 3 of 25 rows carried by the platform's response; it reports 66 row(s)
available and flags its own leaderboard as truncated. 22 carried row(s) not shown — raise topN.
#1 runIx=41  plateau=1.4000 (neighbours=4)  sharpe=1.6000  dSharpe=0.9700  pnl=150.0000  trades=90
#2 runIx=17  plateau=1.3700 (neighbours=3)  sharpe=1.5700  dSharpe=0.9500  pnl=146.0000  trades=89
#3 runIx=52  plateau=1.3400 (neighbours=4)  sharpe=1.5400  dSharpe=0.9300  pnl=142.0000  trades=88

> get_sweep_sensitivity sweepId=sw-d4748fdb
Marginals (one axis at a time, every other axis collapsed):
rsiPeriod:
  7.0 → best 1.5000  mean 0.9000  worst 0.2000  (n=4)
  ...
stopLossPct:
  1 → best 1.5000  mean 0.9000  worst 0.2000  (n=4)
  ...
```

## Strategy format

Strategies are plain Java classes compiled server-side — no local Java compiler required. Each one extends a strategy base class chosen by its data source; `AbstractTickerStrategy` (live tickers) is the most common.

```java
import com.wualabs.qtsurfer.engine.strategy.AbstractTickerStrategy;

public class MyStrategy extends AbstractTickerStrategy {
    @Override
    protected void setupIndicators(InstrumentGroupRTIndicator indicators) {
        // define indicators and trade logic here
    }
}
```

The **[QTSurfer Strategy Skills](https://github.com/QTSurfer/strategy-skills)** are the source of truth for writing strategies — the full base-class family, indicator catalogue, window listeners, state management, signal emission, worked examples, and advanced patterns. Install:

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
