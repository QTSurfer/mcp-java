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
- **Fat JAR** — single file, no installation beyond JDK 21+.
- **Backed by [`net.qtsurfer:sdk`](https://github.com/QTSurfer/sdk-java)** — compile → prepare → execute orchestration with retry and cancellation.

## Installation

Download the latest fat JAR from [Releases](https://github.com/QTSurfer/mcp-java/releases/latest):

```bash
curl -LO https://github.com/QTSurfer/mcp-java/releases/latest/download/qtsurfer-mcp-java-v0.1.0.jar
```

Requires **JDK 21+**.

## Configuration

### Claude Code (`~/.claude.json`)

```json
{
  "mcpServers": {
    "qtsurfer": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/qtsurfer-mcp-java-v0.1.0.jar", "--url", "https://api.qtsurfer.com/v1"],
      "env": {
        "QTS_TOKEN": "<your-api-token>"
      }
    }
  }
}
```

### OpenAI Codex (`~/.codex/config.toml`)

```toml
[mcp_servers.qtsurfer]
command = "java"
args = ["-jar", "/path/to/qtsurfer-mcp-java-v0.1.0.jar", "--url", "https://api.qtsurfer.com/v1"]

[mcp_servers.qtsurfer.env]
QTS_TOKEN = "<your-api-token>"
```

## Usage

```
Usage: java -jar qtsurfer-mcp-java.jar [options]

Options:
  --url   <base-url>  API base URL (default: https://api.qtsurfer.com/v1)
                      Override with QTS_URL env var
  --token <jwt>       Bearer token (default: QTS_TOKEN env var)
  --stub              Use in-memory stub (no backend required)
  --help              Print this message and exit
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

## Building from source

```bash
git clone https://github.com/QTSurfer/mcp-java.git
cd qtsurfer-mcp-java
mvn verify          # compile + unit tests + integration tests
mvn package -DskipTests  # fat JAR → target/mcp-*.jar
```

Requires JDK 21+ and Maven 3.8+.

## License

Apache-2.0 — see [LICENSE](./LICENSE).
