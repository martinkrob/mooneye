package h848.software.mooneye.telemetry;

import h848.software.mooneye.db.Database;
import h848.software.mooneye.model.Printer;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.moonraker.PrinterTelemetry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the continuous telemetry pipeline: keeps a {@link TelemetrySource} in step with the
 * printers configured in the database, caches each printer's latest full reading, and exposes the
 * resulting {@link TelemetryAggregator}.
 * <p>
 * Once running, this is the single place printer state is read from. The detection loop and the
 * web UI both consume the cache instead of querying Moonraker themselves, so printer load stays
 * constant no matter how many dashboards are open.
 */
public class TelemetryService implements TelemetryListener {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    /** The aggregation span the sensor-fusion design is built around. */
    public static final long DEFAULT_WINDOW_MS = 10_000;

    /** Used only to size the aggregator when collection is disabled. */
    private static final long DEFAULT_INTERVAL_MS = 1000;
    /** Safety net in case a CRUD path ever forgets to call {@link #sync()}. */
    private static final long RESYNC_INTERVAL_SEC = 30;

    private final Database db;
    private final TelemetryAggregator aggregator;
    private final TelemetrySource source;
    private final ScheduledExecutorService syncScheduler;
    private final long intervalMs;
    private final boolean enabled;

    /** Latest full reading per printer, served to the UI and the detection loop. */
    private final Map<String, PrinterTelemetry> latestFull = new ConcurrentHashMap<>();

    /**
     * @param intervalMs sampling period; 0 disables continuous collection, in which case callers
     *                   fall back to querying Moonraker directly
     */
    public TelemetryService(Database db, MoonrakerClient moonrakerClient, long intervalMs) {
        this.db = db;
        this.intervalMs = intervalMs;
        this.enabled = intervalMs > 0;
        this.aggregator = new TelemetryAggregator(enabled ? intervalMs : DEFAULT_INTERVAL_MS);
        this.source = enabled
                ? new PollingTelemetrySource(moonrakerClient, this, intervalMs)
                : null;
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-sync");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!enabled) {
            logger.info("Continuous telemetry collection is disabled (MOONEYE_TELEMETRY_INTERVAL_MS=0).");
            return;
        }
        syncScheduler.scheduleAtFixedRate(this::syncQuietly, 0, RESYNC_INTERVAL_SEC, TimeUnit.SECONDS);
        logger.info("TelemetryService started. Sampling every {} ms, {} ms aggregation window.",
                intervalMs, DEFAULT_WINDOW_MS);
    }

    /**
     * Brings the set of polled printers in line with the database. Call after any printer is
     * added, changed or removed; it is cheap (one query) and idempotent.
     */
    public void sync() {
        if (!enabled) {
            return;
        }
        List<Printer> printers = db.printers().findAll();
        Set<String> desired = new HashSet<>();

        for (Printer printer : printers) {
            if (printer.isEnabled()) {
                desired.add(printer.getId());
                source.start(printer); // no-op when already polling with the same settings
            }
        }

        // Reconcile against what the source is actually doing rather than against the printer list,
        // so a printer that was disabled AND one that was deleted are both cleaned up here.
        for (String activeId : source.activePrinterIds()) {
            if (!desired.contains(activeId)) {
                forget(activeId);
            }
        }
    }

    /** Immediately drops a deleted printer, rather than waiting for the next {@link #sync()}. */
    public void remove(String printerId) {
        if (!enabled) {
            return;
        }
        forget(printerId);
    }

    private void forget(String printerId) {
        source.stop(printerId);
        aggregator.forget(printerId);
        latestFull.remove(printerId);
    }

    @Override
    public void onReading(String printerId, long ts, PrinterTelemetry telemetry) {
        latestFull.put(printerId, telemetry);
        aggregator.accept(printerId, TelemetrySample.from(ts, telemetry));
    }

    @Override
    public void onUnreachable(String printerId, String reason) {
        // Replace rather than keep the last good reading: presenting a ten-minute-old bed
        // temperature as if it were current is worse than showing nothing. This mirrors what a
        // failed direct query used to return, so consumers need no special case.
        PrinterTelemetry offline = new PrinterTelemetry();
        offline.setKlipperState("offline");
        offline.setKlipperMessage(reason);
        latestFull.put(printerId, offline);
    }

    /**
     * The printer's latest full reading, or empty if none has arrived yet.
     * <p>
     * Note this is a cache: it is at most one sampling interval old, and it reports
     * {@code klipperState=offline} rather than stale values once a printer stops responding.
     */
    public Optional<PrinterTelemetry> latestFull(String printerId) {
        return Optional.ofNullable(latestFull.get(printerId));
    }

    /** Whether this printer is currently being sampled, i.e. whether the cache is being kept fresh. */
    public boolean isSampling(String printerId) {
        return enabled && source.activePrinterIds().contains(printerId);
    }

    /** The 10 s window leading up to {@code endTs}, typically the instant a camera frame was taken. */
    public Optional<TelemetryWindow> windowEndingAt(String printerId, long endTs) {
        return aggregator.windowEndingAt(printerId, endTs, DEFAULT_WINDOW_MS);
    }

    public Optional<TelemetrySample> latest(String printerId) {
        return aggregator.latest(printerId);
    }

    public boolean isHealthy(String printerId) {
        return enabled && source.isHealthy(printerId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public TelemetryAggregator getAggregator() {
        return aggregator;
    }

    public void stop() {
        syncScheduler.shutdownNow();
        if (source != null) {
            source.close();
        }
        logger.info("TelemetryService stopped.");
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (Exception e) {
            logger.error("Error while synchronising telemetry sources", e);
        }
    }
}
