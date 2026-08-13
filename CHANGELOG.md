# Changelog

All notable changes to `com.qtsurfer:mcp-java` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.10.2] — 2026-08-13

### Added ✨

- **`cancel_backtest` tool.** A backtest stuck in `EXECUTING` had no way to stop it — only sweeps
  had a cancel tool, even though the backend has always exposed a single-backtest cancel endpoint
  (`DELETE .../execute/{jobId}`) and `sdk-java`'s `Backtest.cancel()` already wraps it. Mirrors
  `cancel_sweep`: session-scoped (only a job this server submitted has the handle to cancel
  through), returns whether it stopped a run still in flight or found nothing to stop.

## [0.10.1] — 2026-08-13

### Fixed 🐛

- **Bump `sdk-java` to 0.13.1.** A session left running past the JWT's 1hr `expires_in` window
  started failing `submit_backtest` with a raw `HTTP 401` surfaced as a compile error, with no
  automatic recovery. Fixed upstream (`sdk-java` 0.13.1): every call now proactively re-mints the
  token a short margin ahead of expiry, and `compile`'s `401` — previously unrecognized because
  it talks to its endpoint directly rather than through the generated client — now retries the
  same as every other call.

## [0.10.0] — 2026-08-12

### Added ✨

- **Four sweep tools.** An agent asked to explore a strategy's parameter space had no tool for it
  and fell back to running single backtests in a loop — slow, and it got none of the ranking or
  anti-overfitting machinery the platform already computes.
  - **`submit_sweep`** — mirrors `submit_backtest`, plus the parameter grid (numeric ranges or
    explicit value lists, one axis per strategy property), an optional sampler (`grid`, `random`,
    `lhs`) with a sample count and a replay seed, an objective, and optional walk-forward
    validation. It blocks until the platform accepts the sweep, because the sweep id does not
    exist before then; the description says so, and says that an identical resubmission comes
    back with `queued=false` rather than starting a second sweep.
  - **`get_sweep_status`** — progress plus the leaderboard, capped at 10 rows (`topN`, max 50).
    The response always states three numbers — rows shown, rows the platform's response carried,
    and rows it reports as available — plus its `truncated` flag, so a capped leaderboard never
    looks like a short one.
  - **`cancel_sweep`** — stops a running sweep between parameter vectors; the rows already
    finished stay readable.
  - **`get_sweep_sensitivity`** — marginals by default, one axis at a time with the rest
    collapsed, which is linear in the axis count and answers the question an agent actually has:
    which parameter mattered. Interaction surfaces are quadratic, so they come back only for a
    named `paramA`/`paramB` pair, and `heatmapsTruncated` is surfaced whenever the platform
    capped them — including when it explains a pair that was asked for and did not come back.
- **`get_job_status` falls back to the platform.** Job state was served only from this process's
  own map, so a job submitted by the web app, another session, or this server before a restart
  was invisible even when the agent held its id. Unknown ids are now read through the SDK's
  `backtestResult`, whose four outcomes map one for one onto the existing job vocabulary —
  completed, failed, aborted → CANCELED, in progress → EXECUTING (the id addresses an execute
  job, so compilation and preparation are already behind it). The endpoint is addressed by
  exchange as well as job, so the tool gains an optional **`exchangeId`**, needed only on that
  path; the description and the not-found message both say exactly when. `get_equity_curve`
  takes the same optional argument, so a job readable through one tool is readable through both.
- **A gate on `reflect-config.json`.** It was the one artifact here no check covered: `verify`
  runs on the JVM where reflection config is inert, the native image is built only on a tag, and
  the offline stub tests never put a real payload through Jackson — so a green pipeline proved
  nothing about it. A new test derives what must be registered by walking the service seam's
  return types, transitively through generic arguments, declared fields and member types, and
  fails on anything missing. The two categories no return type can reach — request bodies, and
  wire types the SDK deserializes internally and flattens before handing anything back — are
  pinned explicitly with their reason, and three further assertions keep those pins honest: each
  must still resolve, none may have quietly become derivable, and no registered type may go
  unaccounted for.

### Fixed 🐛

- Registered the types the reflection gate found missing. Twenty-one of them are reachable from
  what a tool returns, including `CoverageWindow` and `InstrumentCoverage` — read by
  `list_instruments` since 0.5.0 — and the MCP layer's own `EquityPoint`. Untangling the sweep
  and validation graphs added twenty-five more on the pinned side. In a native binary every one
  of these would have failed to deserialize at runtime while every build stayed green.

### Changed 🔄

- Bumped `com.qtsurfer:sdk` to `0.13.0`, which is what exposes `sweep(...)` with its `Sweep`
  handle (`await`, `results`, `cancel`, `sensitivity`) and the standalone `backtestResult(...)`
  read behind the two changes above.
- `list_jobs` now says in its description that it is session-scoped and that the API has no list
  operation to fall back to, so an empty result is not a statement about the account.

## [0.9.0] — 2026-08-12

### Changed 🔄

- Bumped `com.qtsurfer:sdk` to `0.10.0` (OpenAPI spec `0.106.0` via `com.qtsurfer:api-client-java`
  `0.8.0` — sweep walk-forward validation and a new sweep sensitivity endpoint) — no MCP tool
  touches sweep, so this is a dependency bump only.

## [0.8.0] — 2026-08-06

### Changed 🔄

- Bumped `com.qtsurfer:sdk` to `0.9.0`, which compiles a strategy in a single request now that the
  API answers synchronously. Nothing in this repo changed to accommodate it: the SDK's `Backtest`
  abstraction absorbs the difference, so the compile step is simply faster and reports a bad
  source immediately.
- Registered `Notice` for reflection in the native image. Backtest results can now carry engine
  diagnostics, and without the registration the native binaries would fail to deserialize a result
  that includes them — while the JVM build would be perfectly fine, so this is only visible where
  it matters.
- Dropped the reflection entries for `GetStrategy200Response`, a type the client no longer
  generates.

### Added ✨

- `verify` now checks javadoc with doclint at `all,-missing`, so a broken `@link`, malformed HTML
  or an `@param` naming a parameter that does not exist fails the build. Nothing here was
  undocumented — the check exists to keep it that way. No javadoc jar is attached: this is an
  application, not a library.

## [0.7.1] — 2026-07-27

### Changed 🔄

- Bumped `com.qtsurfer:sdk` to `0.8.1`, which fixes the execute poll ending early when the API
  answers `202` — the status it returns when a job is known but its result is not readable yet.
  Nothing in this repo changed: the SDK's `Backtest` abstraction absorbs that distinction, so a
  backtest driven through the MCP tools could previously report an empty result for a run that had
  actually completed, and now polls until the result is readable.

### Changed 🔄

- Bumped `com.qtsurfer:sdk-java` to `0.8.0` (API spec 0.99.1, 16 operationId renames — no request/response shape, field, or endpoint changes). `sdk-java`'s own entry point is renamed as part of that: `QTSurfer.auth(...)` → `QTSurfer.authenticate(...)`. Updated the server's single auth-mint call site (`Main.authenticate(...)`, internal) accordingly. No change to any MCP tool's input or output contract.
- Registered the renamed generated model classes in the GraalVM `reflect-config.json` (`CancelExecution200Response`→`CancelBacktest200Response`, `GetStrategyStatus200Response`→`GetStrategy200Response`, `PostStrategy200Response`→`CompileStrategy200Response`, `ExecuteBacktestingRequest`→`ExecuteBacktestRequest`, `PrepareBacktestingRequest`→`PrepareRequest`, plus their nested enums) so the native binary keeps deserializing/instantiating these types after the rename.

## [0.6.0] — 2026-07-11

### Changed 🔄

- Bumped `com.qtsurfer:sdk-java` to `0.7.0` (API spec 0.98.0). The single-instrument preparation endpoint now returns `PrepareJobState` (with `coverageRatio` and a per-hour coverage breakdown), and `Partial` is gone from the job status enum. No change to any MCP tool's input or output contract.
- Registered `PrepareJobState` (and its nested coverage types) in the GraalVM `reflect-config.json` so the native binary can deserialize the new preparation-status response.

## [0.5.0] — 2026-07-10

### Changed 🔄

- Bumped `com.qtsurfer:sdk-java` to `0.6.1` (API spec 0.97.0). `InstrumentDetail` replaced its flat `dataFrom`/`dataTo` fields with per-data-type `coverage`, so the **`list_instruments`** tool now derives each instrument's `data: <from> → <to>` window from `coverage` (preferring the `tickers` window, falling back to `klines`, and omitting the suffix when unavailable). No change to the tool's input or output contract.

## [0.4.0] — 2026-06-16

### Added ✨

- **Equity curve access** — backtest results now expose the equity curve, which was previously fetched from the backend but silently discarded by the MCP layer.
  - New **`get_equity_curve`** tool returns the curve of a COMPLETED job as compact JSON (parallel arrays `t[]` = epoch-millis timestamps, `equity[]` = account equity). Curves longer than `maxPoints` (default 500, max 5000) are downsampled, always preserving the first/last points and the global min and max (worst drawdown and peak).
  - **`get_job_status`** gains an optional `includeEquityCurve` flag (default `false`) that appends the curve, downsampled to ~200 points, to the status summary.

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
