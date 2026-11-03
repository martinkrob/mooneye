package h848.software.mooneye.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import h848.software.mooneye.detection.PredictionState;
import h848.software.mooneye.model.AppSettings;
import h848.software.mooneye.model.Incident;
import h848.software.mooneye.model.PrintJob;
import h848.software.mooneye.model.Printer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTest {

    private Database db;

    @BeforeEach
    void open() {
        db = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void close() {
        db.close();
    }

    static Printer printer(String id) {
        Printer p = new Printer();
        p.setId(id);
        p.setName("Voron");
        p.setMoonrakerUrl("192.168.1.50");
        p.setApiKey("secret-key");
        p.setMqttPassword("mqtt-secret");
        p.setRoiX(0.1);
        p.setRoiY(0.2);
        p.setRoiW(0.5);
        p.setRoiH(0.6);
        return p;
    }

    @Test
    void migrationsAreIdempotent() {
        // Opening the same database a second time must not try to re-create anything.
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        new Database(url).close();
        try (Database again = new Database(url)) {
            assertTrue(again.printers().findAll().isEmpty());
        }
    }

    @Test
    void settingsHaveDefaultsAndRoundTrip() {
        AppSettings s = db.settings().load();
        assertEquals(AppSettings.DEFAULT_RETENTION_PRINTS, s.getRetentionPrintCount());
        s.setRetentionPrintCount(5);
        s.setSnapshotIntervalSec(0);
        db.settings().save(s);
        AppSettings loaded = db.settings().load();
        assertEquals(5, loaded.getRetentionPrintCount());
        assertEquals(0, loaded.getSnapshotIntervalSec());
    }

    @Test
    void printerRoundTripsAndKeepsSecretsWhenUpdatedWithout() {
        db.printers().insert(printer("p1"));
        Printer loaded = db.printers().find("p1").orElseThrow();
        assertEquals("Voron", loaded.getName());
        assertEquals(Printer.FailureAction.PAUSE, loaded.getOnFailure());
        assertEquals(1.0, loaded.getSensitivity());
        assertNotNull(loaded.getRoi());
        assertNotNull(loaded.getCreatedAt());

        Printer update = printer("p1");
        update.setName("Voron 2.4");
        update.setApiKey(null);
        update.setMqttPassword(null);
        update.setOnFailure(Printer.FailureAction.NOTIFY);
        assertTrue(db.printers().update(update));

        Printer after = db.printers().find("p1").orElseThrow();
        assertEquals("Voron 2.4", after.getName());
        assertEquals("secret-key", after.getApiKey());
        assertEquals("mqtt-secret", after.getMqttPassword());
        assertEquals(Printer.FailureAction.NOTIFY, after.getOnFailure());
    }

    @Test
    void openJobIsFoundUntilItEnds() {
        db.printers().insert(printer("p1"));
        PrintJob job = new PrintJob();
        job.setPrinterId("p1");
        job.setFilename("benchy.gcode");
        job.setStartTime(Instant.now());
        job.setStatus(PrintJob.STATUS_PRINTING);
        db.jobs().insert(job);
        assertNotNull(job.getId());
        assertEquals(job.getId(), db.jobs().findOpen("p1").orElseThrow().getId());

        job.setEndTime(Instant.now());
        job.setStatus("complete");
        db.jobs().update(job);
        assertTrue(db.jobs().findOpen("p1").isEmpty());
    }

    @Test
    void predictionStateIsPersisted() {
        db.printers().insert(printer("p1"));
        db.predictions().save("p1", new PredictionState(3, 400, 0.2, 0.15, 0.1, 0.05));
        PredictionState s = db.predictions().find("p1").orElseThrow();
        assertEquals(400, s.getLifetimeFrameNum());
        assertEquals(0.05, s.getRollingMeanLong(), 1e-9);
        db.predictions().delete("p1");
        assertTrue(db.predictions().find("p1").isEmpty());
    }

    @Test
    void incidentReviewIsStored() {
        db.printers().insert(printer("p1"));
        Incident i = new Incident();
        i.setPrinterId("p1");
        i.setLevel(Incident.LEVEL_FAILURE);
        i.setAction(Incident.ACTION_PAUSED);
        i.setScore(0.8);
        i.setCurrentP(1.2);
        i.setDetections("[]");
        long id = db.incidents().insert(i, new byte[]{1, 2, 3});

        assertNull(db.incidents().find(id).orElseThrow().getGroundTruth());
        assertTrue(db.incidents().review(id, Incident.TRUE_POSITIVE));
        assertEquals(Incident.TRUE_POSITIVE, db.incidents().find(id).orElseThrow().getGroundTruth());
        assertEquals(3, db.incidents().image(id).orElseThrow().length);
        assertFalse(db.incidents().review(9999, Incident.TRUE_POSITIVE));
    }

    @Test
    void deletingAPrinterCascades() {
        db.printers().insert(printer("p1"));
        db.predictions().save("p1", new PredictionState());
        db.printers().delete("p1");
        assertTrue(db.predictions().find("p1").isEmpty());
    }
}
