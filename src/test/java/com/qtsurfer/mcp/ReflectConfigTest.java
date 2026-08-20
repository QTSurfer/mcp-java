package com.qtsurfer.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtsurfer.mcp.service.BacktestingService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@code reflect-config.json}, the one artifact in this repo that no other check covers.
 * The JVM build ignores it, the native image is only built on a tag, and the offline stub tests
 * never put a real payload through Jackson — so a fully green pipeline says nothing about
 * whether the native binary can deserialize what the platform sends.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Two sources, and neither is a list of names kept by hand.
 *
 * <p><strong>Derived.</strong> {@link #derive()} walks {@link BacktestingService} — the seam the
 * whole tool surface goes through — taking every method's return type, following generic type
 * arguments, and then, for every serialized type it lands on, following that type's declared
 * fields and member types. Anything reachable that way is a type a tool can hand back, so it is
 * a type the native image has to be able to construct and introspect. Add a tool that returns a
 * new shape and this set grows on its own.
 *
 * <p><strong>Pinned.</strong> Two categories genuinely cannot be reached from a return type,
 * because they never are one: request bodies the client serializes on the way out, and wire
 * types the SDK's workflows deserialize internally and flatten before handing anything back.
 * They are listed explicitly, in two constants with their reason, rather than by weakening the
 * derived walk to something that would happen to include them. Three assertions keep the pins
 * from rotting: every pinned name must still resolve, no pinned name may have become derivable,
 * and no {@code com.qtsurfer} entry may sit in the config without belonging to one set or the
 * other.
 */
class ReflectConfigTest {

  private static final String CONFIG =
      "/META-INF/native-image/com.qtsurfer/mcp-java/reflect-config.json";

  /** Packages whose types travel as JSON and therefore need reflective access. */
  private static final List<String> SERIALIZED_PACKAGES =
      List.of("com.qtsurfer.api.client.model", "com.qtsurfer.mcp.model");

  /** What a serialized type needs registered for Jackson to construct and introspect it. */
  private static final List<String> REQUIRED_FLAGS =
      List.of("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields");

  private static final String MODEL = "com.qtsurfer.api.client.model.";

  /**
   * Request bodies. The client serializes these on the way out and no tool ever returns one, so
   * no walk over return types can reach them — but Jackson still introspects them in the native
   * image, on the request path.
   */
  private static final List<String> PINNED_REQUEST_BODIES = List.of(
      MODEL + "ExecuteBacktestRequest",
      MODEL + "PrepareRequest",
      MODEL + "PrepareRequest$CadenceEnum",
      MODEL + "ExecuteSweepRequest",
      MODEL + "SweepSpecRequest",
      MODEL + "SweepSpecRequest$ObjectiveEnum",
      MODEL + "SweepSpecRequest$SamplerEnum",
      MODEL + "SweepBaseConfig",
      MODEL + "SweepBaseConfig$FeeLegEnum",
      MODEL + "SweepAxis",
      MODEL + "SweepAxis$SweepAxisSerializer",
      MODEL + "SweepAxis$SweepAxisDeserializer",
      MODEL + "SweepAxisOneOf",
      MODEL + "SweepAxisOneOf1",
      MODEL + "SweepAxisOneOf1ValuesInner",
      MODEL + "SweepAxisOneOf1ValuesInner$SweepAxisOneOf1ValuesInnerSerializer",
      MODEL + "SweepAxisOneOf1ValuesInner$SweepAxisOneOf1ValuesInnerDeserializer",
      MODEL + "WalkForwardRequest");

  /**
   * Wire types the SDK deserializes inside its own workflows — auth, compile, prepare, poll,
   * cancel, validate — and either consumes or flattens into something else before any tool sees
   * it. A backtest's {@code ResultMap} is the clearest case: it arrives from the platform on
   * every poll, and the service maps it to its own record, so the type never appears in a
   * signature the walk can follow.
   */
  private static final List<String> PINNED_WORKFLOW_INTERMEDIATES = List.of(
      MODEL + "AbstractOpenApiSchema",
      MODEL + "AcceptedJob",
      MODEL + "AuthTokenError",
      MODEL + "AuthTokenError$CodeEnum",
      MODEL + "AuthTokenResponse",
      MODEL + "AuthTokenResponse$TierEnum",
      MODEL + "AuthTokenResponse$TokenTypeEnum",
      MODEL + "BacktestJobResult",
      MODEL + "CancelBacktest200Response",
      MODEL + "CancelBacktest200Response$StatusEnum",
      MODEL + "CancelSweep200Response",
      MODEL + "CancelSweep200Response$StatusEnum",
      MODEL + "CompileStrategy200Response",
      MODEL + "DataSourceType",
      MODEL + "DeleteStrategy200Response",
      MODEL + "DeleteStrategy200Response$DeletedEnum",
      MODEL + "EquityPoint",
      MODEL + "GetStrategyCode200Response",
      MODEL + "HalLink",
      MODEL + "InstrumentLinks",
      MODEL + "InstrumentListMeta",
      MODEL + "InstrumentListMeta$SegmentEnum",
      MODEL + "InstrumentListResponse",
      MODEL + "JobState",
      MODEL + "JobState$StatusEnum",
      MODEL + "ListStrategies200Response",
      MODEL + "Notice",
      MODEL + "Notice$ProvenanceEnum",
      MODEL + "PrepareJobState",
      MODEL + "PrepareJobState$StatusEnum",
      MODEL + "PrepareJobStateAllOfHoursWithoutData",
      MODEL + "PrepareJobStateAllOfHoursWithoutData$RationaleEnum",
      MODEL + "ResponseError",
      MODEL + "ResultMap",
      MODEL + "ResultMap$SignalsUploadEnum",
      MODEL + "StrategyState",
      MODEL + "StrategyState$RequiredSourcesEnum",
      MODEL + "StrategyState$ValidationEnum");

  // ---- the gate -------------------------------------------------------------

  @Test
  void everyTypeTheToolSurfaceCanReturnIsRegistered() throws IOException {
    Map<String, Set<String>> config = readConfig();
    assertMissing("reachable from a BacktestingService return type", derive(), config);
  }

  @Test
  void everyPinnedRequestBodyIsRegistered() throws IOException {
    assertMissing("pinned: serialized on the request path", PINNED_REQUEST_BODIES, readConfig());
  }

  @Test
  void everyPinnedWorkflowIntermediateIsRegistered() throws IOException {
    assertMissing("pinned: deserialized inside the SDK's workflows",
        PINNED_WORKFLOW_INTERMEDIATES, readConfig());
  }

  // ---- keeping the pins honest ----------------------------------------------

  @Test
  void everyPinnedTypeStillExists() {
    List<String> unresolved = new ArrayList<>();
    for (String name : pinned()) {
      try {
        Class.forName(name);
      } catch (ClassNotFoundException e) {
        unresolved.add(name);
      }
    }
    assertThat(unresolved)
        .as("pinned types that no longer exist — a rename or removal in the client; "
            + "drop or update the pin, and the reflect-config entry with it")
        .isEmpty();
  }

  @Test
  void noPinnedTypeHasBecomeDerivable() {
    Set<String> overlap = new TreeSet<>(derive());
    overlap.retainAll(pinned());
    assertThat(overlap)
        .as("types now reachable from a tool return type while still pinned — the derived walk "
            + "covers them, so remove the pin rather than maintaining it by hand")
        .isEmpty();
  }

  @Test
  void noRegisteredQtsurferTypeIsUnaccountedFor() throws IOException {
    Set<String> accounted = new LinkedHashSet<>(derive());
    accounted.addAll(pinned());
    Set<String> stale = new TreeSet<>();
    for (String name : readConfig().keySet()) {
      if (name.startsWith("com.qtsurfer.") && !accounted.contains(name)) {
        stale.add(name);
      }
    }
    assertThat(stale)
        .as("reflect-config entries that neither the walk nor a pin accounts for — most likely "
            + "left behind by a rename; delete them or pin them with a reason")
        .isEmpty();
  }

  // ---- derivation -----------------------------------------------------------

  /**
   * Every serialized type a tool can hand back: the return types of {@link BacktestingService},
   * transitively through generic arguments, declared fields and member types.
   *
   * @return the fully qualified names, in {@code $}-nested form as reflect-config spells them
   */
  private static Set<String> derive() {
    Set<Class<?>> found = new LinkedHashSet<>();
    Deque<Type> pending = new ArrayDeque<>();
    for (Method method : BacktestingService.class.getMethods()) {
      pending.add(method.getGenericReturnType());
    }
    while (!pending.isEmpty()) {
      Class<?> type = rawType(pending.poll(), pending);
      if (type == null || !isSerialized(type) || !found.add(type)) continue;
      for (Field field : type.getDeclaredFields()) {
        if (!field.isSynthetic()) pending.add(field.getGenericType());
      }
      // Nested enums carry the wire values of their owner's enum-typed properties.
      pending.addAll(List.of(type.getDeclaredClasses()));
    }
    Set<String> names = new TreeSet<>();
    found.forEach(c -> names.add(c.getName()));
    return names;
  }

  /**
   * Reduce a generic type to its raw class, queueing anything else it carries — type arguments,
   * array components, wildcard bounds — so the walk follows them too.
   */
  private static Class<?> rawType(Type type, Deque<Type> pending) {
    if (type instanceof Class<?> c) {
      return c.isArray() ? c.getComponentType() : c;
    }
    if (type instanceof ParameterizedType p) {
      pending.addAll(List.of(p.getActualTypeArguments()));
      return rawType(p.getRawType(), pending);
    }
    if (type instanceof GenericArrayType a) {
      pending.add(a.getGenericComponentType());
      return null;
    }
    if (type instanceof WildcardType w) {
      pending.addAll(List.of(w.getUpperBounds()));
      return null;
    }
    return null;
  }

  private static boolean isSerialized(Class<?> type) {
    if (type.isPrimitive() || type.getPackageName().isEmpty()) return false;
    return SERIALIZED_PACKAGES.contains(type.getPackageName());
  }

  private static Set<String> pinned() {
    Set<String> all = new TreeSet<>(PINNED_REQUEST_BODIES);
    all.addAll(PINNED_WORKFLOW_INTERMEDIATES);
    return all;
  }

  // ---- config -------------------------------------------------------------

  /** Read the packaged config as a name → declared-flags map. */
  private static Map<String, Set<String>> readConfig() throws IOException {
    try (InputStream in = ReflectConfigTest.class.getResourceAsStream(CONFIG)) {
      assertThat(in).as("reflect-config.json must be on the classpath at %s", CONFIG).isNotNull();
      JsonNode root = new ObjectMapper().readTree(in);
      Map<String, Set<String>> entries = new LinkedHashMap<>();
      for (JsonNode entry : root) {
        Set<String> flags = new LinkedHashSet<>();
        entry.fieldNames().forEachRemaining(flags::add);
        entries.put(entry.get("name").asText(), flags);
      }
      return entries;
    }
  }

  private static void assertMissing(
      String what, Iterable<String> expected, Map<String, Set<String>> config) {
    Set<String> missing = new TreeSet<>();
    Set<String> underRegistered = new TreeSet<>();
    for (String name : expected) {
      Set<String> flags = config.get(name);
      if (flags == null) {
        missing.add(name);
      } else if (!flags.containsAll(REQUIRED_FLAGS)) {
        underRegistered.add(name);
      }
    }
    assertThat(missing)
        .as("types %s but absent from reflect-config.json — the native binary would fail to "
            + "deserialize them while the JVM build stays green", what)
        .isEmpty();
    assertThat(underRegistered)
        .as("types %s but registered without all of %s", what, REQUIRED_FLAGS)
        .isEmpty();
  }
}
