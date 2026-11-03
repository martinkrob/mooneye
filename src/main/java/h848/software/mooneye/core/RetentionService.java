package h848.software.mooneye.core;

import h848.software.mooneye.db.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps everything belonging to the most recent N prints of each printer and drops the rest.
 * <p>
 * One knob instead of separate row caps: what a user wants to keep is "the last few prints", and
 * a print's detection log, incidents and snapshots only make sense together. The cut is made in
 * time, at the start of the oldest print being kept. Incidents carrying a human verdict survive
 * regardless - they are labelled data.
 */
public final class RetentionService {

    private static final Logger logger = LoggerFactory.getLogger(RetentionService.class);

    /** The event log is an audit trail, not data anyone analyses - a fixed cap is enough. */
    private static final int MAX_EVENTS = 5000;

    private final Database db;
    private final Path snapshotsDir;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "retention");
        t.setDaemon(true);
        return t;
    });

    public RetentionService(Database db, Path snapshotsDir) {
        this.db = db;
        this.snapshotsDir = snapshotsDir;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::purgeQuietly, 1, 60, TimeUnit.MINUTES);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void purgeQuietly() {
        try {
            purge();
        } catch (Exception e) {
            logger.error("Retention purge failed", e);
        }
    }

    /** What a purge removed, for the log and for tests. */
    public record Result(int jobs, int detectionRows, int incidents) {
    }

    public Result purge() {
        int keep = db.settings().load().getRetentionPrintCount();
        int jobs = 0;
        int rows = 0;
        int incidents = 0;

        List<String> printerIds = db.jdbi().withHandle(h ->
                h.createQuery("SELECT id FROM printers").mapTo(String.class).list());

        for (String pid : printerIds) {
            Instant cutoff = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT start_time FROM print_jobs WHERE printer_id = :pid ORDER BY id DESC LIMIT 1 OFFSET :skip")
                    .bind("pid", pid)
                    .bind("skip", keep - 1)
                    .mapTo(Instant.class)
                    .findOne()
                    .orElse(null));
            if (cutoff == null) {
                continue; // fewer prints than the limit
            }

            List<Long> doomed = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT id FROM print_jobs WHERE printer_id = :pid AND start_time < :cutoff AND end_time IS NOT NULL")
                    .bind("pid", pid).bind("cutoff", cutoff)
                    .mapTo(Long.class).list());
            if (doomed.isEmpty()) {
                continue;
            }

            int[] counts = db.jdbi().inTransaction(h -> new int[]{
                    h.createUpdate("DELETE FROM detection_log WHERE printer_id = :pid AND ts < :cutoff")
                            .bind("pid", pid).bind("cutoff", cutoff).execute(),
                    h.createUpdate("DELETE FROM incidents WHERE printer_id = :pid AND ts < :cutoff AND ground_truth IS NULL")
                            .bind("pid", pid).bind("cutoff", cutoff).execute(),
                    h.createUpdate("DELETE FROM print_jobs WHERE id IN (<ids>)")
                            .bindList("ids", doomed).execute()
            });
            rows += counts[0];
            incidents += counts[1];
            jobs += counts[2];

            for (Long jobId : doomed) {
                deleteRecursively(snapshotsDir.resolve(pid).resolve(String.valueOf(jobId)));
            }
        }

        db.jdbi().useHandle(h -> h.createUpdate(
                        "DELETE FROM events WHERE id <= (SELECT COALESCE(MAX(id), 0) - :cap FROM events)")
                .bind("cap", MAX_EVENTS)
                .execute());

        if (jobs > 0) {
            logger.info("Retention: kept the last {} prints per printer; removed {} prints, {} detection rows, {} incidents.",
                    keep, jobs, rows, incidents);
        }
        return new Result(jobs, rows, incidents);
    }

    /** Removes a printer's whole snapshot folder after the printer itself was deleted. */
    public void deletePrinterSnapshots(String printerId) {
        deleteRecursively(snapshotsDir.resolve(printerId));
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    logger.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.warn("Could not clean up {}: {}", dir, e.getMessage());
        }
    }
}
