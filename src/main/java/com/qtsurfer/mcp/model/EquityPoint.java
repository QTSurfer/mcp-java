package com.qtsurfer.mcp.model;

/**
 * One sample of the running equity curve produced by a backtest.
 *
 * @param timestamp epoch milliseconds; the first point is anchored at the backtest {@code from},
 *                  subsequent points carry the timestamp of each emitted yield event
 * @param equity    running equity at this point ({@code initialCapital + cumulativePnl})
 */
public record EquityPoint(long timestamp, double equity) {}
