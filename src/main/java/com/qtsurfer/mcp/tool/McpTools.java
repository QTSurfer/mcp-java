package com.qtsurfer.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import com.qtsurfer.mcp.McpServerRunner;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.SweepHeatmap;
import com.qtsurfer.api.client.model.SweepHeatmapCell;
import com.qtsurfer.api.client.model.SweepMarginal;
import com.qtsurfer.api.client.model.SweepMarginalPoint;
import com.qtsurfer.api.client.model.SweepProgress;
import com.qtsurfer.api.client.model.SweepRunRow;
import com.qtsurfer.api.client.model.StrategySummary;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.client.model.WalkForwardResult;
import com.qtsurfer.api.sdk.ParamAxis;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.SweepSampler;
import com.qtsurfer.api.sdk.WalkForwardSpec;
import com.qtsurfer.mcp.model.EquityPoint;
import com.qtsurfer.mcp.model.JobResult;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.service.BacktestingService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the MCP tools that expose QTSurfer over the Model Context Protocol.
 *
 * <p>Each {@link SyncToolSpecification} pairs a {@link Tool} (name + description + JSON Schema)
 * with a lambda handler that delegates to {@link BacktestingService}. No MCP protocol detail
 * leaks into the service layer.
 *
 * <p>The descriptions are the interface. An agent calling these tools reads nothing else, so
 * anything it has to know to read an answer correctly — that the leaderboard is plateau-ordered
 * rather than objective-ordered, that a walk-forward sweep answers in a different shape, that a
 * list is session-scoped and not a statement about the account — belongs in the description
 * rather than in a comment here.
 */
public final class McpTools {

  private McpTools() {}

  /** Default sample cap for the inline equity-curve preview in {@code get_job_status}. */
  private static final int PREVIEW_POINTS = 200;
  /** Default sample cap for the dedicated {@code get_equity_curve} tool. */
  private static final int DEFAULT_MAX_POINTS = 500;
  /** Hard upper bound on points returned by {@code get_equity_curve}. */
  private static final int MAX_POINTS_LIMIT = 5000;
  /** Default leaderboard rows returned by {@code get_sweep_status}. */
  private static final int DEFAULT_TOP_N = 10;
  /** Hard upper bound on leaderboard rows returned by {@code get_sweep_status}. */
  private static final int MAX_TOP_N = 50;

  public static List<SyncToolSpecification> build(BacktestingService service, String apiUrl) {
    return List.of(
        version(apiUrl),
        listExchanges(service),
        listInstruments(service),
        submitBacktest(service),
        getJobStatus(service),
        cancelBacktest(service),
        getEquityCurve(service),
        listJobs(service),
        submitSweep(service),
        getSweepStatus(service),
        cancelSweep(service),
        getSweepSensitivity(service),
        listStrategies(service),
        deleteStrategy(service),
        getStrategyCode(service));
  }

  // ---- version ------------------------------------------------------------

  private static SyncToolSpecification version(String apiUrl) {
    Tool tool = Tool.builder()
        .name("version")
        .description("Return the qtsurfer-mcp server version and the API endpoint in use.")
        .inputSchema(emptySchema())
        .build();
    String info = McpServerRunner.SERVER_NAME + " " + McpServerRunner.SERVER_VERSION
        + "\nAPI: " + apiUrl;
    return new SyncToolSpecification(tool, (exchange, request) -> text(info));
  }

  // ---- list_exchanges -----------------------------------------------------

  private static SyncToolSpecification listExchanges(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("list_exchanges")
        .description("List available exchanges on the QTSurfer platform. "
            + "Call this first to discover valid exchangeId values for list_instruments and submit_backtest.")
        .inputSchema(emptySchema())
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            List<Exchange> exchanges = service.listExchanges();
            if (exchanges.isEmpty()) return text("No exchanges available.");
            StringBuilder sb = new StringBuilder("Available exchanges:\n");
            exchanges.forEach(e -> {
              sb.append("- ").append(e.getId()).append(": ").append(e.getName());
              if (e.getDescription() != null && !e.getDescription().isBlank()) {
                sb.append(" — ").append(e.getDescription());
              }
              sb.append('\n');
            });
            return text(sb.toString().stripTrailing());
          } catch (Exception e) {
            return error("Failed to list exchanges: " + e.getMessage());
          }
        });
  }

  // ---- list_instruments ---------------------------------------------------

  private static SyncToolSpecification listInstruments(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("list_instruments")
        .description("List instruments available on a specific exchange, including data availability. "
            + "Call list_exchanges first to get a valid exchangeId. "
            + "Use the returned instrument ids with submit_backtest.")
        .inputSchema(schema(
            Map.of("exchangeId", prop("string",
                "Exchange identifier, e.g. 'binance' (spot) or 'binancefutures' (perps)")),
            List.of("exchangeId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            String exchangeId = required(request.arguments(), "exchangeId");
            List<InstrumentDetail> instruments = service.listInstruments(exchangeId);
            if (instruments.isEmpty()) return text("No instruments found for exchange: " + exchangeId);
            StringBuilder sb = new StringBuilder(
                "Instruments on " + exchangeId + " (" + instruments.size() + " total):\n");
            instruments.forEach(i -> {
              sb.append("- ").append(i.getId());
              if (i.getLastPrice() != null) {
                sb.append(" (last: ").append(i.getLastPrice()).append(')');
              }
              appendCoverage(sb, i);
              sb.append('\n');
            });
            return text(sb.toString().stripTrailing());
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          } catch (Exception e) {
            return error("Failed to list instruments: " + e.getMessage());
          }
        });
  }

  /**
   * Appends a {@code " data: <from> → <to>"} suffix derived from the instrument's
   * coverage, preferring the {@code tickers} window and falling back to
   * {@code klines}. Appends nothing when coverage, both windows, or either
   * endpoint of the chosen window is unavailable.
   */
  private static void appendCoverage(StringBuilder sb, InstrumentDetail instrument) {
    if (instrument.getCoverage() == null) return;
    var coverage = instrument.getCoverage();
    OffsetDateTime from = null;
    OffsetDateTime to = null;
    if (coverage.getTickers() != null) {
      from = coverage.getTickers().getFrom();
      to = coverage.getTickers().getTo();
    } else if (coverage.getKlines() != null) {
      from = coverage.getKlines().getFrom();
      to = coverage.getKlines().getTo();
    }
    if (from == null || to == null) return;
    sb.append(" data: ").append(from.toLocalDate()).append(" → ").append(to.toLocalDate());
  }

  // ---- submit_backtest ----------------------------------------------------

  private static SyncToolSpecification submitBacktest(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("submit_backtest")
        .description("Compile a Java strategy and queue a backtesting run on QTSurfer. "
            + "Returns the job ID immediately — poll with get_job_status. "
            + "Call list_instruments first to choose a valid exchangeId and instrument. "
            + "To try the same strategy at many parameter settings, use submit_sweep instead of "
            + "calling this in a loop.")
        .inputSchema(schema(
            Map.of(
                "strategyCode", prop("string",
                    "Complete Java source of the strategy class to compile and run"),
                "exchangeId",   prop("string",
                    "Exchange identifier, e.g. 'binance' (spot) or 'binancefutures' (perps)"),
                "instrument",   prop("string",
                    "CCXT instrument symbol, e.g. 'BTC/USDT' (spot) or 'BTC/USDT:USDT' (perp)"),
                "from",         prop("string", "Backtest start date, ISO-8601 (e.g. 2024-01-01)"),
                "to",           prop("string", "Backtest end date, ISO-8601 (e.g. 2024-03-31)")),
            List.of("strategyCode", "exchangeId", "instrument", "from", "to")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            Map<String, Object> args = request.arguments();
            String jobId = service.submitBacktest(
                required(args, "strategyCode"),
                required(args, "exchangeId"),
                required(args, "instrument"),
                required(args, "from"),
                required(args, "to"));
            return text("Backtest submitted. Job ID: " + jobId
                + "\nUse get_job_status with jobId=\"" + jobId + "\" to poll results.");
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          } catch (Exception e) {
            return error("Backend error: " + e.getMessage());
          }
        });
  }

  // ---- get_job_status -----------------------------------------------------

  private static SyncToolSpecification getJobStatus(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("get_job_status")
        .description("Get the current status of a backtesting job. "
            + "A job submitted by this server in this session is answered from its own state and "
            + "needs nothing but the jobId. Any other job id — from the web app, another session, "
            + "or this server before a restart — is read straight off the platform, which "
            + "addresses a backtest result by exchange as well as job, so those need exchangeId "
            + "too and cannot be answered without it. "
            + "Set includeEquityCurve=true to append the equity curve (downsampled to ~"
            + PREVIEW_POINTS + " points) as compact JSON; use get_equity_curve for finer control.")
        .inputSchema(schema(
            Map.of(
                "jobId", prop("string", "Job ID returned by submit_backtest"),
                "exchangeId", prop("string",
                    "Exchange the run was submitted against. Only needed for a job this session "
                        + "did not submit; ignored for one it did."),
                "includeEquityCurve", prop("boolean",
                    "When true, append the equity curve (downsampled) to the result. Default false.")),
            List.of("jobId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          Map<String, Object> args = request.arguments();
          String jobId = required(args, "jobId");
          String exchangeId = optional(args, "exchangeId");
          boolean includeCurve = asBool(args == null ? null : args.get("includeEquityCurve"));
          try {
            return service.getJobStatus(jobId, exchangeId)
                .map(j -> {
                  String body = formatJobStatus(j.jobId(), j.instrument(), j.exchangeId(),
                      j.status(), j.submittedAt(), j.result());
                  if (includeCurve && j.result() != null
                      && j.result().equityCurve() != null && !j.result().equityCurve().isEmpty()) {
                    body += "\n\nEquity curve:\n"
                        + equityCurveJson(j.jobId(), j.result().equityCurve(), PREVIEW_POINTS);
                  }
                  return text(body);
                })
                .orElseGet(() -> text(jobNotFound(jobId, exchangeId)));
          } catch (Exception e) {
            return error(e.getMessage());
          }
        });
  }

  /**
   * The message an agent gets for a job id that produced nothing. It has to distinguish the two
   * reasons, because only one of them is recoverable and the recovery is to supply an argument
   * the agent did not know it needed.
   */
  private static String jobNotFound(String jobId, String exchangeId) {
    if (exchangeId == null) {
      return "Job " + jobId + " was not submitted in this session, and no exchangeId was given, "
          + "so the platform could not be asked: a backtest result is addressed by exchange as "
          + "well as job id. Call again with exchangeId set to the exchange the run was submitted "
          + "against (e.g. 'binance') to look it up directly.";
    }
    return "Job " + jobId + " was not submitted in this session and the platform returned nothing "
        + "for it on exchange " + exchangeId + ".";
  }

  // ---- cancel_backtest ------------------------------------------------------

  private static SyncToolSpecification cancelBacktest(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("cancel_backtest")
        .description("Ask the platform to stop a backtest submitted in this session. Unlike "
            + "cancel_sweep, a single backtest has no partial leaderboard to preserve — cancelling "
            + "one stuck in EXECUTING simply abandons it. Only jobs submitted in this session can "
            + "be cancelled; a job id from the web app or another session has no local handle to "
            + "cancel through, whatever get_job_status can read off the platform for it.")
        .inputSchema(schema(
            Map.of("jobId", prop("string", "Job ID returned by submit_backtest")),
            List.of("jobId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          String jobId = required(request.arguments(), "jobId");
          try {
            if (service.cancelBacktest(jobId)) {
              return text("Cancellation requested for job " + jobId
                  + ". Poll get_job_status for the platform's confirmation.");
            }
            return service.getJobStatus(jobId, null).isPresent()
                ? text("Job " + jobId + " was not running, so nothing was cancelled. "
                    + "It had already finished, failed or been cancelled.")
                : text(jobNotFound(jobId, null));
          } catch (Exception e) {
            return error("Failed to cancel job " + jobId + ": " + e.getMessage());
          }
        });
  }

  // ---- get_equity_curve ---------------------------------------------------

  private static SyncToolSpecification getEquityCurve(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("get_equity_curve")
        .description("Return the equity curve of a COMPLETED backtest as compact JSON: parallel "
            + "arrays t[] (epoch-millis timestamps) and equity[] (account equity). Curves longer "
            + "than maxPoints are downsampled, always preserving the first/last points and the "
            + "global min and max (worst drawdown and peak). "
            + "As with get_job_status, a job this session did not submit needs exchangeId.")
        .inputSchema(schema(
            Map.of(
                "jobId", prop("string", "Job ID returned by submit_backtest"),
                "exchangeId", prop("string",
                    "Exchange the run was submitted against. Only needed for a job this session "
                        + "did not submit; ignored for one it did."),
                "maxPoints", prop("integer", "Max points to return (default " + DEFAULT_MAX_POINTS
                    + ", max " + MAX_POINTS_LIMIT + "). The curve is downsampled if longer.")),
            List.of("jobId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          Map<String, Object> args = request.arguments();
          String jobId = required(args, "jobId");
          String exchangeId = optional(args, "exchangeId");
          int maxPoints = clamp(asInt(args.get("maxPoints"), DEFAULT_MAX_POINTS), 1, MAX_POINTS_LIMIT);
          var summary = service.getJobStatus(jobId, exchangeId);
          if (summary.isEmpty()) {
            return text(jobNotFound(jobId, exchangeId));
          }
          JobResult result = summary.get().result();
          if (result == null) {
            return text("Job " + jobId + " has no results yet (status: "
                + summary.get().status() + ").");
          }
          List<EquityPoint> curve = result.equityCurve();
          if (curve == null || curve.isEmpty()) {
            return text("Job " + jobId + " produced no equity curve "
                + "(the strategy may have emitted no yield events).");
          }
          return text(equityCurveJson(jobId, curve, maxPoints));
        });
  }

  // ---- list_jobs ----------------------------------------------------------

  private static SyncToolSpecification listJobs(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("list_jobs")
        .description("List backtesting jobs submitted through this server in this session. "
            + "The API has no operation that lists a caller's jobs, so this cannot fall back to "
            + "the platform: an empty result means this session submitted nothing, not that the "
            + "account has no jobs. A job id obtained elsewhere is still readable — pass it to "
            + "get_job_status together with its exchangeId. "
            + "Optionally filter by status: COMPILING, PREPARING, EXECUTING, COMPLETED, FAILED, CANCELED.")
        .inputSchema(schema(
            Map.of("status", prop("string",
                "Optional status filter. One of: COMPILING, PREPARING, EXECUTING, COMPLETED, FAILED, CANCELED")),
            List.of()))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          Map<String, Object> args = request.arguments();
          JobStatus statusFilter = null;
          if (args != null) {
            Object raw = args.get("status");
            if (raw instanceof String s && !s.isBlank()) {
              try {
                statusFilter = JobStatus.valueOf(s.toUpperCase(Locale.ROOT));
              } catch (IllegalArgumentException e) {
                return error("Unknown status '" + s + "'. Valid: COMPILING, PREPARING, EXECUTING, COMPLETED, FAILED, CANCELED");
              }
            }
          }
          var jobList = service.listJobs(statusFilter);
          if (jobList.isEmpty()) {
            return text("No jobs found" + (statusFilter != null ? " with status " + statusFilter : "")
                + " in this session.");
          }
          StringBuilder sb = new StringBuilder();
          jobList.forEach(j -> sb.append(j).append('\n'));
          return text(sb.toString().stripTrailing());
        });
  }

  // ---- submit_sweep -------------------------------------------------------

  private static SyncToolSpecification submitSweep(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("submit_sweep")
        .description("Run one strategy over a grid of parameter settings on one instrument and "
            + "window, scored and ranked against a single objective. Use this rather than calling "
            + "submit_backtest in a loop: the platform schedules the whole grid as one job and "
            + "computes the ranking, the plateau scores, the deflated Sharpe per row and the "
            + "probability of backtest overfitting for the search as a whole — none of which a "
            + "loop of independent runs produces. "
            + "This call blocks until the platform accepts the sweep: it compiles the strategy and "
            + "prepares the dataset first, which can take minutes on a long window. If it does "
            + "return late, do not resubmit blindly — an identical resubmission is reported back "
            + "with queued=false and starts nothing new, but any difference starts a second sweep. "
            + "Set walkForward to validate out-of-sample instead of ranking in-sample; that changes "
            + "what runs and the shape of the answer (see get_sweep_status). "
            + "Returns the sweep id: poll it with get_sweep_status, stop it with cancel_sweep, and "
            + "ask which parameter mattered with get_sweep_sensitivity. Those three only answer for "
            + "sweeps submitted in this session.")
        .inputSchema(schema(
            mapOf(
                "strategyCode", prop("string",
                    "Complete Java source of the strategy class. Compiled once and reused by every "
                        + "trial; the swept names must be settable properties on it."),
                "exchangeId", prop("string",
                    "Exchange identifier, e.g. 'binance' (spot) or 'binancefutures' (perps)"),
                "instrument", prop("string",
                    "CCXT instrument symbol, e.g. 'BTC/USDT' (spot) or 'BTC/USDT:USDT' (perp)"),
                "from", prop("string", "Sweep window start, ISO-8601 (e.g. 2024-01-01)"),
                "to", prop("string", "Sweep window end, ISO-8601 (e.g. 2024-03-31)"),
                "params", Map.of(
                    "type", "object",
                    "description", "The grid: one axis per strategy property to vary, at least one. "
                        + "An axis is either a numeric range {\"from\":7,\"to\":28,\"step\":1} or an "
                        + "explicit list {\"values\":[10,20,50]}; values may be numbers or booleans, "
                        + "and the axis of a flag is [true,false] rather than a range. Example: "
                        + "{\"rsiPeriod\":{\"from\":7,\"to\":28,\"step\":1},"
                        + "\"useTrendFilter\":{\"values\":[true,false]}}. "
                        + "A full grid is the cross product of every axis, so adding an axis "
                        + "multiplies the cost — use sampler to draw a subset instead."),
                "objective", prop("string",
                    "Metric to optimize and rank by: sharpe (default), sortino, pnl or maxdd. It is "
                        + "also what get_sweep_sensitivity aggregates unless told otherwise."),
                "sampler", prop("string",
                    "How the grid becomes the vectors actually run: grid (full cross product, the "
                        + "default), random, or lhs (Latin hypercube — better coverage than random "
                        + "for the same budget). random and lhs need samples."),
                "samples", prop("integer",
                    "How many vectors to draw for sampler=random or sampler=lhs. Ignored by grid."),
                "seed", prop("integer",
                    "Reproducibility seed for a sampled sweep. Omit to let the platform pick one; "
                        + "it is reported back, and resubmitting it replays the same draw."),
                "walkForward", Map.of(
                    "type", "object",
                    "description", "Opt into walk-forward validation. {\"folds\":5} splits the window "
                        + "into 5 chained folds, picks the winner in-sample on each and scores it on "
                        + "the fold's unseen tail; optional \"inSamplePct\" (10..90) sets the split. "
                        + "folds must be at least 2. The answer is then one row per fold rather than "
                        + "a ranked grid.")),
            List.of("strategyCode", "exchangeId", "instrument", "from", "to", "params")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            Map<String, Object> args = request.arguments();
            SweepRequest.Builder builder = SweepRequest.builder()
                .strategy(required(args, "strategyCode"))
                .exchangeId(required(args, "exchangeId"))
                .instrument(required(args, "instrument"))
                .from(required(args, "from"))
                .to(required(args, "to"))
                .params(parseParams(args.get("params")));
            String objective = optional(args, "objective");
            if (objective != null) builder.objective(parseObjective(objective));
            String sampler = optional(args, "sampler");
            if (sampler != null) builder.sampler(parseSampler(sampler));
            Integer samples = asInteger(args.get("samples"));
            if (samples != null) builder.samples(samples);
            Integer seed = asInteger(args.get("seed"));
            if (seed != null) builder.seed(seed.longValue());
            WalkForwardSpec walkForward = parseWalkForward(args.get("walkForward"));
            if (walkForward != null) builder.walkForward(walkForward);

            ExecuteSweepAccepted accepted = service.submitSweep(builder.build());
            return text(formatAccepted(accepted));
          } catch (IllegalArgumentException | NullPointerException e) {
            return error(e.getMessage());
          } catch (Exception e) {
            return error("Backend error: " + e.getMessage());
          }
        });
  }

  private static String formatAccepted(ExecuteSweepAccepted accepted) {
    StringBuilder sb = new StringBuilder("Sweep submitted. Sweep ID: ")
        .append(accepted.getSweepId()).append('\n');
    if (accepted.getTotalRuns() != null) {
      sb.append("Runs: ").append(accepted.getTotalRuns());
      if (accepted.getShards() != null) sb.append(" across ").append(accepted.getShards()).append(" shard(s)");
      sb.append('\n');
    }
    if (accepted.getSeed() != null) {
      sb.append("Seed: ").append(accepted.getSeed())
          .append(" (resubmit this seed to replay the same draw)\n");
    }
    if (Boolean.FALSE.equals(accepted.getQueued())) {
      sb.append("Queued: no — an identical sweep already existed, so nothing new was started. "
          + "The id above reads that sweep.\n");
    }
    if (accepted.getWalkForward() != null) {
      var wf = accepted.getWalkForward();
      sb.append("Walk-forward: ").append(wf.getFolds()).append(" folds");
      if (wf.getInSamplePct() != null) sb.append(", ").append(wf.getInSamplePct()).append("% in-sample");
      sb.append(" — the leaderboard will be one row per fold, not a ranked grid.\n");
    }
    sb.append("Poll with get_sweep_status using sweepId=\"").append(accepted.getSweepId()).append("\".");
    return sb.toString();
  }

  // ---- get_sweep_status ---------------------------------------------------

  private static SyncToolSpecification getSweepStatus(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("get_sweep_status")
        .description("Progress and leaderboard of a sweep submitted in this session. "
            + "There is no lookup for a sweep id from anywhere else. "
            + "The leaderboard's default order is plateau, not raw objective: a point's plateau "
            + "score is the worst run in its neighbourhood, so a spike that does not survive small "
            + "parameter moves ranks low, which is the point. Read plateauScore together with "
            + "neighbourCount — neighbourCount 0 means the point had no neighbours in the grid to "
            + "compare against, so its plateau score is unevidenced rather than confirmed, and on "
            + "its own it is indistinguishable from a genuinely robust one. "
            + "Only the top " + DEFAULT_TOP_N + " rows come back by default (raise topN, max "
            + MAX_TOP_N + "); the response always states how many rows it is showing, how many the "
            + "platform sent, and how many it says exist, so rows are never dropped silently. "
            + "On a walk-forward sweep the answer has a different shape: one row per completed "
            + "fold, that fold's winner as it scored out-of-sample, with runIx carrying the fold "
            + "index rather than a grid position, and no plateau score, deflated Sharpe or "
            + "overfitting probability — the out-of-sample numbers are already the honest "
            + "measurement. "
            + "An empty leaderboard is not always an empty answer: when failReason is present the "
            + "sweep finished having scored nothing, usually something the whole grid would have "
            + "hit such as a strategy that could not be loaded. That is a different outcome from a "
            + "sweep that genuinely found nothing, and the leaderboard alone cannot tell them apart.")
        .inputSchema(schema(
            Map.of(
                "sweepId", prop("string", "Sweep ID returned by submit_sweep"),
                "topN", prop("integer", "Leaderboard rows to return (default " + DEFAULT_TOP_N
                    + ", max " + MAX_TOP_N + ")")),
            List.of("sweepId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          Map<String, Object> args = request.arguments();
          String sweepId = required(args, "sweepId");
          int topN = clamp(asInt(args.get("topN"), DEFAULT_TOP_N), 1, MAX_TOP_N);
          try {
            return service.getSweepStatus(sweepId)
                .map(result -> text(formatSweepStatus(sweepId, result, topN)))
                .orElseGet(() -> text(sweepNotFound(sweepId)));
          } catch (Exception e) {
            return error("Failed to read sweep " + sweepId + ": " + e.getMessage());
          }
        });
  }

  private static String sweepNotFound(String sweepId) {
    return "Sweep " + sweepId + " was not submitted in this session. Sweeps are only readable "
        + "through the handle their submission returned, so a sweep id from another session or "
        + "another client cannot be polled, cancelled or analysed here.";
  }

  private static String formatSweepStatus(String sweepId, ExecuteSweepResult r, int topN) {
    StringBuilder sb = new StringBuilder();
    sb.append("Sweep ").append(sweepId).append(": ")
        .append(r.getStatus() == null ? "unknown" : r.getStatus().getValue()).append('\n');
    sb.append("Objective: ").append(r.getObjective() == null ? "default" : r.getObjective().getValue());
    sb.append(" | Order: ").append(r.getOrder() == null ? "default" : r.getOrder().getValue());
    sb.append(" | Ranking applied: ")
        .append(r.getRanking() == null ? "default" : r.getRanking().getValue());
    sb.append('\n');

    SweepProgress p = r.getProgress();
    if (p != null) {
      sb.append("Progress: ").append(p.getDone()).append('/').append(p.getTotal()).append(" runs");
      if (p.getAborted() != null && p.getAborted() > 0) sb.append(", aborted ").append(p.getAborted());
      if (p.getFailedShards() != null && p.getFailedShards() > 0) {
        sb.append(", failed shards ").append(p.getFailedShards());
      }
      if (p.getEtaSeconds() != null) sb.append(", eta ").append(p.getEtaSeconds()).append("s");
      sb.append('\n');
    }
    if (r.getPbo() != null) {
      sb.append(String.format(Locale.ROOT, "PBO: %.3f", r.getPbo()));
      if (r.getPboSplits() != null) sb.append(" over ").append(r.getPboSplits()).append(" splits");
      sb.append(" — probability the in-sample winner lands below median out-of-sample; above ~0.5 "
          + "the search is selecting noise and the top row is discredited however good it looks.\n");
    }
    if (r.getFailReason() != null && !r.getFailReason().isBlank()) {
      sb.append("Fail reason (first shard to fail): ").append(r.getFailReason()).append('\n');
    }

    WalkForwardResult wf = r.getWalkForward();
    if (wf != null) {
      sb.append("\nWalk-forward: ").append(wf.getFolds()).append(" folds");
      if (wf.getInSamplePct() != null) sb.append(", ").append(wf.getInSamplePct()).append("% in-sample");
      if (wf.getCompletedFolds() != null) sb.append(", ").append(wf.getCompletedFolds()).append(" completed");
      sb.append('\n');
      sb.append(wf.getParamDrift() != null
          ? String.format(Locale.ROOT, "Param drift: %.3f%n", wf.getParamDrift())
          : "Param drift: not reported — it could not be computed (fewer than two folds finished, "
              + "or no stored grid to place the winners on). Absent is not zero; zero would mean "
              + "winners that never moved.\n");
      sb.append("Rows below are one per completed fold; runIx is the fold index.\n");
    }

    List<SweepRunRow> board = r.getLeaderboard() == null ? List.of() : r.getLeaderboard();
    sb.append('\n').append(leaderboardHeader(board.size(), r.getLeaderboardSize(),
        Boolean.TRUE.equals(r.getTruncated()), topN));
    if (board.isEmpty()) {
      sb.append(r.getFailReason() != null && !r.getFailReason().isBlank()
          ? "\nNo rows: the sweep finished having scored nothing — see the fail reason above."
          : "\nNo rows yet.");
      return sb.toString();
    }
    for (SweepRunRow row : board.subList(0, Math.min(topN, board.size()))) {
      sb.append('\n').append(formatRow(row));
    }
    return sb.toString();
  }

  /**
   * States the three numbers that a capped leaderboard has to keep apart: how many rows this
   * answer shows, how many the platform's response carried, and how many it says exist. Two of
   * them can differ for different reasons — this tool's cap and the platform's own — and
   * collapsing them would make either one look like the other.
   */
  private static String leaderboardHeader(int carried, Integer available, boolean truncated, int topN) {
    int shown = Math.min(topN, carried);
    StringBuilder sb = new StringBuilder("Leaderboard: showing ").append(shown)
        .append(" of ").append(carried).append(" rows carried by the platform's response");
    if (available != null) {
      sb.append("; it reports ").append(available).append(" row(s) available");
    }
    if (truncated) {
      sb.append(" and flags its own leaderboard as truncated");
    }
    sb.append('.');
    if (shown < carried) {
      sb.append(" ").append(carried - shown).append(" carried row(s) not shown — raise topN (max ")
          .append(MAX_TOP_N).append(").");
    }
    if (truncated || (available != null && available > carried)) {
      sb.append(" Rows beyond what the platform sent are not reachable from this tool.");
    }
    return sb.toString();
  }

  private static String formatRow(SweepRunRow row) {
    StringBuilder sb = new StringBuilder();
    if (row.getRank() != null) sb.append('#').append(row.getRank()).append(' ');
    sb.append("runIx=").append(row.getRunIx());
    if (row.getPlateauScore() != null) {
      sb.append(String.format(Locale.ROOT, "  plateau=%.4f", row.getPlateauScore()));
      sb.append(" (neighbours=").append(row.getNeighbourCount());
      if (row.getNeighbourCount() != null && row.getNeighbourCount() == 0) sb.append(", unevidenced");
      sb.append(')');
    }
    appendMetric(sb, "sharpe", row.getSharpe());
    appendMetric(sb, "dSharpe", row.getDeflatedSharpe());
    appendMetric(sb, "sortino", row.getSortino());
    appendMetric(sb, "pnl", row.getPnl());
    appendMetric(sb, "maxDD%", row.getMaxDdPct());
    if (row.getTrades() != null) sb.append("  trades=").append(row.getTrades());
    appendMetric(sb, "win%", row.getWinRate());
    if (Boolean.TRUE.equals(row.getAborted())) sb.append("  ABORTED");
    if (Boolean.TRUE.equals(row.getBelowTradeFloor())) sb.append("  below-trade-floor");
    if (row.getParams() != null) sb.append("  params=").append(row.getParams());
    return sb.toString();
  }

  private static void appendMetric(StringBuilder sb, String label, Double value) {
    if (value != null) sb.append(String.format(Locale.ROOT, "  %s=%.4f", label, value));
  }

  // ---- cancel_sweep -------------------------------------------------------

  private static SyncToolSpecification cancelSweep(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("cancel_sweep")
        .description("Ask the platform to stop a sweep submitted in this session, between "
            + "parameter vectors. The rows already finished stay readable, so get_sweep_status and "
            + "get_sweep_sensitivity keep working afterwards and answer from the partial "
            + "leaderboard — cancelling late is not the same as throwing the work away. A cancel "
            + "that arrives after the last vector has run changes nothing and the sweep simply "
            + "completes. Only sweeps submitted in this session can be cancelled.")
        .inputSchema(schema(
            Map.of("sweepId", prop("string", "Sweep ID returned by submit_sweep")),
            List.of("sweepId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          String sweepId = required(request.arguments(), "sweepId");
          try {
            if (service.cancelSweep(sweepId)) {
              return text("Cancellation requested for sweep " + sweepId
                  + ". It stops between parameter vectors; poll get_sweep_status for the partial "
                  + "leaderboard and the platform's confirmation.");
            }
            return service.getSweepStatus(sweepId).isPresent()
                ? text("Sweep " + sweepId + " was not running, so nothing was cancelled. "
                    + "It had already finished or been cancelled.")
                : text(sweepNotFound(sweepId));
          } catch (Exception e) {
            return error("Failed to cancel sweep " + sweepId + ": " + e.getMessage());
          }
        });
  }

  // ---- get_sweep_sensitivity ----------------------------------------------

  private static SyncToolSpecification getSweepSensitivity(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("get_sweep_sensitivity")
        .description("Which parameter actually mattered — the question a leaderboard cannot "
            + "answer. A leaderboard says which point won; a sweep can spend its whole budget on "
            + "an axis that never moved the objective at all, and the top rows hide that. "
            + "Returns marginals by default: one axis at a time with every other axis collapsed, "
            + "reporting best, mean and worst for each value. Them disagreeing is the signal — a "
            + "value with a high best and a poor mean only works in specific company, which is an "
            + "interaction, and one number would hide it. A flat marginal means the axis did not "
            + "matter over the range swept. Marginals are complete and cost one line per value. "
            + "Pass paramA and paramB together to get the interaction surface for that one pair "
            + "instead. Pair surfaces are quadratic in the axis count, which is why they are not "
            + "returned by default; the platform caps them, and when heatmapsTruncated is set at "
            + "least one pair was left out — a pair you ask for and do not get may be one of those "
            + "rather than one that was never computed. "
            + "Readable while the sweep is still running, where the aggregates describe the runs "
            + "finished so far. Aborted runs are excluded throughout: a run that threw measured "
            + "nothing, and counting it as a bad outcome would invent evidence against a parameter "
            + "value that was never really tested. Only sweeps submitted in this session.")
        .inputSchema(schema(
            Map.of(
                "sweepId", prop("string", "Sweep ID returned by submit_sweep"),
                "objective", prop("string",
                    "Metric to aggregate: sharpe, sortino, pnl or maxdd. Omit to use the objective "
                        + "the sweep was submitted with."),
                "paramA", prop("string",
                    "First axis of an interaction surface. Must be given together with paramB; "
                        + "omit both to get marginals."),
                "paramB", prop("string", "Second axis of an interaction surface.")),
            List.of("sweepId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          Map<String, Object> args = request.arguments();
          String sweepId = required(args, "sweepId");
          String paramA = optional(args, "paramA");
          String paramB = optional(args, "paramB");
          if ((paramA == null) != (paramB == null)) {
            return error("paramA and paramB must be given together — one names an axis pair, and "
                + "a pair needs both. Omit both for marginals.");
          }
          SweepObjective objective;
          try {
            String raw = optional(args, "objective");
            objective = raw == null ? null : parseObjective(raw);
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          }
          try {
            return service.getSweepSensitivity(sweepId, objective)
                .map(s -> text(formatSensitivity(sweepId, s, paramA, paramB)))
                .orElseGet(() -> text(sweepNotFound(sweepId)));
          } catch (Exception e) {
            return error("Failed to read sensitivity for sweep " + sweepId + ": " + e.getMessage());
          }
        });
  }

  private static String formatSensitivity(
      String sweepId, SweepSensitivity s, String paramA, String paramB) {
    StringBuilder sb = new StringBuilder("Sensitivity for sweep ").append(sweepId);
    sb.append(" (objective ").append(s.getObjective() == null ? "default" : s.getObjective().getValue());
    if (s.getStatus() != null) sb.append(", status ").append(s.getStatus().getValue());
    if (s.getRowsAnalysed() != null) sb.append(", ").append(s.getRowsAnalysed()).append(" rows analysed");
    sb.append(")\n");

    boolean heatmapsTruncated = Boolean.TRUE.equals(s.getHeatmapsTruncated());
    if (paramA != null) {
      SweepHeatmap surface = findHeatmap(s.getHeatmaps(), paramA, paramB);
      if (surface == null) {
        sb.append("\nNo interaction surface for ").append(paramA).append(" × ").append(paramB).append('.');
        sb.append(heatmapsTruncated
            ? " heatmapsTruncated is set, so the platform capped the pair surfaces and this pair "
                + "was probably dropped by the cap rather than never computed."
            : " heatmapsTruncated is not set, so the platform returned every pair it computed and "
                + "this one is not among them — check the axis names against the marginals.");
        sb.append("\nAxes swept: ").append(axisNames(s));
        return sb.toString();
      }
      sb.append("\nInteraction ").append(surface.getParamA()).append(" × ").append(surface.getParamB())
          .append(":\n");
      List<SweepHeatmapCell> cells = surface.getCells() == null ? List.of() : surface.getCells();
      for (SweepHeatmapCell cell : cells) {
        sb.append("  ").append(surface.getParamA()).append('=').append(cell.getValueA())
            .append(", ").append(surface.getParamB()).append('=').append(cell.getValueB())
            .append(" → ");
        appendAggregate(sb, cell.getBest(), cell.getMean(), null, cell.getCount());
        sb.append('\n');
      }
      if (heatmapsTruncated) sb.append(truncatedNote());
      return sb.toString().stripTrailing();
    }

    List<SweepMarginal> marginals = s.getMarginals() == null ? List.of() : s.getMarginals();
    if (marginals.isEmpty()) {
      sb.append("\nNo marginals: no completed run has been scored yet.");
      return sb.toString();
    }
    sb.append("\nMarginals (one axis at a time, every other axis collapsed):\n");
    for (SweepMarginal marginal : marginals) {
      sb.append(marginal.getParam()).append(":\n");
      List<SweepMarginalPoint> points = marginal.getPoints() == null ? List.of() : marginal.getPoints();
      for (SweepMarginalPoint point : points) {
        sb.append("  ").append(point.getValue()).append(" → ");
        appendAggregate(sb, point.getBest(), point.getMean(), point.getWorst(), point.getCount());
        sb.append('\n');
      }
    }
    if (heatmapsTruncated) sb.append(truncatedNote());
    return sb.toString().stripTrailing();
  }

  private static String truncatedNote() {
    return "\nheatmapsTruncated is set: the pair surfaces are quadratic in the axis count and the "
        + "platform capped them, so at least one interaction is missing from what it can return.";
  }

  private static String axisNames(SweepSensitivity s) {
    if (s.getMarginals() == null || s.getMarginals().isEmpty()) return "(none reported)";
    return String.join(", ", s.getMarginals().stream().map(SweepMarginal::getParam).toList());
  }

  private static SweepHeatmap findHeatmap(List<SweepHeatmap> heatmaps, String a, String b) {
    if (heatmaps == null) return null;
    for (SweepHeatmap h : heatmaps) {
      boolean straight = a.equals(h.getParamA()) && b.equals(h.getParamB());
      boolean swapped = b.equals(h.getParamA()) && a.equals(h.getParamB());
      if (straight || swapped) return h;
    }
    return null;
  }

  private static void appendAggregate(
      StringBuilder sb, Double best, Double mean, Double worst, Integer count) {
    if (best != null) sb.append(String.format(Locale.ROOT, "best %.4f", best));
    if (mean != null) sb.append(String.format(Locale.ROOT, "  mean %.4f", mean));
    if (worst != null) sb.append(String.format(Locale.ROOT, "  worst %.4f", worst));
    if (count != null) sb.append("  (n=").append(count).append(')');
  }

  // ---- list_strategies ------------------------------------------------------

  private static SyncToolSpecification listStrategies(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("list_strategies")
        .description("List every strategy registered under this account, most recently compiled "
            + "first. Account-scoped, not session-scoped — unlike list_jobs, this answers for "
            + "strategies compiled through any client, not just this session's submit_backtest "
            + "calls. Omits validation state to stay cheap regardless of how many are registered. "
            + "Use the returned strategyId with delete_strategy or get_strategy_code. "
            + "An empty list means the account has none registered — not an error.")
        .inputSchema(emptySchema())
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            List<StrategySummary> strategies = service.listStrategies();
            if (strategies.isEmpty()) return text("No registered strategies.");
            StringBuilder sb = new StringBuilder(
                "Registered strategies (" + strategies.size() + "):\n");
            strategies.forEach(s -> {
              sb.append("- ").append(s.getStrategyId());
              if (s.getCompiledAt() != null) {
                sb.append("  compiled ").append(s.getCompiledAt());
              }
              if (s.getRequiredSources() != null && !s.getRequiredSources().isEmpty()) {
                sb.append("  needs ").append(String.join(", ", s.getRequiredSources()));
              }
              sb.append('\n');
            });
            return text(sb.toString().stripTrailing());
          } catch (Exception e) {
            return error("Failed to list strategies: " + e.getMessage());
          }
        });
  }

  // ---- delete_strategy --------------------------------------------------------

  private static SyncToolSpecification deleteStrategy(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("delete_strategy")
        .description("Release a registered strategy. Backtests already run against it are "
            + "completely unaffected — deletion only stops the strategy counting against the "
            + "account and stops future validation or re-run under this id. Recompiling identical "
            + "source afterward registers a brand-new strategy under a new id; it does not "
            + "\"undelete\" this one.")
        .inputSchema(schema(
            Map.of("strategyId", prop("string",
                "Strategy ID, e.g. from list_strategies")),
            List.of("strategyId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            String strategyId = required(request.arguments(), "strategyId");
            service.deleteStrategy(strategyId);
            return text("Strategy " + strategyId + " deleted.");
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          } catch (Exception e) {
            return error("Failed to delete strategy: " + e.getMessage());
          }
        });
  }

  // ---- get_strategy_code -------------------------------------------------------

  private static SyncToolSpecification getStrategyCode(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("get_strategy_code")
        .description("Fetch the exact source last registered for a strategy id — the same text "
            + "originally compiled, whitespace and comments included.")
        .inputSchema(schema(
            Map.of("strategyId", prop("string",
                "Strategy ID, e.g. from list_strategies")),
            List.of("strategyId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          try {
            String strategyId = required(request.arguments(), "strategyId");
            return text(service.getStrategyCode(strategyId));
          } catch (IllegalArgumentException e) {
            return error(e.getMessage());
          } catch (Exception e) {
            return error("Failed to fetch strategy code: " + e.getMessage());
          }
        });
  }

  // ---- sweep argument parsing ---------------------------------------------

  private static Map<String, ParamAxis> parseParams(Object raw) {
    if (!(raw instanceof Map<?, ?> grid) || grid.isEmpty()) {
      throw new IllegalArgumentException(
          "Argument 'params' must be a non-empty object mapping each swept property to an axis, "
              + "e.g. {\"rsiPeriod\":{\"from\":7,\"to\":28,\"step\":1}}");
    }
    Map<String, ParamAxis> axes = new LinkedHashMap<>();
    grid.forEach((name, axis) -> axes.put(String.valueOf(name), parseAxis(String.valueOf(name), axis)));
    return axes;
  }

  private static ParamAxis parseAxis(String name, Object raw) {
    if (raw instanceof List<?> list) return axisValues(name, list);
    if (raw instanceof Map<?, ?> axis) {
      if (axis.get("values") instanceof List<?> list) return axisValues(name, list);
      if (axis.containsKey("from") && axis.containsKey("to") && axis.containsKey("step")) {
        return ParamAxis.range(
            axisNumber(name, "from", axis.get("from")),
            axisNumber(name, "to", axis.get("to")),
            axisNumber(name, "step", axis.get("step")));
      }
    }
    throw new IllegalArgumentException("Axis '" + name + "' must be either a range "
        + "{\"from\":…,\"to\":…,\"step\":…} or an explicit list {\"values\":[…]}");
  }

  private static ParamAxis axisValues(String name, List<?> raw) {
    List<Object> values = new ArrayList<>(raw.size());
    for (Object value : raw) {
      if (value instanceof Boolean || value instanceof Number) {
        values.add(value);
      } else if (value instanceof String s && "true".equalsIgnoreCase(s.trim())) {
        values.add(Boolean.TRUE);
      } else if (value instanceof String s && "false".equalsIgnoreCase(s.trim())) {
        values.add(Boolean.FALSE);
      } else {
        values.add(axisNumber(name, "values", value));
      }
    }
    return new ParamAxis.Values(values);
  }

  private static double axisNumber(String name, String field, Object value) {
    if (value instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Axis '" + name + "': '" + field
          + "' must be a number (or a boolean, for a flag), got " + value);
    }
  }

  private static SweepObjective parseObjective(String raw) {
    try {
      return SweepObjective.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown objective '" + raw + "'. Valid: sharpe, sortino, pnl, maxdd");
    }
  }

  private static SweepSampler parseSampler(String raw) {
    try {
      return SweepSampler.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown sampler '" + raw + "'. Valid: grid, random, lhs");
    }
  }

  private static WalkForwardSpec parseWalkForward(Object raw) {
    if (raw == null) return null;
    if (!(raw instanceof Map<?, ?> spec)) {
      throw new IllegalArgumentException(
          "Argument 'walkForward' must be an object, e.g. {\"folds\":5,\"inSamplePct\":70}");
    }
    Integer folds = asInteger(spec.get("folds"));
    if (folds == null) {
      throw new IllegalArgumentException("'walkForward' needs a 'folds' count of at least 2");
    }
    Integer inSamplePct = asInteger(spec.get("inSamplePct"));
    return inSamplePct == null
        ? WalkForwardSpec.of(folds)
        : WalkForwardSpec.of(folds, inSamplePct);
  }

  // ---- formatting ---------------------------------------------------------

  private static String formatJobStatus(String jobId, String instrument, String exchangeId,
                                        JobStatus status, String submittedAt, JobResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("Job ").append(jobId).append(": ").append(status).append('\n');
    sb.append("Exchange: ").append(exchangeId).append(" | Instrument: ").append(instrument).append('\n');
    sb.append("Submitted: ").append(submittedAt);

    if (result != null) {
      sb.append("\n\n=== Results ===\n");
      if (result.pnlTotal() != null) {
        sb.append(String.format("P&L:          %+.4f\n", result.pnlTotal()));
      }
      if (result.totalTrades() != null) {
        sb.append("Trades:       ").append(result.totalTrades());
        if (result.winRate() != null) {
          sb.append(String.format(" (win rate: %.1f%%)", result.winRate()));
        }
        sb.append('\n');
      }
      if (result.sharpeRatio() != null) {
        sb.append(String.format("Sharpe:       %.3f", result.sharpeRatio()));
        if (result.sortinoRatio() != null) {
          sb.append(String.format(" | Sortino: %.3f", result.sortinoRatio()));
        }
        sb.append('\n');
      }
      if (result.cagr() != null) {
        sb.append(String.format("CAGR:         %.2f%%\n", result.cagr() * 100));
      }
      if (result.maxDrawdownPercent() != null) {
        sb.append(String.format("Max Drawdown: %.2f%%\n", result.maxDrawdownPercent()));
      }
      if (result.signalCount() != null) {
        sb.append("Signals:      ").append(result.signalCount()).append('\n');
      }
      if (result.iops() != null) {
        sb.append(String.format("Throughput:   %.0f iops", result.iops()));
        if (result.hostName() != null) {
          sb.append(" (").append(result.hostName()).append(')');
        }
        sb.append('\n');
      }
    }
    return sb.toString().stripTrailing();
  }

  // ---- equity curve -------------------------------------------------------

  /**
   * Render an equity curve as compact JSON with parallel arrays (smaller than an array of
   * objects). Curves longer than {@code maxPoints} are downsampled.
   */
  private static String equityCurveJson(String jobId, List<EquityPoint> curve, int maxPoints) {
    List<EquityPoint> sampled = downsample(curve, maxPoints);
    StringBuilder t = new StringBuilder();
    StringBuilder eq = new StringBuilder();
    for (int i = 0; i < sampled.size(); i++) {
      if (i > 0) {
        t.append(',');
        eq.append(',');
      }
      t.append(sampled.get(i).timestamp());
      eq.append(sampled.get(i).equity());
    }
    return "{\"jobId\":\"" + jobId + "\",\"unit\":\"epoch_ms\""
        + ",\"points\":" + sampled.size()
        + ",\"totalPoints\":" + curve.size()
        + ",\"downsampled\":" + (sampled.size() < curve.size())
        + ",\"t\":[" + t + "]"
        + ",\"equity\":[" + eq + "]}";
  }

  /**
   * Downsample to ~{@code maxPoints} via uniform striding, always keeping the first and last
   * points and the global min/max so the worst drawdown and the peak survive the reduction.
   */
  private static List<EquityPoint> downsample(List<EquityPoint> curve, int maxPoints) {
    int n = curve.size();
    if (maxPoints <= 0 || n <= maxPoints) return curve;
    int minI = 0, maxI = 0;
    for (int i = 1; i < n; i++) {
      double e = curve.get(i).equity();
      if (e < curve.get(minI).equity()) minI = i;
      if (e > curve.get(maxI).equity()) maxI = i;
    }
    TreeSet<Integer> idx = new TreeSet<>();
    idx.add(0);
    idx.add(n - 1);
    idx.add(minI);
    idx.add(maxI);
    double stride = (double) (n - 1) / (maxPoints - 1);
    for (int k = 0; k < maxPoints; k++) {
      idx.add((int) Math.round(k * stride));
    }
    List<EquityPoint> out = new ArrayList<>(idx.size());
    for (int i : idx) {
      out.add(curve.get(i));
    }
    return out;
  }

  // ---- helpers ------------------------------------------------------------

  private static boolean asBool(Object v) {
    if (v instanceof Boolean b) return b;
    return v != null && "true".equalsIgnoreCase(v.toString().trim());
  }

  private static int asInt(Object v, int defaultValue) {
    Integer parsed = asInteger(v);
    return parsed == null ? defaultValue : parsed;
  }

  /** Parse an optional integer argument; {@code null} when absent or unparseable. */
  private static Integer asInteger(Object v) {
    if (v instanceof Number n) return n.intValue();
    if (v != null) {
      try {
        return Integer.valueOf(v.toString().trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static CallToolResult text(String content) {
    return CallToolResult.builder().addTextContent(content).build();
  }

  private static CallToolResult error(String message) {
    return CallToolResult.builder().isError(true).addTextContent("Error: " + message).build();
  }

  private static String required(Map<String, Object> args, String key) {
    if (args == null || !args.containsKey(key)) {
      throw new IllegalArgumentException("Missing required argument: " + key);
    }
    Object value = args.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException("Argument '" + key + "' must not be blank");
    }
    return value.toString();
  }

  /** An optional string argument; {@code null} when absent or blank. */
  private static String optional(Map<String, Object> args, String key) {
    if (args == null) return null;
    Object value = args.get(key);
    if (value == null || value.toString().isBlank()) return null;
    return value.toString();
  }

  private static JsonSchema emptySchema() {
    return new JsonSchema("object", Map.of(), List.of(), null, null, null);
  }

  private static JsonSchema schema(Map<String, Object> properties, List<String> required) {
    return new JsonSchema("object", properties, required, null, null, null);
  }

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  /** {@code Map.of} tops out at ten pairs; the sweep schema needs more. */
  private static Map<String, Object> mapOf(Object... keyValuePairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return map;
  }
}
