package com.qtsurfer.mcp.model;

import java.util.List;

/** A compiled strategy id, declared properties and required plan capabilities. */
public record StrategyCompilation(
    String strategyId, List<StrategyProperty> declaredProperties, List<String> requires,
    String name, String language) {

  /** Construct compilation metadata without optional source identity. */
  public StrategyCompilation(String strategyId, List<StrategyProperty> declaredProperties, List<String> requires) {
    this(strategyId, declaredProperties, requires, null, null);
  }

  public StrategyCompilation(String strategyId, List<StrategyProperty> declaredProperties) {
    this(strategyId, declaredProperties, List.of());
  }

  public StrategyCompilation {
    declaredProperties = declaredProperties == null ? List.of() : List.copyOf(declaredProperties);
    requires = requires == null ? List.of() : List.copyOf(requires);
  }
}
