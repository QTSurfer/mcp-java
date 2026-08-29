package com.qtsurfer.mcp.model;

/** One best-effort strategy property declaration returned after compilation. */
public record StrategyProperty(
    String name,
    String description,
    String defaultValue,
    Boolean reflected,
    Double min,
    Double max,
    Double step) {}
