# Changelog

All notable changes to `com.qtsurfer:mcp-java` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.3] — 2026-06-13

### Fixed 🐛

- **Native binary was non-functional beyond `--help`** — root cause of the `auth() failed: HTTP 0` errors finally identified: it was never TLS. GraalVM native images need reflection metadata for every Jackson-(de)serialized type; without it, deserializing the auth response throws `InvalidDefinitionException` (an `IOException` subclass), which the OpenAPI client wraps as `ApiException` with code 0. The MCP layer was equally broken: `McpSchema` record components were not registered, so even a stdio `initialize` crashed with `UnsupportedFeatureError`.
  - Added `reflect-config.json` generated with the GraalVM tracing agent across full MCP sessions (stub + live API), plus wholesale registration of all `McpSchema$*`, `api.client.model.*`, and `mcp.model.*` types.
  - Replaced `reachability-metadata.json` (the unified format, ignored by GraalVM for JDK 21) with classic-format `reflect-config.json`/`resource-config.json`, which all GraalVM versions process.
  - `logback.xml` is now included in the image: native binary logs went to **stdout** with the default pattern, corrupting the MCP stdio protocol; they now go to stderr as configured.
- Verified end-to-end on macOS arm64: authentication, `tools/list`, and live tool calls against the production API.

## [0.3.2] — 2026-06-13

### Fixed 🐛

- **Native binary TLS on macOS/Linux: `auth() failed: HTTP 0`** — `api.qtsurfer.net` omits the WE1 intermediate CA from its TLS handshake. GraalVM native images cannot AIA-chase at runtime, causing the TLS handshake to fail silently. The WE1 certificate (Google Trust Services, valid until 2029) is now bundled as a classpath resource and injected as a trust anchor at startup via a composite `X509TrustManager` set as the JVM default `SSLContext`, before the first outbound connection. The fat JAR is unaffected (JVM AIA-chases automatically).

## [0.3.1] — 2026-06-12

### Added ✨

- **`version` MCP tool** — returns the server version and API endpoint in use. Useful for diagnosing which build is running inside a client session.
- **Installer scripts** — one-liner install for all platforms:
  - `install.sh` (Linux · macOS): detects OS/arch, downloads the native binary or fat JAR, removes macOS quarantine automatically. Falls back to fat JAR on Intel Mac and Linux ARM64; offers to install Java 21 via SDKMAN if missing.
  - `install.ps1` (Windows): downloads `qtsurfer-mcp-windows-amd64.exe`, adds to user PATH; offers to install Java 21 via `winget` on unsupported architectures.
- **Versionless fat JAR asset** — each release now includes `qtsurfer-mcp-java.jar` alongside the versioned `qtsurfer-mcp-java-x.y.z.jar`, so the installation URL never needs updating.
- **`--help` banner** — QTSurfer ASCII banner + version, git commit hash, and build timestamp printed on `--help`.

### Fixed 🐛

- **Native binary TLS trust on macOS** — GraalVM native images embed CA certificates at compile time and cannot chase AIA URLs at runtime. The CI build now syncs OS root CAs into the GraalVM JDK and explicitly downloads any intermediate CAs omitted by the server's TLS handshake (via AIA CA Issuers), so the baked-in trust store is complete. Fixes `auth() failed: HTTP 0` errors on macOS Apple Silicon against hosts using Google Trust Services intermediates.

### Changed 🔄

- **Version sourced from `build.properties`** — the server version is no longer a hardcoded string in `McpServerRunner`. It is read from a filtered `build.properties` resource populated by Maven (`${project.version}`) and `git-commit-id-maven-plugin` (`git.commit.id.abbrev`, `git.build.time`). Falls back to `"dev"` when running from IDE sources without a Maven build.

## [0.3.0] — 2026-05-26

### Changed (BREAKING)

- **Auth model swapped from JWT-in-env to apikey-via-SDK.** The MCP server now
  reads a long-lived API key from `QTSURFER_APIKEY` (or the new `--apikey`
  flag) and uses [`com.qtsurfer:sdk-java`](https://github.com/QTSurfer/sdk-java)
  0.5.0 `QTSurfer.auth(apikey)` to mint and transparently refresh a short-lived
  JWT for the lifetime of the process. MCP servers can now run for days under a
  desktop client without manual token rotation.
- **Removed**: the `QTS_TOKEN` env var and the `--token` CLI flag. There is no
  backwards-compatibility shim — adopters must update their MCP client config
  to pass `QTSURFER_APIKEY` instead.
- **Fail-fast startup**: if `QTSURFER_APIKEY` is missing or the initial JWT
  exchange returns 401, the server logs a clear error to stderr and exits
  non-zero before exposing any tools. Better than silently exposing tools that
  all 401 on first call.
- **Maven coordinates**: `<artifactId>` renamed from `mcp` to `mcp-java` to
  match the GitHub repository name. This affects the on-disk JAR name
  (`mcp-java-0.3.0.jar` instead of `mcp-0.2.1.jar`); distribution to end users
  is unchanged — the GitHub Release asset is still
  `qtsurfer-mcp-java-0.3.0.jar`.

### Upgrade guide

Replace `QTS_TOKEN` with `QTSURFER_APIKEY` in every MCP client config snippet:

```diff
 {
   "mcpServers": {
     "qtsurfer": {
       "command": "/path/to/qtsurfer-mcp",
-      "args": ["--url", "https://api.qtsurfer.com/v1"],
-      "env": { "QTS_TOKEN": "<your-jwt>" }
+      "env": { "QTSURFER_APIKEY": "<your-api-key>" }
     }
   }
 }
```

Issue a new long-lived API key via the QTSurfer web app — the server handles
JWT minting and refresh for you.

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
