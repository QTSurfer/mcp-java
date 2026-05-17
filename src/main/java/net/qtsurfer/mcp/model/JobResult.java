package net.qtsurfer.mcp.model;

/**
 * Execution metrics captured when a backtest job reaches COMPLETED state.
 * All numeric fields are nullable — they are absent when the strategy emitted no trades.
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
    Double iops
) {}
