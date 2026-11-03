package h848.software.mooneye.telemetry;

import h848.software.mooneye.model.Printer;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.moonraker.PrinterTelemetry;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples printers by polling Moonraker's object query endpoint over HTTP.
 * <p>
 * Threading mirrors {@code DetectionService}: a small scheduler only fires ticks, the blocking
 * HTTP call happens on a worker pool, and an in-flight guard means a printer that is timing out
 * occupies exactly one thread and silently drops its own overlapping ticks. Those dropped ticks
 * are not hidden - they show up as reduced {@link TelemetryWindow#coverage()} downstream.
 */
public final class PollingTelemetrySource implements TelemetrySource {

    private static final Logger logger = LoggerFactory.getLogger(PollingTelemetrySource.class);

    /**
     * How many consecutive failures before a printer is declared unreachable. At 1 Hz a single
     * dropped request is a blip, not an outage, and flipping the UI to OFFLINE over one is noise.
     */
    private static final int OFFLINE_AFTER_FAILURES = 3;

    /** The Klipper host state changes rarely, so it is refreshed on this cadence, not every tick. */
    private static final long INFO_REFRESH_MS = 10_000;

    private final MoonrakerClient moonrakerClient;
    private final TelemetryListener listener;
    private final long intervalMs;
    private final int infoEveryNTicks;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, PolledPrinter> polled = new ConcurrentHashMap<>();

    public PollingTelemetrySource(MoonrakerClient moonrakerClient,
                                  TelemetryListener listener,
                                  long intervalMs) {
        this.moonrakerClient = moonrakerClient;
        this.listener = listener;
        this.intervalMs = intervalMs;
        this.infoEveryNTicks = (int) Math.max(1, INFO_REFRESH_MS / intervalMs);
        this.scheduler = Executors.newScheduledThreadPool(2, daemonFactory("telemetry-tick-"));
        // Bounded in practice by inFlight: at most one live task per printer.
        this.workers = Executors.newCachedThreadPool(daemonFactory("telemetry-poll-"));
    }

    @Override
    public void start(Printer printer) {
        String id = printer.getId();
        String signature = connectionSignature(printer);

        PolledPrinter existing = polled.get(id);
        if (existing != null) {
            if (Objects.equals(existing.signature, signature)) {
                return; // already polling with the same connection settings
            }
            stop(id); // hostname or API key changed - restart against the new endpoint
        }

        PolledPrinter state = new PolledPrinter(printer, signature);
        state.task = scheduler.scheduleAtFixedRate(
                () -> tick(state), 0, intervalMs, TimeUnit.MILLISECONDS);
        polled.put(id, state);
        logger.info("Telemetry polling started for printer {} every {} ms", printer.getName(), intervalMs);
    }

    @Override
    public void stop(String printerId) {
        PolledPrinter state = polled.remove(printerId);
        if (state != null) {
            state.task.cancel(false);
            logger.info("Telemetry polling stopped for printer {}", state.printer.getName());
        }
    }

    @Override
    public Set<String> activePrinterIds() {
        return Set.copyOf(polled.keySet());
    }

    @Override
    public boolean isHealthy(String printerId) {
        PolledPrinter state = polled.get(printerId);
        return state != null
                && state.lastOkTs > 0
                && System.currentTimeMillis() - state.lastOkTs < 3 * intervalMs;
    }

    @Override
    public void close() {
        polled.values().forEach(s -> s.task.cancel(false));
        polled.clear();
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    private void tick(PolledPrinter state) {
        String id = state.printer.getId();
        if (!inFlight.add(id)) {
            return; // previous poll still running
        }
        workers.submit(() -> {
            try {
                poll(state);
            } catch (Exception e) {
                logger.error("Unhandled error polling telemetry for {}", state.printer.getName(), e);
            } finally {
                inFlight.remove(id);
            }
        });
    }

    private void poll(PolledPrinter state) {
        PrinterTelemetry telemetry = moonrakerClient.queryObjects(state.printer);
        long now = System.currentTimeMillis();

        if (telemetry == null) {
            state.consecutiveFailures++;
            // Report the outage once, on the tick that crosses the threshold. Logging or notifying
            // on every attempt would mean once per second for as long as the printer stays down.
            if (state.consecutiveFailures == OFFLINE_AFTER_FAILURES) {
                logger.warn("Printer {} unreachable after {} consecutive telemetry polls. "
                        + "Further failures are logged at DEBUG until it recovers.",
                        state.printer.getName(), OFFLINE_AFTER_FAILURES);
                state.klipperState = null; // force an info refresh once it comes back
                listener.onUnreachable(state.printer.getId(), "Telemetry polling timed out");
            }
            return;
        }

        if (state.consecutiveFailures >= OFFLINE_AFTER_FAILURES) {
            logger.info("Printer {} telemetry recovered", state.printer.getName());
        }
        state.consecutiveFailures = 0;
        state.lastOkTs = now;

        // The object query cannot report the Klipper host state, so carry it across ticks and
        // refresh it on its own slower cadence.
        if (state.klipperState == null || state.tickCounter % infoEveryNTicks == 0) {
            if (moonrakerClient.queryKlipperInfo(state.printer, telemetry)) {
                state.klipperState = telemetry.getKlipperState();
                state.klipperMessage = telemetry.getKlipperMessage();
            }
        }
        telemetry.setKlipperState(state.klipperState);
        telemetry.setKlipperMessage(state.klipperMessage);
        state.tickCounter++;

        listener.onReading(state.printer.getId(), now, telemetry);
    }

    /** Everything that decides where and how we connect; a change here forces a restart. */
    private static String connectionSignature(Printer printer) {
        return printer.getMoonrakerUrl() + "|" + printer.getApiKey();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Mutable per-printer polling state. Everything except {@code lastOkTs} is touched only from
     * the poll worker, and the in-flight guard means at most one worker per printer at a time.
     */
    private static final class PolledPrinter {
        final Printer printer;
        final String signature;
        ScheduledFuture<?> task;
        volatile long lastOkTs;
        int consecutiveFailures;
        int tickCounter;
        String klipperState;
        String klipperMessage;

        PolledPrinter(Printer printer, String signature) {
            this.printer = printer;
            this.signature = signature;
        }
    }
}
