package h848.software.mooneye.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import h848.software.mooneye.FakeMoonraker;
import h848.software.mooneye.MoonEye;
import h848.software.mooneye.alerts.Notifier;
import h848.software.mooneye.camera.CameraClient;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.detection.Detection;
import h848.software.mooneye.detection.FailureDetector;
import h848.software.mooneye.detection.FrameDetector;
import h848.software.mooneye.detection.ModelStore;
import h848.software.mooneye.detection.PredictionParams;
import h848.software.mooneye.model.AppSettings;
import h848.software.mooneye.model.Incident;
import h848.software.mooneye.model.PrintJob;
import h848.software.mooneye.model.Printer;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.telemetry.TelemetryService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the monitor one cycle at a time against a fake Moonraker and a fake camera, with a
 * scripted detector standing in for the model.
 */
class PrintMonitorTest {

    @TempDir
    Path dir;

    private FakeMoonraker moonraker;
    private HttpServer cameraServer;
    private Database db;
    private PrintMonitor monitor;
    private Printer printer;
    private AppSettings settings;
    private final ScriptedDetector detector = new ScriptedDetector();

    /** Returns whatever confidences the test sets, as boxes. */
    static final class ScriptedDetector implements FrameDetector {
        volatile float[] confidences = new float[0];

        @Override
        public FailureDetector.Result detect(BufferedImage frame, h848.software.mooneye.model.Roi roi) {
            List<Detection> boxes = new java.util.ArrayList<>();
            for (int i = 0; i < confidences.length; i++) {
                boxes.add(new Detection(confidences[i], 0.1f * i, 0.1f, 0.1f * i + 0.08f, 0.3f));
            }
            return new FailureDetector.Result(boxes, 5);
        }

        @Override
        public void close() {
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        moonraker = new FakeMoonraker();

        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpeg);
        byte[] frame = jpeg.toByteArray();
        cameraServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cameraServer.createContext("/snapshot", ex -> {
            ex.sendResponseHeaders(200, frame.length);
            ex.getResponseBody().write(frame);
            ex.close();
        });
        cameraServer.start();

        db = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        MoonrakerClient client = new MoonrakerClient();
        TelemetryService telemetry = new TelemetryService(db, client, 0); // direct queries
        monitor = new PrintMonitor(db, telemetry, client, new CameraClient(),
                new ModelStore(dir.resolve("none.onnx"), null, null),
                new Notifier(MoonEye.jsonMapper()), new SnapshotStore(dir.resolve("snapshots")),
                MoonEye.jsonMapper());
        monitor.useDetector(detector);

        printer = new Printer();
        printer.setId("p1");
        printer.setName("Test");
        printer.setMoonrakerUrl(moonraker.url());
        printer.setWebcamUrl("http://127.0.0.1:" + cameraServer.getAddress().getPort() + "/snapshot");
        db.printers().insert(printer);
        printer = db.printers().find("p1").orElseThrow();
        settings = db.settings().load();
    }

    @AfterEach
    void tearDown() {
        monitor.stop();
        cameraServer.stop(0);
        moonraker.close();
        db.close();
    }

    private void cycles(int n) {
        for (int i = 0; i < n; i++) {
            monitor.check(printer, settings);
            moonraker.printDuration += PredictionParams.CYCLE_SECONDS;
        }
    }

    private PrintJob openJob() {
        return db.jobs().findOpen("p1").orElse(null);
    }

    @Test
    void aPauseAndResumeStayOneJob() {
        moonraker.printing("benchy.gcode", 10);
        cycles(1);
        PrintJob job = openJob();
        assertNotNull(job);

        moonraker.printState = "paused";
        cycles(1);
        assertEquals(job.getId(), openJob().getId());
        assertEquals(PrintJob.STATUS_PAUSED, openJob().getStatus());

        moonraker.printState = "printing";
        cycles(1);
        assertEquals(job.getId(), openJob().getId());

        moonraker.printState = "complete";
        cycles(1);
        assertTrue(db.jobs().findOpen("p1").isEmpty());
        PrintJob done = db.jobs().find(job.getId()).orElseThrow();
        assertEquals("complete", done.getStatus());
        assertNotNull(done.getEndTime());
    }

    @Test
    void aRestartedPrintIsANewJob() {
        moonraker.printing("benchy.gcode", 500);
        cycles(1);
        long first = openJob().getId();

        moonraker.printDuration = 3; // same file, duration rewound: printed again
        cycles(1);
        long second = openJob().getId();
        assertNotEquals(first, second);
        assertEquals("interrupted", db.jobs().find(first).orElseThrow().getStatus());
    }

    @Test
    void anUnreachablePrinterLeavesTheJobAlone() {
        moonraker.printing("benchy.gcode", 10);
        cycles(1);
        long id = openJob().getId();

        printer.setMoonrakerUrl("http://127.0.0.1:1");
        cycles(1);
        assertEquals(id, openJob().getId());
    }

    @Test
    void aCleanPrintIsWatchedAndLoggedWithoutAlarms() {
        moonraker.printing("benchy.gcode", 10);
        cycles(40);
        assertTrue(monitor.status(printer).watching());
        assertEquals(40, db.detectionLog().findForJob(openJob().getId()).size());
        assertTrue(db.incidents().findRecent("p1", 10).isEmpty());
        assertTrue(moonraker.gcode.isEmpty());
    }

    @Test
    void aFailureWarnsThenPausesAndResumeRestartsWarmUp() {
        moonraker.printing("benchy.gcode", 10);
        cycles(60); // ten minutes of a clean print

        detector.confidences = new float[]{0.8f, 0.7f};
        for (int i = 0; i < 30 && moonraker.gcode.isEmpty(); i++) {
            cycles(1);
        }
        assertEquals(1, moonraker.gcode.size(), "exactly one PAUSE");
        assertTrue(moonraker.gcode.getFirst().contains("PAUSE"));

        List<Incident> incidents = db.incidents().findRecent("p1", 10);
        assertEquals(2, incidents.size());
        assertEquals(Incident.LEVEL_FAILURE, incidents.get(0).getLevel());
        assertEquals(Incident.ACTION_PAUSED, incidents.get(0).getAction());
        assertEquals(Incident.LEVEL_WARNING, incidents.get(1).getLevel());
        assertEquals(1, openJob().getAiPauses());

        // The printer acknowledges the pause; the operator looks, clears it and resumes.
        moonraker.printState = "paused";
        cycles(1);
        moonraker.printState = "printing";
        cycles(1);
        assertTrue(monitor.status(printer).warmupSeconds() > 0, "warm-up restarted after resume");
        assertEquals(1, moonraker.gcode.size(), "no immediate second pause");
        assertEquals(1, db.jobs().findRecent("p1", 10).size(), "still one job for one print");
    }

    @Test
    void notifyModeNeverPauses() {
        printer.setOnFailure(Printer.FailureAction.NOTIFY);
        moonraker.printing("benchy.gcode", 10);
        cycles(60);
        detector.confidences = new float[]{0.8f, 0.7f};
        cycles(30);
        assertTrue(moonraker.gcode.isEmpty());
        List<Incident> incidents = db.incidents().findRecent("p1", 10);
        assertEquals(2, incidents.size(), "one warning and one failure, not one per frame");
        assertTrue(incidents.stream().allMatch(i -> Incident.ACTION_NOTIFIED.equals(i.getAction())));
    }

    @Test
    void mutedPrintStaysQuiet() {
        moonraker.printing("benchy.gcode", 10);
        cycles(60);
        assertTrue(monitor.muteCurrentPrint("p1"));
        detector.confidences = new float[]{0.8f, 0.7f};
        cycles(30);
        assertTrue(moonraker.gcode.isEmpty());
        assertTrue(db.incidents().findRecent("p1", 10).isEmpty());
    }

    @Test
    void nothingIsJudgedWhilePaused() {
        moonraker.printing("benchy.gcode", 10);
        cycles(5);
        int logged = db.detectionLog().findForJob(openJob().getId()).size();
        moonraker.printState = "paused";
        cycles(5);
        assertEquals(logged, db.detectionLog().findForJob(openJob().getId()).size());
        assertFalse(monitor.status(printer).watching());
    }
}
