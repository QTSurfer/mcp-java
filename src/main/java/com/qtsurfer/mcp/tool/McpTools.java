package com.qtsurfer.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.mcp.model.JobResult;
import com.qtsurfer.mcp.model.JobStatus;
import com.qtsurfer.mcp.service.BacktestingService;

import java.util.List;
import java.util.Map;

/**
 * Builds the five MCP tools that expose QTSurfer over the Model Context Protocol.
 *
 * <p>Each {@link SyncToolSpecification} pairs a {@link Tool} (name + description + JSON Schema)
 * with a lambda handler that delegates to {@link BacktestingService}. No MCP protocol detail
 * leaks into the service layer.
 */
public final class McpTools {

  private McpTools() {}

  public static List<SyncToolSpecification> build(BacktestingService service) {
    return List.of(
        listExchanges(service),
        listInstruments(service),
        submitBacktest(service),
        getJobStatus(service),
        listJobs(service));
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
              if (i.getDataFrom() != null && i.getDataTo() != null) {
                sb.append(" data: ").append(i.getDataFrom().toLocalDate())
                  .append(" → ").append(i.getDataTo().toLocalDate());
              }
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

  // ---- submit_backtest ----------------------------------------------------

  private static SyncToolSpecification submitBacktest(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("submit_backtest")
        .description("Compile a Java strategy and queue a backtesting run on QTSurfer. "
            + "Returns the job ID immediately — poll with get_job_status. "
            + "Call list_instruments first to choose a valid exchangeId and instrument.")
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
        .description("Get the current status of a submitted backtesting job.")
        .inputSchema(schema(
            Map.of("jobId", prop("string", "Job ID returned by submit_backtest")),
            List.of("jobId")))
        .build();
    return new SyncToolSpecification(tool,
        (exchange, request) -> {
          String jobId = required(request.arguments(), "jobId");
          return service.getJobStatus(jobId)
              .map(j -> text(formatJobStatus(j.jobId(), j.instrument(), j.exchangeId(),
                  j.status(), j.submittedAt(), j.result())))
              .orElse(text("Job not found: " + jobId
                  + ". Only jobs submitted in this session are tracked."));
        });
  }

  // ---- list_jobs ----------------------------------------------------------

  private static SyncToolSpecification listJobs(BacktestingService service) {
    Tool tool = Tool.builder()
        .name("list_jobs")
        .description("List backtesting jobs submitted in this session. "
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
                statusFilter = JobStatus.valueOf(s.toUpperCase());
              } catch (IllegalArgumentException e) {
                return error("Unknown status '" + s + "'. Valid: COMPILING, PREPARING, EXECUTING, COMPLETED, FAILED, CANCELED");
              }
            }
          }
          var jobList = service.listJobs(statusFilter);
          if (jobList.isEmpty()) {
            return text("No jobs found" + (statusFilter != null ? " with status " + statusFilter : "") + ".");
          }
          StringBuilder sb = new StringBuilder();
          jobList.forEach(j -> sb.append(j).append('\n'));
          return text(sb.toString().stripTrailing());
        });
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

  // ---- helpers ------------------------------------------------------------

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

  private static JsonSchema emptySchema() {
    return new JsonSchema("object", Map.of(), List.of(), null, null, null);
  }

  private static JsonSchema schema(Map<String, Object> properties, List<String> required) {
    return new JsonSchema("object", properties, required, null, null, null);
  }

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }
}
