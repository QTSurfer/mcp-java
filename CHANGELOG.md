# Changelog

All notable changes to `com.qtsurfer:mcp-java` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] — 2026-05-17

### Changed

- Maven coordinates migrated to `com.qtsurfer:mcp-java` via JitPack custom domain (`git.qtsurfer.com`). Consumers should replace `com.github.QTSurfer:mcp-java` with `com.qtsurfer:mcp-java:0.2.0`.
- Java packages renamed from `net.qtsurfer.mcp` to `com.qtsurfer.mcp` throughout.
- Dependency on `com.qtsurfer:sdk-java:0.4.1`.
- Tags no longer use the `v` prefix; CI release workflow updated accordingly.

## [0.1.0] — 2026-05-17

### Added

- **MCP server over stdio** — JSON-RPC 2.0 transport compatible with Claude Code, Codex, and any MCP-capable client.
- **Five tools exposed:**
  - `list_exchanges` — list available exchanges on the QTSurfer platform.
  - `list_instruments` — list instruments for a given exchange with data-availability windows, last price, and 24 h volume.
  - `submit_backtest` — compile a Java strategy and queue a backtesting run; returns the job ID immediately.
  - `get_job_status` — poll a job and retrieve full execution metrics (P&L, trades, win rate, Sharpe, Sortino, CAGR, max drawdown) once completed.
  - `list_jobs` — list jobs submitted in the current session, with optional status filter.
- **Fat JAR distribution** — `java -jar qtsurfer-mcp-java-vX.Y.Z.jar` with all dependencies bundled; no installation required beyond JDK 21+.
- **GraalVM native binary** — ~17 ms startup, ~44 MB, no JVM required. Pre-built for Linux, macOS, and Windows via the CI release workflow. Build locally with `mvn -Pnative -DskipTests package native:compile-no-fork` or via `Dockerfile.native`.
- **Docker support** — `Dockerfile` (fat JAR, eclipse-temurin:21-jre) and `Dockerfile.native` (GraalVM CE 25 → distroless/cc, ~65 MB).
- **`--stub` mode** — in-memory stub for local testing without a backend token.
- Backed by [`com.qtsurfer:sdk-java`](https://github.com/QTSurfer/sdk-java) for all API interaction.
