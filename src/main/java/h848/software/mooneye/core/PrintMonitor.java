package h848.software.mooneye.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import h848.software.mooneye.alerts.Notifier;
import h848.software.mooneye.camera.CameraClient;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.detection.Detection;
import h848.software.mooneye.detection.DetectionStatus;
import h848.software.mooneye.detection.FailureDetector;
import h848.software.mooneye.detection.FrameDetector;
import h848.software.mooneye.detection.FailurePredictor;
import h848.software.mooneye.detection.FailurePredictor.Decision;
import h848.software.mooneye.detection.ModelStore;
import h848.software.mooneye.detection.PredictionParams;
import h848.software.mooneye.detection.PredictionState;
import h848.software.mooneye.model.AppSettings;
import h848.software.mooneye.model.DetectionLogEntry;
import h848.software.mooneye.model.Incident;
import h848.software.mooneye.model.PrintJob;
import h848.software.mooneye.model.Printer;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.moonraker.PrinterTelemetry;
import h848.software.mooneye.telemetry.TelemetryService;
import h848.software.mooneye.telemetry.TelemetryWindow;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The detection loop: every {@value PredictionParams#CYCLE_SECONDS} seconds, for every enabled
 * printer, it tracks the print job, grabs a frame, runs the model, updates the failure decision
 * and acts on it.
 * <p>
 * Policy decisions that live here, deliberately:
 * <ul>
 *   <li>Frames are judged only while the printer reports {@code printing}. A paused, idle or
 *       finished printer is not watched - there is nothing to stop.</li>
 *   <li>A pause does not end the print job. The same record continues after a resume, so one
 *       physical print is always one row in the history.</li>
 *   <li>After resuming from a pause MoonEye itself caused, the current-print state is reset the
 *       way a new print would be. The operator has looked and chosen to continue; the evidence
 *       that triggered the pause must not trigger it again a few seconds later.</li>
 *   <li>A warning is sent at most once per print (re-armed after such a resume). In NOTIFY mode
 *       the same applies to a failure.</li>
 *   <li>The operator can mute alerts for the rest of a print.</li>
 * </ul>
 */
public final class PrintMonitor {

    private static final Logger logger = LoggerFactory.getLogger(PrintMonitor.class);

    /** After a failed model load, wait this long before trying again. */
    private static final long MODEL_RETRY_MS = 10 * 60 * 1000;

    /** Klipper's print_duration can jitter slightly; a real restart rewinds it by far more. */
    private static final double DURATION_REWIND_TOLERANCE_SEC = 5.0;

    private final Database db;
    private final TelemetryService telemetry;
    private final MoonrakerClient moonraker;
    private final CameraClient camera;
    private final ModelStore modelStore;
    private final Notifier notifier;
    private final SnapshotStore snapshots;
    private final ObjectMapper json;
    private final FailurePredictor predictor = new FailurePredictor(PredictionParams.OBICO_GEN1);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor-tick");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "monitor-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();

    private volatile FrameDetector detector;
    private volatile String modelError = "Model is loading";
    private final AtomicBoolean modelLoading = new AtomicBoolean();
    private volatile long lastModelAttempt;

    public PrintMonitor(Database db, TelemetryService telemetry, MoonrakerClient moonraker,
                        CameraClient camera, ModelStore modelStore, Notifier notifier,
                        SnapshotStore snapshots, ObjectMapper json) {
        this.db = db;
        this.telemetry = telemetry;
        this.moonraker = moonraker;
        this.camera = camera;
        this.modelStore = modelStore;
        this.notifier = notifier;
        this.snapshots = snapshots;
        this.json = json;
    }

    public void start() {
        loadModelAsync();
        scheduler.scheduleAtFixedRate(this::tick, 5, PredictionParams.CYCLE_SECONDS, TimeUnit.SECONDS);
        logger.info("Print monitor started, checking every {} s.", PredictionParams.CYCLE_SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        workers.shutdownNow();
        FrameDetector d = detector;
        detector = null;
        if (d != null) {
            d.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Model lifecycle
    // ------------------------------------------------------------------------------------------

    private void loadModelAsync() {
        if (!modelLoading.compareAndSet(false, true)) {
            return;
        }
        lastModelAttempt = System.currentTimeMillis();
        // Off the startup path: the first start may download ~200 MB before anything can load.
        Thread loader = new Thread(() -> {
            try {
                loadModel();
            } finally {
                modelLoading.set(false);
            }
        }, "model-loader");
        loader.setDaemon(true);
        loader.start();
    }

    /** Loads (and if needed downloads) the model on the calling thread. */
    void loadModel() {
        try {
            modelError = "Model is loading";
            detector = new FailureDetector(modelStore.ensureAvailable(), predictor.params());
            modelError = null;
            db.events().log(null, "MODEL_LOADED", modelStore.path().getFileName().toString());
        } catch (Exception e) {
            modelError = "Model unavailable: " + e.getMessage();
            logger.error("Failure model could not be loaded; detection is off until it is. {}", e.getMessage());
        }
    }

    /** Replaces the detector - tests use it to script what the "model" sees. */
    void useDetector(FrameDetector replacement) {
        detector = replacement;
        modelError = null;
    }

    public boolean isModelReady() {
        return detector != null;
    }

    public String modelError() {
        return modelError;
    }

    // ------------------------------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------------------------------

    private void tick() {
        try {
            if (detector == null && System.currentTimeMillis() - lastModelAttempt > MODEL_RETRY_MS) {
                loadModelAsync();
            }
            AppSettings settings = db.settings().load();
            for (Printer printer : db.printers().findAll()) {
                if (!printer.isEnabled() || !inProgress.add(printer.getId())) {
                    continue; // disabled, or the previous cycle for this printer is still running
                }
                workers.submit(() -> {
                    try {
                        check(printer, settings);
                    } catch (Exception e) {
                        logger.error("Monitoring printer {} failed", printer.getName(), e);
                    } finally {
                        inProgress.remove(printer.getId());
                    }
                });
            }
        } catch (Exception e) {
            logger.error("Monitor tick failed", e);
        }
    }

    void check(Printer printer, AppSettings settings) {
        PrinterTelemetry t = currentTelemetry(printer);
        if (t == null || t.getPrintState() == null || "offline".equals(t.getKlipperState())) {
            return; // unreachable: the print state is unknown, so leave the job record alone
        }
        Runtime rt = runtime(printer.getId());
        PrintJob job = trackJob(printer, t, rt);
        boolean printing = "printing".equalsIgnoreCase(t.getPrintState());

        if (printing && printer.isDetectionEnabled() && printer.hasWebcam() && detector != null && job != null) {
            analyse(printer, t, job, rt, settings);
        } else {
            synchronized (rt) {
                rt.watching = false;
            }
        }

        Map<String, Object> payload = Notifier.envelope(Notifier.EVENT_TELEMETRY, printer);
        payload.put("printState", t.getPrintState());
        payload.put("filename", t.getFilename());
        payload.put("progress", t.getProgress());
        payload.put("extruderTemp", t.getExtruderTemp());
        payload.put("bedTemp", t.getBedTemp());
        synchronized (rt) {
            payload.put("failureScore", rt.watching ? rt.score : null);
        }
        notifier.send(printer, payload, true);
    }

    /**
     * Keeps the print_jobs table in step with what the printer reports and returns the job in
     * progress (printing or paused), or null when the printer is not printing.
     */
    private PrintJob trackJob(Printer printer, PrinterTelemetry t, Runtime rt) {
        String state = t.getPrintState().toLowerCase();
        boolean active = state.equals(PrintJob.STATUS_PRINTING) || state.equals(PrintJob.STATUS_PAUSED);
        PrintJob open = db.jobs().findOpen(printer.getId()).orElse(null);
        String previousState;
        synchronized (rt) {
            previousState = rt.lastPrintState;
            rt.lastPrintState = state;
        }

        if (!active) {
            if (open != null) {
                finishJob(open, state, t);
                db.events().log(printer.getId(), "PRINT_ENDED", state + ": " + open.getFilename());
            }
            return null;
        }

        boolean samePrint = open != null
                && Objects.equals(open.getFilename(), t.getFilename())
                && t.getPrintDuration() + DURATION_REWIND_TOLERANCE_SEC >= open.getDurationSeconds();
        if (open != null && !samePrint) {
            // A different file, or the same one restarted, without us seeing the end of the last.
            finishJob(open, "interrupted", t);
            open = null;
        }

        if (open == null) {
            open = new PrintJob();
            open.setPrinterId(printer.getId());
            open.setFilename(t.getFilename());
            open.setStartTime(Instant.now());
            open.setStatus(state);
            open.setDurationSeconds(t.getPrintDuration());
            open.setFilamentUsed(t.getFilamentUsed());
            db.jobs().insert(open);
            synchronized (rt) {
                rt.prediction.resetForNewPrint();
                rt.warnedJobId = null;
                rt.notifiedFailureJobId = null;
                rt.pausedByUs = false;
            }
            db.events().log(printer.getId(), "PRINT_STARTED", t.getFilename());
        } else if (PrintJob.STATUS_PAUSED.equals(previousState) && state.equals(PrintJob.STATUS_PRINTING)) {
            synchronized (rt) {
                if (rt.pausedByUs) {
                    rt.prediction.resetForNewPrint();
                    rt.warnedJobId = null;
                    rt.pausedByUs = false;
                    logger.info("Printer {} resumed after a MoonEye pause; detection restarts its warm-up.",
                            printer.getName());
                }
            }
        }

        open.setStatus(state);
        open.setDurationSeconds(t.getPrintDuration());
        open.setFilamentUsed(t.getFilamentUsed());
        db.jobs().update(open);
        return open;
    }

    private void finishJob(PrintJob job, String status, PrinterTelemetry t) {
        job.setEndTime(Instant.now());
        job.setStatus(status);
        // A stale reading (e.g. the next print already started) must not overwrite the totals.
        if (Objects.equals(job.getFilename(), t.getFilename()) && t.getPrintDuration() >= job.getDurationSeconds()) {
            job.setDurationSeconds(t.getPrintDuration());
            job.setFilamentUsed(t.getFilamentUsed());
        }
        db.jobs().update(job);
    }

    private void analyse(Printer printer, PrinterTelemetry t, PrintJob job, Runtime rt, AppSettings settings) {
        CameraClient.Frame frame;
        try {
            frame = camera.fetch(printer.getWebcamUrl());
        } catch (IOException e) {
            synchronized (rt) {
                rt.cameraError = e.getMessage();
                rt.watching = false;
            }
            logger.debug("No frame from {}: {}", printer.getName(), e.getMessage());
            return;
        }

        FailureDetector.Result result;
        try {
            result = detector.detect(frame.image(), printer.getRoi());
        } catch (Exception e) {
            logger.error("Inference failed for printer {}", printer.getName(), e);
            return;
        }

        Decision decision;
        double score;
        PredictionState snapshot;
        List<Detection> visible = predictor.visible(result.detections());
        synchronized (rt) {
            predictor.update(rt.prediction, result.detections());
            decision = predictor.decide(rt.prediction, printer.getSensitivity());
            score = predictor.normalizedScore(rt.prediction, printer.getSensitivity());
            snapshot = rt.prediction.copy();

            rt.watching = true;
            rt.cameraError = null;
            rt.lastFrame = frame.jpeg();
            rt.lastFrameAt = Instant.ofEpochMilli(frame.capturedAt());
            rt.lastDetections = visible;
            rt.decision = decision;
            rt.score = score;
            rt.inferenceMs = result.inferenceMs();
        }
        db.predictions().save(printer.getId(), snapshot);

        TelemetryWindow window = telemetry.windowEndingAt(printer.getId(), frame.capturedAt()).orElse(null);
        DetectionLogEntry entry = new DetectionLogEntry();
        entry.setPrinterId(printer.getId());
        entry.setJobId(job.getId());
        entry.setCurrentP(snapshot.getCurrentP());
        entry.setEwmMean(snapshot.getEwmMean());
        entry.setRollingMeanShort(snapshot.getRollingMeanShort());
        entry.setRollingMeanLong(snapshot.getRollingMeanLong());
        entry.setScore(score);
        entry.setDecision(decision.name());
        entry.setFrameNum(snapshot.getCurrentFrameNum());
        entry.setDetections(toJson(result.detections()));
        entry.setExtruderTemp(t.getExtruderTemp());
        entry.setBedTemp(t.getBedTemp());
        entry.setProgress(t.getProgress());
        entry.setTelemetryWindow(window != null ? toJson(window) : null);
        entry.setInferenceMs(result.inferenceMs());
        db.detectionLog().insert(entry);

        boolean keepFrame = predictor.isSuspicious(snapshot);
        synchronized (rt) {
            int interval = settings.getSnapshotIntervalSec();
            if (interval > 0 && frame.capturedAt() - rt.lastSnapshotSaved >= interval * 1000L) {
                keepFrame = true;
            }
            if (keepFrame) {
                rt.lastSnapshotSaved = frame.capturedAt();
            }
        }
        if (keepFrame) {
            snapshots.save(printer.getId(), job.getId(), frame.capturedAt(), frame.jpeg());
        }

        updateDisplay(printer, rt, score, decision);
        act(printer, t, job, rt, decision, score, snapshot, result.detections(), frame.jpeg());
    }

    private void act(Printer printer, PrinterTelemetry t, PrintJob job, Runtime rt, Decision decision,
                     double score, PredictionState state, List<Detection> detections, byte[] jpeg) {
        if (decision == Decision.NONE || job.isAlertsMuted()) {
            return;
        }
        boolean pauseMode = printer.getOnFailure() == Printer.FailureAction.PAUSE;

        if (decision == Decision.WARNING) {
            synchronized (rt) {
                if (Objects.equals(rt.warnedJobId, job.getId())) {
                    return;
                }
                rt.warnedJobId = job.getId();
            }
            long id = recordIncident(printer, t, job, Incident.LEVEL_WARNING, Incident.ACTION_NOTIFIED,
                    score, state, detections, jpeg);
            db.events().log(printer.getId(), "AI_WARNING", String.format(java.util.Locale.US,
                    "score %.2f on %s", score, t.getFilename()));
            alert(printer, Notifier.EVENT_WARNING, Incident.ACTION_NOTIFIED, t, score, id);
            return;
        }

        // FAILURE
        if (!pauseMode) {
            synchronized (rt) {
                if (Objects.equals(rt.notifiedFailureJobId, job.getId())) {
                    return;
                }
                rt.notifiedFailureJobId = job.getId();
            }
        }

        String action = Incident.ACTION_NOTIFIED;
        if (pauseMode) {
            logger.warn("Failure detected on {} (score {}). Pausing print.", printer.getName(),
                    String.format(java.util.Locale.US, "%.2f", score));
            if (moonraker.pausePrint(printer)) {
                action = Incident.ACTION_PAUSED;
                job.setAiPauses(job.getAiPauses() + 1);
                job.setStatus(PrintJob.STATUS_PAUSED);
                db.jobs().update(job);
                synchronized (rt) {
                    rt.pausedByUs = true;
                }
            } else {
                db.events().log(printer.getId(), "AI_PAUSE_FAILED", "Moonraker did not accept PAUSE");
            }
        }

        long id = recordIncident(printer, t, job, Incident.LEVEL_FAILURE, action, score, state, detections, jpeg);
        db.events().log(printer.getId(), Incident.ACTION_PAUSED.equals(action) ? "AI_PAUSE" : "AI_FAILURE",
                String.format(java.util.Locale.US, "score %.2f on %s", score, t.getFilename()));
        alert(printer, Notifier.EVENT_FAILURE, action, t, score, id);
    }

    private long recordIncident(Printer printer, PrinterTelemetry t, PrintJob job, String level, String action,
                                double score, PredictionState state, List<Detection> detections, byte[] jpeg) {
        Incident incident = new Incident();
        incident.setPrinterId(printer.getId());
        incident.setJobId(job.getId());
        incident.setFilename(t.getFilename());
        incident.setLevel(level);
        incident.setAction(action);
        incident.setScore(score);
        incident.setCurrentP(state.getCurrentP());
        incident.setDetections(toJson(detections));
        return db.incidents().insert(incident, jpeg);
    }

    private void alert(Printer printer, String event, String action, PrinterTelemetry t, double score, long incidentId) {
        Map<String, Object> payload = Notifier.envelope(event, printer);
        payload.put("action", action);
        payload.put("filename", t.getFilename());
        payload.put("progress", t.getProgress());
        payload.put("failureScore", score);
        payload.put("incidentId", incidentId);
        notifier.send(printer, payload, false);
    }

    /** Shows the score on the printer's display, but only when what it would say changes. */
    private void updateDisplay(Printer printer, Runtime rt, double score, Decision decision) {
        if (!printer.isM117Status()) {
            return;
        }
        String text = switch (decision) {
            case NONE -> String.format("MoonEye OK %d%%", Math.round(score * 100));
            case WARNING -> String.format("MoonEye WARNING %d%%", Math.round(score * 100));
            case FAILURE -> String.format("MoonEye FAILURE %d%%", Math.round(score * 100));
        };
        synchronized (rt) {
            if (text.equals(rt.lastDisplayText)) {
                return;
            }
            rt.lastDisplayText = text;
        }
        moonraker.sendM117(printer, text);
    }

    // ------------------------------------------------------------------------------------------
    // Operator actions and status
    // ------------------------------------------------------------------------------------------

    /** Silences alerts for the rest of the current print. @return false if nothing is printing */
    public boolean muteCurrentPrint(String printerId) {
        Optional<PrintJob> job = db.jobs().findOpen(printerId);
        if (job.isEmpty()) {
            return false;
        }
        job.get().setAlertsMuted(true);
        db.jobs().update(job.get());
        db.events().log(printerId, "ALERTS_MUTED", "for " + job.get().getFilename());
        return true;
    }

    /**
     * Forgets what this printer's camera "normally" looks like. Needed after moving the camera,
     * changing its URL or the region of interest - the long-term mean otherwise describes a scene
     * that no longer exists, for up to a day of printing.
     */
    public void resetPrediction(String printerId) {
        Runtime rt = runtimes.get(printerId);
        if (rt != null) {
            // Reset in place rather than dropping the runtime: a cycle may be running right now
            // and would otherwise write the old state back when it finishes.
            synchronized (rt) {
                rt.prediction.resetForNewPrint();
                rt.prediction.setRollingMeanLong(0);
                rt.prediction.setLifetimeFrameNum(0);
            }
        }
        db.predictions().delete(printerId);
        db.events().log(printerId, "PREDICTION_RESET", "long-term baseline cleared");
    }

    /** Called after a printer was edited; resets the baseline only when the view changed. */
    public void onPrinterUpdated(Printer before, Printer after) {
        boolean viewChanged = !Objects.equals(before.getWebcamUrl(), after.getWebcamUrl())
                || !Objects.equals(before.getRoi(), after.getRoi());
        if (viewChanged) {
            resetPrediction(after.getId());
        }
    }

    public void onPrinterDeleted(String printerId) {
        runtimes.remove(printerId);
    }

    public DetectionStatus status(Printer printer) {
        Runtime rt = runtimes.get(printer.getId());
        if (rt == null) {
            return new DetectionStatus(isModelReady(), modelError, false, null, 0, Decision.NONE, 0,
                    predictor.params().initSafeFrames() * PredictionParams.CYCLE_SECONDS, List.of(), null, null);
        }
        synchronized (rt) {
            return new DetectionStatus(isModelReady(), modelError, rt.watching, rt.lastFrameAt,
                    rt.score, rt.decision, rt.prediction.getCurrentP(),
                    predictor.warmupFramesRemaining(rt.prediction) * PredictionParams.CYCLE_SECONDS,
                    rt.lastDetections, rt.inferenceMs, rt.cameraError);
        }
    }

    /** The last frame that was analysed, for the live view. */
    public Optional<byte[]> lastFrame(String printerId) {
        Runtime rt = runtimes.get(printerId);
        if (rt == null) {
            return Optional.empty();
        }
        synchronized (rt) {
            return Optional.ofNullable(rt.lastFrame);
        }
    }

    /** Result of a one-off analysis that does not touch any state. */
    /** {@code image} is the analysed frame itself, so the boxes can be drawn over exactly it. */
    public record Analysis(List<Detection> detections, double frameScore, int inferenceMs,
                           int imageWidth, int imageHeight, byte[] image) {
    }

    /**
     * Grabs a frame now and runs the model on it, without recording anything. This is how the
     * operator checks a camera angle or a staged failure without having to print.
     */
    public Analysis analyseNow(Printer printer) throws Exception {
        FrameDetector d = detector;
        if (d == null) {
            throw new IllegalStateException(modelError != null ? modelError : "Model not loaded");
        }
        CameraClient.Frame frame = camera.fetch(printer.getWebcamUrl());
        FailureDetector.Result result = d.detect(frame.image(), printer.getRoi());
        return new Analysis(result.detections(), FailurePredictor.frameScore(result.detections()),
                result.inferenceMs(), frame.image().getWidth(), frame.image().getHeight(), frame.jpeg());
    }

    // ------------------------------------------------------------------------------------------

    private Runtime runtime(String printerId) {
        return runtimes.computeIfAbsent(printerId, id ->
                new Runtime(db.predictions().find(id).orElseGet(PredictionState::new)));
    }

    private PrinterTelemetry currentTelemetry(Printer printer) {
        if (telemetry.isSampling(printer.getId())) {
            Optional<PrinterTelemetry> cached = telemetry.latestFull(printer.getId());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return moonraker.getTelemetry(printer);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** Mutable per-printer state. Guarded by its own monitor. */
    private static final class Runtime {
        final PredictionState prediction;
        String lastPrintState;
        boolean pausedByUs;
        Long warnedJobId;
        Long notifiedFailureJobId;
        boolean watching;
        String cameraError;
        byte[] lastFrame;
        Instant lastFrameAt;
        List<Detection> lastDetections = List.of();
        Decision decision = Decision.NONE;
        double score;
        Integer inferenceMs;
        long lastSnapshotSaved;
        String lastDisplayText;

        Runtime(PredictionState prediction) {
            this.prediction = prediction;
        }
    }
}
