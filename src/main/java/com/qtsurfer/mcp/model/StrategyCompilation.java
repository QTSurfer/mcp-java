package com.qtsurfer.mcp.model;

import java.util.List;

/** A compiled strategy id together with the properties discovered by the platform. */
public record StrategyCompilation(String strategyId, List<StrategyProperty> declaredProperties) {}
