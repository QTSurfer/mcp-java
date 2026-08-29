package com.qtsurfer.mcp.model;

/** Safe status of a dataset upload and its optional ingested version. */
public record DatasetUploadStatus(
    String datasetId,
    String uploadId,
    String status,
    String ingestJobId,
    String versionId,
    Integer rows,
    Integer bytes,
    String cadence,
    Integer gaps,
    Integer largestGapSteps) {}
