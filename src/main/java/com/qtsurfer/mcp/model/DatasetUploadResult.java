package com.qtsurfer.mcp.model;

/** Result of an uploaded dataset version after its asynchronous ingest has been requested. */
public record DatasetUploadResult(String datasetId, String uploadId, String ingestJobId, long bytes) {}
