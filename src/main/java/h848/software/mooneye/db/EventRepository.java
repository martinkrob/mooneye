package h848.software.mooneye.db;

import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/**
 * Audit trail of what happened and who did it. {@code printer_id} has no foreign key on purpose:
 * the "printer deleted" event must be able to outlive the printer it describes.
 */
public final class EventRepository {

    public record Event(long id, String printerId, Instant ts, String eventType, String notes) {
    }

    private final Jdbi jdbi;

    EventRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void log(String printerId, String eventType, String notes) {
        String trimmed = notes != null && notes.length() > 2000 ? notes.substring(0, 2000) : notes;
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO events (printer_id, event_type, notes) VALUES (:pid, :type, :notes)")
                .bind("pid", printerId)
                .bind("type", eventType)
                .bind("notes", trimmed)
                .execute());
    }

    public List<Event> findRecent(int limit) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT id, printer_id, ts, event_type, notes FROM events ORDER BY id DESC LIMIT :limit")
                .bind("limit", limit)
                .map((rs, ctx) -> new Event(rs.getLong("id"), rs.getString("printer_id"),
                        rs.getObject("ts", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("event_type"), rs.getString("notes")))
                .list());
    }
}
