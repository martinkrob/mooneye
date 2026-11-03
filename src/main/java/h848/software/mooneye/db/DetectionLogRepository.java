package h848.software.mooneye.db;

import h848.software.mooneye.model.DetectionLogEntry;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

public final class DetectionLogRepository {

    /** Without the telemetry window, which is large and only needed for offline analysis. */
    private static final String COLUMNS = "id, printer_id, job_id, ts, current_p, ewm_mean, "
            + "rolling_mean_short, rolling_mean_long, score, decision, frame_num, detections, "
            + "extruder_temp, bed_temp, progress, inference_ms";

    private final Jdbi jdbi;

    DetectionLogRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void insert(DetectionLogEntry e) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO detection_log (printer_id, job_id, current_p, ewm_mean, rolling_mean_short,
                    rolling_mean_long, score, decision, frame_num, detections, extruder_temp, bed_temp,
                    progress, telemetry_window, inference_ms)
                VALUES (:printerId, :jobId, :currentP, :ewmMean, :rollingMeanShort,
                    :rollingMeanLong, :score, :decision, :frameNum, :detections, :extruderTemp, :bedTemp,
                    :progress, :telemetryWindow, :inferenceMs)
                """)
                .bindBean(e)
                .execute());
    }

    /** Oldest first, ready to plot. */
    public List<DetectionLogEntry> findForJob(long jobId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + COLUMNS
                        + " FROM detection_log WHERE job_id = :job ORDER BY id")
                .bind("job", jobId)
                .mapToBean(DetectionLogEntry.class)
                .list());
    }

    /** The most recent entries for a printer, oldest first. */
    public List<DetectionLogEntry> findRecent(String printerId, int limit) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM (SELECT " + COLUMNS
                        + " FROM detection_log WHERE printer_id = :pid ORDER BY id DESC LIMIT :limit) ORDER BY id")
                .bind("pid", printerId)
                .bind("limit", limit)
                .mapToBean(DetectionLogEntry.class)
                .list());
    }
}
