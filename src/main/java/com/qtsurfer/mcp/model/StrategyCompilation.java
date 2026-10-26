package com.qtsurfer.mcp.model;

import java.util.List;

/** A compiled strategy id, declared properties and required plan capabilities. */
public record StrategyCompilation(
    String strategyId, List<StrategyProperty> declaredProperties, List<String> requires) {

  public StrategyCompilation(String strategyId, List<StrategyProperty> declaredProperties) {
    this(strategyId, declaredProperties, List.of());
  }

  public StrategyCompilation {
    declaredProperties = declaredProperties == null ? List.of() : List.copyOf(declaredProperties);
    requires = requires == null ? List.of() : List.copyOf(requires);
  }
}
