package h848.software.mooneye.db;

import h848.software.mooneye.detection.PredictionState;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/** Persists each printer's {@link PredictionState}, mainly so the long-term mean survives restarts. */
public final class PredictionRepository {

    private final Jdbi jdbi;

    PredictionRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public Optional<PredictionState> find(String printerId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM predictions WHERE printer_id = :pid")
                .bind("pid", printerId)
                .map((rs, ctx) -> new PredictionState(
                        rs.getInt("current_frame_num"),
                        rs.getInt("lifetime_frame_num"),
                        rs.getDouble("current_p"),
                        rs.getDouble("ewm_mean"),
                        rs.getDouble("rolling_mean_short"),
                        rs.getDouble("rolling_mean_long")))
                .findOne());
    }

    public void save(String printerId, PredictionState s) {
        jdbi.useHandle(h -> h.createUpdate("""
                MERGE INTO predictions (printer_id, current_frame_num, lifetime_frame_num, current_p,
                    ewm_mean, rolling_mean_short, rolling_mean_long, updated_at)
                KEY (printer_id)
                VALUES (:pid, :currentFrameNum, :lifetimeFrameNum, :currentP,
                    :ewmMean, :rollingMeanShort, :rollingMeanLong, CURRENT_TIMESTAMP)
                """)
                .bind("pid", printerId)
                .bindBean(s)
                .execute());
    }

    /** Used when the camera or its view changed so much that the old "normal" no longer applies. */
    public void delete(String printerId) {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM predictions WHERE printer_id = :pid")
                .bind("pid", printerId)
                .execute());
    }
}
