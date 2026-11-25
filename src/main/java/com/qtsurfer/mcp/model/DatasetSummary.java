package com.qtsurfer.mcp.model;

import java.util.Map;

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
    String deletedAt,
    String collectionId,
    Map<String, String> meta) {

  /** Preserve the constructor for a dataset without collection or provenance metadata. */
  public DatasetSummary(String datasetId, String name, String instrument, String type,
      String currentVersionId, String from, String to, String cadence, String dataFormat, String deletedAt) {
    this(datasetId, name, instrument, type, currentVersionId, from, to, cadence, dataFormat, deletedAt,
        null, null);
  }

  /** Preserve the original constructor for callers that have no resolved dataset type. */
  public DatasetSummary(String datasetId, String name, String instrument, String currentVersionId,
      String from, String to, String cadence, String dataFormat, String deletedAt) {
    this(datasetId, name, instrument, null, currentVersionId, from, to, cadence, dataFormat, deletedAt);
  }
}
