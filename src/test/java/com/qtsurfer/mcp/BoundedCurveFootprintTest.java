package com.qtsurfer.mcp;

import com.qtsurfer.api.client.model.EquityCurveMeta;
import com.qtsurfer.api.client.model.EquityCurveOutMode;
import com.qtsurfer.api.client.model.EquityCurveResult;
import com.qtsurfer.api.sdk.BoundedEquityCurve;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jol.info.GraphLayout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records local, synthetic curve payload and retained-heap footprints without contacting the API.
 *
 * <p>The fixture has the exact current {@code SHORT+differential} topology: boxed timestamp and
 * equity arrays in the generated client, then the SDK's normalized immutable point list. JOL's
 * retained-size result is VM-layout dependent, so this test reports it for review rather than
 * fixing a platform-specific byte count as an assertion.
 */
class BoundedCurveFootprintTest {

  static {
    // JDK 25 disallows Unsafe offsets for record fields unless JOL opts into this compatibility mode.
    System.setProperty("jol.magicFieldOffset", "true");
  }

  @ParameterizedTest
  @ValueSource(ints = {1_000, 10_000})
  void reportsSyntheticCompactCurveFootprint(int pointCount) {
    EquityCurveResult generated = differentialShortCurve(pointCount);
    BoundedEquityCurve normalized = BoundedEquityCurve.decode(generated, pointCount);

    int compactJsonBytes = compactJson(generated).getBytes(StandardCharsets.UTF_8).length;
    long generatedBytes = GraphLayout.parseInstance(generated).totalSize();
    long normalizedBytes = GraphLayout.parseInstance(normalized).totalSize();
    long decodePeakBytes = GraphLayout.parseInstance(generated, normalized).totalSize();

    System.out.printf(Locale.ROOT,
        "curve-footprint points=%d compactJsonBytes=%d generatedRetainedBytes=%d "
            + "normalizedRetainedBytes=%d decodePeakRetainedBytes=%d%n",
        pointCount, compactJsonBytes, generatedBytes, normalizedBytes, decodePeakBytes);

    assertThat(normalized.points()).hasSize(pointCount);
    assertThat(generated.getTimestamps()).hasSize(pointCount);
    assertThat(generated.getEquities()).hasSize(pointCount);
  }

  private static EquityCurveResult differentialShortCurve(int pointCount) {
    List<Long> timestamps = new ArrayList<>(pointCount);
    List<Double> equities = new ArrayList<>(pointCount);
    long timestamp = 1_700_000_000_000L;
    double equity = 10_000.0;
    for (int index = 0; index < pointCount; index++) {
      timestamps.add(index == 0 ? timestamp : 60_000L);
      equities.add(index == 0 ? equity : Math.sin(index / 25.0));
    }
    return new EquityCurveResult()
        .timestamps(timestamps)
        .equities(equities)
        .meta(new EquityCurveMeta()
            .inputPointCount(pointCount)
            .outputPointCount(pointCount)
            .resampled(pointCount > BoundedEquityCurve.DEFAULT_MAX_RESAMPLE)
            .differential(true)
            .outMode(EquityCurveOutMode.SHORT));
  }

  private static String compactJson(EquityCurveResult curve) {
    StringBuilder json = new StringBuilder("{\"meta\":{\"inputPointCount\":")
        .append(curve.getMeta().getInputPointCount())
        .append(",\"outputPointCount\":")
        .append(curve.getMeta().getOutputPointCount())
        .append(",\"resampled\":")
        .append(curve.getMeta().getResampled())
        .append(",\"differential\":true,\"outMode\":\"short\"},\"timestamps\":[");
    appendValues(json, curve.getTimestamps());
    json.append("],\"equities\":[");
    appendValues(json, curve.getEquities());
    return json.append("]}").toString();
  }

  private static void appendValues(StringBuilder json, List<?> values) {
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) json.append(',');
      json.append(values.get(index));
    }
  }
}
