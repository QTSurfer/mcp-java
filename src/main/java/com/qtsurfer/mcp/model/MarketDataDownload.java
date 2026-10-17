package com.qtsurfer.mcp.model;

/** Result of a guarded streamed market-data download written by the MCP server. */
public record MarketDataDownload(String outputPath, long bytes, String format, String contentType,
    String timestampUnit, String dataStatus, String dataThrough) {
  /** Construct a download result when the transport supplies no response metadata. */
  public MarketDataDownload(String outputPath, long bytes, String format) {
    this(outputPath, bytes, format, null, null, null, null);
  }
}
