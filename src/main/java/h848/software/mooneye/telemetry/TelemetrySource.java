package h848.software.mooneye.telemetry;

import h848.software.mooneye.model.Printer;

/**
 * Produces a continuous stream of {@link TelemetrySample}s for one or more printers.
 * <p>
 * The abstraction exists so the transport can be swapped without touching anything downstream:
 * {@link PollingTelemetrySource} asks Moonraker over HTTP, and a future WebSocket source can take
 * its place by implementing this interface and being handed to {@link TelemetryService}.
 */
public interface TelemetrySource extends AutoCloseable {

    /**
     * Begins (or restarts, if the printer's connection settings changed) sampling a printer.
     * Implementations must tolerate being called repeatedly for the same printer.
     */
    void start(Printer printer);

    /** Stops sampling a printer. A no-op if it was not being sampled. */
    void stop(String printerId);

    /** Whether samples are currently arriving for this printer. */
    boolean isHealthy(String printerId);

    /** Ids of every printer currently being sampled, so callers can reconcile against their own set. */
    java.util.Set<String> activePrinterIds();

    @Override
    void close();
}
