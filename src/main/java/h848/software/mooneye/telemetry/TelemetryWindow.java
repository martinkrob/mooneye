package h848.software.mooneye.telemetry;

import java.util.List;

/**
 * Aggregated statistics over a fixed time span of {@link TelemetrySample}s, meant to be paired
 * with the camera frame taken at {@link #endTs()}.
 * <p>
 * Naming convention, because the two are easy to confuse and answer different questions:
 * <ul>
 *   <li>{@code ...Delta}  - last minus first. Detects drift: a bed cooling down, a nozzle losing heat.</li>
 *   <li>{@code ...StdDev} - spread around the mean. Detects instability in the regulation loop.</li>
 *   <li>{@code ...Error}  - current temperature minus its target. Detects "not where it should be".</li>
 * </ul>
 */
public record TelemetryWindow(
        long startTs,
        long endTs,
        int sampleCount,
        // Fraction of the expected samples that actually arrived, 0..1. The single most important
        // field here: a window built from 2 of 10 expected samples describes almost nothing, and
        // any consumer must refuse to draw conclusions from it.
        double coverage,
        String printState,
        boolean stateChanged,
        double distanceTravelledMm,
        double extrudedFilamentMm,
        double zHeight,
        double zDeltaMm,
        double velocityAvg,
        double velocityMax,
        double extruderTempDelta,
        double extruderTempStdDev,
        double extruderTempError,
        double bedTempDelta,
        double bedTempStdDev,
        double bedTempError,
        double extruderPowerAvg,
        double printDurationSec) {

    /**
     * Folds the samples of a window into its statistics. The list must be ordered by timestamp
     * and non-empty; {@link TelemetryAggregator} guarantees both.
     *
     * @param expectedIntervalMs the nominal sampling period, used only to judge {@link #coverage()}
     */
    static TelemetryWindow of(List<TelemetrySample> samples, long startTs, long endTs, long expectedIntervalMs) {
        TelemetrySample first = samples.getFirst();
        TelemetrySample last = samples.getLast();
        int n = samples.size();

        double distance = 0.0;
        double velocitySum = 0.0;
        double velocityMax = 0.0;
        double powerSum = 0.0;
        boolean stateChanged = false;

        for (int i = 0; i < n; i++) {
            TelemetrySample s = samples.get(i);
            if (i > 0) {
                TelemetrySample prev = samples.get(i - 1);
                double dx = s.x() - prev.x();
                double dy = s.y() - prev.y();
                double dz = s.z() - prev.z();
                distance += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            velocitySum += s.velocity();
            velocityMax = Math.max(velocityMax, s.velocity());
            powerSum += s.extruderPower();
            if (!java.util.Objects.equals(s.printState(), last.printState())) {
                stateChanged = true;
            }
        }

        // filament_used restarts at 0 on a new print, which would show up as a large negative
        // number in the window straddling the job boundary. Clamp rather than report nonsense.
        double extruded = Math.max(0.0, last.filamentUsed() - first.filamentUsed());

        long expectedSamples = Math.max(1, Math.round((double) (endTs - startTs) / expectedIntervalMs));
        double coverage = Math.min(1.0, (double) n / expectedSamples);

        return new TelemetryWindow(
                startTs,
                endTs,
                n,
                coverage,
                last.printState(),
                stateChanged,
                distance,
                extruded,
                last.z(),
                last.z() - first.z(),
                velocitySum / n,
                velocityMax,
                last.extruderTemp() - first.extruderTemp(),
                stdDev(samples, TelemetrySample::extruderTemp),
                last.extruderTarget() > 0 ? last.extruderTemp() - last.extruderTarget() : 0.0,
                last.bedTemp() - first.bedTemp(),
                stdDev(samples, TelemetrySample::bedTemp),
                last.bedTarget() > 0 ? last.bedTemp() - last.bedTarget() : 0.0,
                powerSum / n,
                last.printDuration());
    }

    private static double stdDev(List<TelemetrySample> samples, java.util.function.ToDoubleFunction<TelemetrySample> f) {
        int n = samples.size();
        if (n < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (TelemetrySample s : samples) {
            mean += f.applyAsDouble(s);
        }
        mean /= n;

        double sumSq = 0.0;
        for (TelemetrySample s : samples) {
            double d = f.applyAsDouble(s) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / n);
    }

    /**
     * Whether this window carries enough samples to be worth reasoning about. Consumers should
     * fall back to their un-fused behaviour when this is false.
     */
    public boolean isReliable() {
        return sampleCount >= 2 && coverage >= 0.5;
    }

    public boolean isPrinting() {
        return "printing".equalsIgnoreCase(printState);
    }
}
