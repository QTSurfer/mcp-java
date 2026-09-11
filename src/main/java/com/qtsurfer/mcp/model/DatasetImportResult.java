package com.qtsurfer.mcp.model;

/** Safe identifiers returned when an external dataset import starts. */
public record DatasetImportResult(String datasetId, String importId, String jobId, String status) {}
