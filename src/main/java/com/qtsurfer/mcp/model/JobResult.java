package com.qtsurfer.mcp.model;

import java.util.List;

/**
 * Execution metrics captured when a backtest job reaches COMPLETED state.
 * All numeric fields are nullable — they are absent when the strategy emitted no trades.
 *
 * <p>{@code equityCurve} is never null (empty when the run produced no yield events). It is
 * not part of the default status summary; callers opt in via the {@code includeEquityCurve}
 * flag on {@code get_job_status} or the dedicated {@code get_equity_curve} tool.
 */
public record JobResult(
    Double pnlTotal,
    Long totalTrades,
    Double winRate,
    Double sharpeRatio,
    Double sortinoRatio,
    Double cagr,
    Double maxDrawdown,
    Double maxDrawdownPercent,
    Long signalCount,
    String hostName,
    Double iops,
    List<EquityPoint> equityCurve
) {}
