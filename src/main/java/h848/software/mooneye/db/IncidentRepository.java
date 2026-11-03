package h848.software.mooneye.db;

import h848.software.mooneye.model.Incident;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public final class IncidentRepository {

    /** Everything but the image, which is fetched on its own. */
    private static final String COLUMNS = "id, printer_id, job_id, ts, filename, level, action, score, "
            + "current_p, detections, ground_truth, reviewed_at";

    private final Jdbi jdbi;

    IncidentRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(Incident incident, byte[] image) {
        long id = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO incidents (printer_id, job_id, filename, level, action, score, current_p, detections, image)
                VALUES (:printerId, :jobId, :filename, :level, :action, :score, :currentP, :detections, :image)
                """)
                .bindBean(incident)
                .bind("image", image)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
        incident.setId(id);
        return id;
    }

    public List<Incident> findRecent(String printerId, int limit) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + COLUMNS
                        + " FROM incidents WHERE printer_id = :pid ORDER BY id DESC LIMIT :limit")
                .bind("pid", printerId)
                .bind("limit", limit)
                .mapToBean(Incident.class)
                .list());
    }

    public Optional<Incident> find(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + COLUMNS + " FROM incidents WHERE id = :id")
                .bind("id", id)
                .mapToBean(Incident.class)
                .findOne());
    }

    public Optional<byte[]> image(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT image FROM incidents WHERE id = :id")
                .bind("id", id)
                .mapTo(byte[].class)
                .findOne());
    }

    /** @return false if the incident does not exist */
    public boolean review(long id, String groundTruth) {
        return jdbi.withHandle(h -> h.createUpdate(
                        "UPDATE incidents SET ground_truth = :gt, reviewed_at = CURRENT_TIMESTAMP WHERE id = :id")
                .bind("gt", groundTruth)
                .bind("id", id)
                .execute() > 0);
    }
}
