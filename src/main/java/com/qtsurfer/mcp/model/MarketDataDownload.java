package com.qtsurfer.mcp.model;

/** Result of a guarded streamed market-data download written by the MCP server. */
public record MarketDataDownload(String outputPath, long bytes, String format) {}
