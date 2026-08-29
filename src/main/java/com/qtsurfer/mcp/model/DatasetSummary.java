package com.qtsurfer.mcp.model;

/** Safe dataset metadata suitable for an MCP text response. */
public record DatasetSummary(
    String datasetId,
    String name,
    String instrument,
    String currentVersionId,
    String from,
    String to,
    String cadence) {}
