package h848.software.mooneye.telemetry;

import h848.software.mooneye.moonraker.PrinterTelemetry;

/**
 * Receives readings from a {@link TelemetrySource}.
 * <p>
 * The source reports raw readings and reachability; deciding what to cache, aggregate or discard
 * is the listener's business. That split is what lets a WebSocket source drop in later: it maps
 * pushed status updates onto {@link #onReading} and a dropped connection onto {@link #onUnreachable}.
 */
public interface TelemetryListener {

    /**
     * A successful reading.
     *
     * @param ts        when the reading was taken, not when it is handled
     * @param telemetry a fresh instance owned by the listener from this point on
     */
    void onReading(String printerId, long ts, PrinterTelemetry telemetry);

    /**
     * The printer has been unreachable long enough that its last reading should no longer be
     * presented as current. Called once per outage, not once per failed attempt.
     */
    void onUnreachable(String printerId, String reason);
}
