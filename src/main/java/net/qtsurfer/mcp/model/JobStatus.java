package net.qtsurfer.mcp.model;

/** Lifecycle states of a QTSurfer backtesting job as seen through the MCP layer. */
public enum JobStatus {
  COMPILING,
  PREPARING,
  EXECUTING,
  COMPLETED,
  FAILED,
  CANCELED
}
