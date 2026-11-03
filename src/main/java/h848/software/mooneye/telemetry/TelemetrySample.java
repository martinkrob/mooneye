package h848.software.mooneye.telemetry;

import h848.software.mooneye.moonraker.PrinterTelemetry;

/**
 * One immutable reading of a printer's state at a point in time.
 * <p>
 * A stream of these is what {@link TelemetryAggregator} folds into a {@link TelemetryWindow}.
 * Deliberately a flat record of primitives: samples are produced ~1/s per printer and read from
 * other threads, so they must be cheap and safe to share.
 */
public record TelemetrySample(
        long ts,
        String printState,
        // motion_report.live_position - where the toolhead actually is, not where it is planned to be.
        double x,
        double y,
        double z,
        double velocity,
        // print_stats.filament_used: cumulative per print, so a window difference is meaningful.
        double filamentUsed,
        double extruderTemp,
        double extruderTarget,
        double extruderPower,
        double bedTemp,
        double bedTarget,
        double progress,
        double printDuration) {

    public static TelemetrySample from(long ts, PrinterTelemetry t) {
        return new TelemetrySample(
                ts,
                t.getPrintState(),
                t.getLiveX(),
                t.getLiveY(),
                t.getLiveZ(),
                t.getPrintSpeed(),
                t.getFilamentUsed(),
                t.getExtruderTemp(),
                t.getExtruderTarget(),
                t.getExtruderPower(),
                t.getBedTemp(),
                t.getBedTarget(),
                t.getProgress(),
                t.getPrintDuration());
    }

    public boolean isPrinting() {
        return "printing".equalsIgnoreCase(printState);
    }
}
