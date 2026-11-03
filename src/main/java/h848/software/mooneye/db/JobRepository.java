package h848.software.mooneye.db;

import h848.software.mooneye.model.PrintJob;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public final class JobRepository {

    private final Jdbi jdbi;

    JobRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** The print currently in progress or paused on this printer, if any. */
    public Optional<PrintJob> findOpen(String printerId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM print_jobs WHERE printer_id = :pid AND end_time IS NULL ORDER BY id DESC LIMIT 1")
                .bind("pid", printerId)
                .mapToBean(PrintJob.class)
                .findOne());
    }

    public Optional<PrintJob> find(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM print_jobs WHERE id = :id")
                .bind("id", id)
                .mapToBean(PrintJob.class)
                .findOne());
    }

    public List<PrintJob> findRecent(String printerId, int limit) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM print_jobs WHERE printer_id = :pid ORDER BY id DESC LIMIT :limit")
                .bind("pid", printerId)
                .bind("limit", limit)
                .mapToBean(PrintJob.class)
                .list());
    }

    public void insert(PrintJob job) {
        long id = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO print_jobs (printer_id, filename, start_time, status, duration_seconds, filament_used)
                VALUES (:printerId, :filename, :startTime, :status, :durationSeconds, :filamentUsed)
                """)
                .bindBean(job)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
        job.setId(id);
    }

    public void update(PrintJob job) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE print_jobs SET end_time = :endTime, status = :status, duration_seconds = :durationSeconds,
                    filament_used = :filamentUsed, ai_pauses = :aiPauses, alerts_muted = :alertsMuted
                WHERE id = :id
                """)
                .bindBean(job)
                .execute());
    }
}
