package com.qtsurfer.mcp.model;

/** Safe dataset metadata suitable for an MCP text response. */
public record DatasetSummary(
    String datasetId,
    String name,
    String instrument,
    String type,
    String currentVersionId,
    String from,
    String to,
    String cadence,
    String dataFormat,
    String deletedAt) {

  /** Preserve the original constructor for callers that have no resolved dataset type. */
  public DatasetSummary(String datasetId, String name, String instrument, String currentVersionId,
      String from, String to, String cadence, String dataFormat, String deletedAt) {
    this(datasetId, name, instrument, null, currentVersionId, from, to, cadence, dataFormat, deletedAt);
  }
}
