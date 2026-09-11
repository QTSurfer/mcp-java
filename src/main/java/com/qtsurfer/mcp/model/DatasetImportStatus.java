package com.qtsurfer.mcp.model;

/** Safe state of an external dataset import and its optional resulting version. */
public record DatasetImportStatus(
    String datasetId, String importId, String status, String jobId, String error, String versionId) {}
