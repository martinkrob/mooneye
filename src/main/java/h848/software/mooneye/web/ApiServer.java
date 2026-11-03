package h848.software.mooneye.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import h848.software.mooneye.alerts.Notifier;
import h848.software.mooneye.camera.CameraClient;
import h848.software.mooneye.core.PrintMonitor;
import h848.software.mooneye.core.RetentionService;
import h848.software.mooneye.core.SnapshotStore;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.detection.DetectionStatus;
import h848.software.mooneye.model.AppSettings;
import h848.software.mooneye.model.Incident;
import h848.software.mooneye.model.PrintJob;
import h848.software.mooneye.model.Printer;
import h848.software.mooneye.model.Roi;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.moonraker.PrinterTelemetry;
import h848.software.mooneye.telemetry.TelemetryService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The REST API and the static web UI, served by Javalin's embedded Jetty on a single port.
 * <p>
 * There is no authentication: MoonEye is meant for a home network, like Mainsail and Fluidd.
 * If it ever has to be reachable from outside, put it behind a reverse proxy that adds a login.
 */
public final class ApiServer {

    private static final Logger logger = LoggerFactory.getLogger(ApiServer.class);

    private final Database db;
    private final TelemetryService telemetry;
    private final MoonrakerClient moonraker;
    private final CameraClient camera;
    private final PrintMonitor monitor;
    private final RetentionService retention;
    private final SnapshotStore snapshots;
    private final Notifier notifier;
    private final String version;
    private final Javalin app;

    public ApiServer(Database db, TelemetryService telemetry, MoonrakerClient moonraker, CameraClient camera,
                     PrintMonitor monitor, RetentionService retention, SnapshotStore snapshots,
                     Notifier notifier, ObjectMapper json, String version) {
        this.db = db;
        this.telemetry = telemetry;
        this.moonraker = moonraker;
        this.camera = camera;
        this.monitor = monitor;
        this.retention = retention;
        this.snapshots = snapshots;
        this.notifier = notifier;
        this.version = version;

        this.app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.requestLogger.http((ctx, ms) -> {
                if (ctx.status().getCode() >= 400) {
                    logger.info("{} {} -> {} ({} ms)", ctx.method(), ctx.path(), ctx.status().getCode(), ms.intValue());
                }
            });

            config.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(400).json(Map.of("error", e.getMessage())));

            // --- System ---
            config.routes.get("/api/health", ctx -> {
                Map<String, Object> health = new java.util.LinkedHashMap<>();
                health.put("status", "UP");
                health.put("version", version);
                health.put("modelReady", monitor.isModelReady());
                health.put("modelError", monitor.modelError());
                ctx.json(health);
            });

            // --- Settings ---
            config.routes.get("/api/settings", ctx -> ctx.json(db.settings().load()));
            config.routes.put("/api/settings", ctx -> {
                db.settings().save(ctx.bodyAsClass(AppSettings.class));
                ctx.json(db.settings().load());
            });
            config.routes.get("/api/events", ctx -> ctx.json(db.events().findRecent(limit(ctx, 100, 1000))));

            // --- Connection tests for the "add printer" form (nothing is saved) ---
            config.routes.post("/api/test/moonraker", this::testMoonraker);
            config.routes.post("/api/test/camera", ctx -> {
                Printer p = ctx.bodyAsClass(Printer.class);
                sendJpeg(ctx, fetchCamera(p.getWebcamUrl()));
            });
            config.routes.post("/api/test/notification", ctx ->
                    ctx.json(Map.of("results", notifier.test(withStoredSecrets(ctx.bodyAsClass(Printer.class))))));

            // --- Printers ---
            config.routes.get("/api/printers", ctx -> ctx.json(db.printers().findAll()));
            config.routes.post("/api/printers", this::createPrinter);
            config.routes.get("/api/printers/{id}", ctx -> ctx.json(printer(ctx)));
            config.routes.put("/api/printers/{id}", this::updatePrinter);
            config.routes.delete("/api/printers/{id}", this::deletePrinter);

            // --- Live state ---
            config.routes.get("/api/printers/{id}/status", this::status);
            config.routes.get("/api/printers/{id}/frame", ctx -> sendJpeg(ctx,
                    monitor.lastFrame(printer(ctx).getId()).orElseThrow(() -> new NotFoundResponse("No frame analysed yet"))));
            config.routes.get("/api/printers/{id}/camera", ctx -> sendJpeg(ctx, fetchCamera(printer(ctx).getWebcamUrl())));
            config.routes.post("/api/printers/{id}/analyse", this::analyseNow);
            config.routes.get("/api/printers/{id}/telemetry/window", ctx -> {
                Printer p = printer(ctx);
                ctx.json(telemetry.windowEndingAt(p.getId(), System.currentTimeMillis())
                        .orElseThrow(() -> new NotFoundResponse("No telemetry samples yet")));
            });

            // --- Operator actions ---
            config.routes.post("/api/printers/{id}/mute", ctx -> {
                if (!monitor.muteCurrentPrint(printer(ctx).getId())) {
                    throw new NotFoundResponse("Nothing is printing");
                }
                ctx.status(204);
            });
            config.routes.post("/api/printers/{id}/reset-baseline", ctx -> {
                monitor.resetPrediction(printer(ctx).getId());
                ctx.status(204);
            });
            config.routes.post("/api/printers/{id}/test-notification", ctx ->
                    ctx.json(Map.of("results", notifier.test(printer(ctx)))));

            // --- History ---
            config.routes.get("/api/printers/{id}/jobs", ctx ->
                    ctx.json(db.jobs().findRecent(printer(ctx).getId(), limit(ctx, 50, 500))));
            config.routes.get("/api/printers/{id}/jobs/{jobId}/detections", ctx ->
                    ctx.json(db.detectionLog().findForJob(job(ctx).getId())));
            config.routes.get("/api/printers/{id}/jobs/{jobId}/snapshots", ctx -> {
                PrintJob job = job(ctx);
                ctx.json(snapshots.list(job.getPrinterId(), job.getId()));
            });
            config.routes.get("/api/printers/{id}/jobs/{jobId}/snapshots/{file}", ctx -> {
                PrintJob job = job(ctx);
                sendJpeg(ctx, snapshots.read(job.getPrinterId(), job.getId(), ctx.pathParam("file"))
                        .orElseThrow(() -> new NotFoundResponse("Snapshot not found")));
            });
            config.routes.get("/api/printers/{id}/detections", ctx ->
                    ctx.json(db.detectionLog().findRecent(printer(ctx).getId(), limit(ctx, 360, 8640))));
            config.routes.get("/api/printers/{id}/incidents", ctx ->
                    ctx.json(db.incidents().findRecent(printer(ctx).getId(), limit(ctx, 50, 500))));
            config.routes.get("/api/incidents/{incidentId}/image", ctx -> sendJpeg(ctx,
                    db.incidents().image(longParam(ctx, "incidentId"))
                            .orElseThrow(() -> new NotFoundResponse("Incident not found"))));
            config.routes.post("/api/incidents/{incidentId}/review", this::reviewIncident);
        });
    }

    public void start(int port) {
        app.start(port);
        logger.info("MoonEye {} listening on port {}", version, app.port());
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        app.stop();
    }

    // ------------------------------------------------------------------------------------------
    // Printers
    // ------------------------------------------------------------------------------------------

    private void createPrinter(Context ctx) {
        Printer p = withDefaults(ctx.bodyAsClass(Printer.class));
        p.setId(UUID.randomUUID().toString());
        validate(p);
        db.printers().insert(p);
        telemetry.sync();
        db.events().log(p.getId(), "PRINTER_ADDED", p.getName());
        ctx.status(201).json(db.printers().find(p.getId()).orElseThrow());
    }

    private void updatePrinter(Context ctx) {
        Printer before = printer(ctx);
        Printer p = withDefaults(ctx.bodyAsClass(Printer.class));
        p.setId(before.getId());
        validate(p);
        db.printers().update(p);
        Printer after = db.printers().find(p.getId()).orElseThrow();
        monitor.onPrinterUpdated(before, after);
        telemetry.sync();
        db.events().log(p.getId(), "PRINTER_UPDATED", p.getName());
        ctx.json(after);
    }

    private void deletePrinter(Context ctx) {
        Printer p = printer(ctx);
        db.printers().delete(p.getId());
        telemetry.remove(p.getId());
        monitor.onPrinterDeleted(p.getId());
        retention.deletePrinterSnapshots(p.getId());
        db.events().log(null, "PRINTER_DELETED", p.getName() + " (" + p.getId() + ")");
        ctx.status(204);
    }

    private static Printer withDefaults(Printer p) {
        if (p.getOnFailure() == null) {
            p.setOnFailure(Printer.FailureAction.PAUSE);
        }
        if (p.getSensitivity() == 0) {
            p.setSensitivity(1.0);
        }
        return p;
    }

    static void validate(Printer p) {
        if (p.getName() == null || p.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (p.getMoonrakerUrl() == null || p.getMoonrakerUrl().isBlank()) {
            throw new IllegalArgumentException("Moonraker address is required");
        }
        try {
            URI.create(MoonrakerClient.formatBaseUrl(p.getMoonrakerUrl()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Moonraker address is not a valid URL");
        }
        if (p.hasWebcam()) {
            String url = p.getWebcamUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("Webcam URL must start with http:// or https://");
            }
        }
        if (p.getSensitivity() < 0.5 || p.getSensitivity() > 2.0) {
            throw new IllegalArgumentException("Sensitivity must be between 0.5 and 2.0");
        }
        int roiParts = (p.getRoiX() != null ? 1 : 0) + (p.getRoiY() != null ? 1 : 0)
                + (p.getRoiW() != null ? 1 : 0) + (p.getRoiH() != null ? 1 : 0);
        if (roiParts != 0 && roiParts != 4) {
            throw new IllegalArgumentException("Region of interest needs all of x, y, w and h, or none");
        }
        if (roiParts == 4) {
            Roi roi = Roi.of(p.getRoiX(), p.getRoiY(), p.getRoiW(), p.getRoiH());
            p.setRoiX(roi.x());
            p.setRoiY(roi.y());
            p.setRoiW(roi.w());
            p.setRoiH(roi.h());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Live state
    // ------------------------------------------------------------------------------------------

    /** Everything the dashboard needs for one printer card, in one request. */
    public record PrinterStatus(String printerId, boolean online, PrinterTelemetry telemetry,
                                DetectionStatus detection, PrintJob job) {
    }

    private void status(Context ctx) {
        Printer p = printer(ctx);
        PrinterTelemetry t = telemetry.isSampling(p.getId())
                ? telemetry.latestFull(p.getId()).orElse(null)
                : moonraker.getTelemetry(p);
        boolean online = t != null && !"offline".equals(t.getKlipperState());
        ctx.json(new PrinterStatus(p.getId(), online, t, monitor.status(p),
                db.jobs().findOpen(p.getId()).orElse(null)));
    }

    private void analyseNow(Context ctx) throws Exception {
        Printer p = printer(ctx);
        if (!p.hasWebcam()) {
            throw new BadRequestResponse("This printer has no webcam configured");
        }
        try {
            ctx.json(monitor.analyseNow(p));
        } catch (IllegalStateException e) {
            ctx.status(503).json(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            ctx.status(502).json(Map.of("error", "Camera: " + e.getMessage()));
        }
    }

    private void testMoonraker(Context ctx) {
        Printer p = withStoredSecrets(ctx.bodyAsClass(Printer.class));
        if (p.getMoonrakerUrl() == null || p.getMoonrakerUrl().isBlank()) {
            throw new BadRequestResponse("Moonraker address is required");
        }
        PrinterTelemetry t = moonraker.getTelemetry(p);
        boolean ok = t != null && !"offline".equals(t.getKlipperState()) && !"error".equals(t.getKlipperState());
        ctx.json(Map.of(
                "ok", ok,
                "klipperState", String.valueOf(t != null ? t.getKlipperState() : null),
                "message", String.valueOf(t != null ? t.getKlipperMessage() : null),
                "printState", String.valueOf(t != null ? t.getPrintState() : null)));
    }

    /**
     * The edit form never receives the stored secrets, so a connection test of an existing printer
     * arrives without them. Fill them in from the database, so "Test" checks what will be saved.
     */
    private Printer withStoredSecrets(Printer p) {
        if (p.getId() != null) {
            db.printers().find(p.getId()).ifPresent(stored -> {
                if (p.getApiKey() == null || p.getApiKey().isEmpty()) {
                    p.setApiKey(stored.getApiKey());
                }
                if (p.getMqttPassword() == null || p.getMqttPassword().isEmpty()) {
                    p.setMqttPassword(stored.getMqttPassword());
                }
            });
        }
        return p;
    }

    private byte[] fetchCamera(String url) {
        try {
            return camera.fetchBytes(url);
        } catch (IOException e) {
            throw new BadRequestResponse("Camera: " + e.getMessage());
        }
    }

    private void reviewIncident(Context ctx) {
        long id = longParam(ctx, "incidentId");
        String verdict = ctx.bodyAsClass(ReviewRequest.class).groundTruth();
        if (!Incident.TRUE_POSITIVE.equals(verdict) && !Incident.FALSE_POSITIVE.equals(verdict)) {
            throw new BadRequestResponse("groundTruth must be TRUE_POSITIVE or FALSE_POSITIVE");
        }
        if (!db.incidents().review(id, verdict)) {
            throw new NotFoundResponse("Incident not found");
        }
        Incident incident = db.incidents().find(id).orElseThrow();
        db.events().log(incident.getPrinterId(), "INCIDENT_REVIEWED", "incident " + id + ": " + verdict);
        ctx.json(incident);
    }

    /** Body of {@code POST /api/incidents/{id}/review}. */
    public record ReviewRequest(String groundTruth) {
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Printer printer(Context ctx) {
        return db.printers().find(ctx.pathParam("id"))
                .orElseThrow(() -> new NotFoundResponse("Printer not found"));
    }

    /** A job, checked to belong to the printer in the path. */
    private PrintJob job(Context ctx) {
        String printerId = printer(ctx).getId();
        return db.jobs().find(longParam(ctx, "jobId"))
                .filter(j -> j.getPrinterId().equals(printerId))
                .orElseThrow(() -> new NotFoundResponse("Print job not found"));
    }

    private static long longParam(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("Invalid " + name);
        }
    }

    private static int limit(Context ctx, int def, int max) {
        int requested = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(def);
        return Math.clamp(requested, 1, max);
    }

    private static void sendJpeg(Context ctx, byte[] jpeg) {
        ctx.contentType("image/jpeg");
        ctx.header("Cache-Control", "no-store");
        ctx.result(jpeg);
    }
}
