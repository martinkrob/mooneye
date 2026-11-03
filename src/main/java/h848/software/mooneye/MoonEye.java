package h848.software.mooneye;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import h848.software.mooneye.alerts.Notifier;
import h848.software.mooneye.camera.CameraClient;
import h848.software.mooneye.config.AppConfig;
import h848.software.mooneye.core.PrintMonitor;
import h848.software.mooneye.core.RetentionService;
import h848.software.mooneye.core.SnapshotStore;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.detection.ModelStore;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.telemetry.TelemetryService;
import h848.software.mooneye.web.ApiServer;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Wires the services together and starts them in dependency order:
 * database, telemetry, detection, retention, then the web server.
 */
public final class MoonEye {

    private static final Logger logger = LoggerFactory.getLogger(MoonEye.class);

    private MoonEye() {
    }

    public static void main(String[] args) throws Exception {
        start(AppConfig.fromEnvironment());
    }

    /**
     * Starts everything for the given configuration and registers a shutdown hook. Separate from
     * {@link #main} so a development launcher can supply its own configuration.
     *
     * @return the database, for launchers that want to seed it
     */
    public static Database start(AppConfig config) throws Exception {
        String version = version();
        logger.info("Starting MoonEye {} (data in {})", version, config.dataDir().toAbsolutePath());
        Files.createDirectories(config.dataDir());

        ObjectMapper json = jsonMapper();
        Database db = new Database(config.dbPath());
        MoonrakerClient moonraker = new MoonrakerClient();
        TelemetryService telemetry = new TelemetryService(db, moonraker, config.telemetryIntervalMs());
        Notifier notifier = new Notifier(json);
        SnapshotStore snapshots = new SnapshotStore(config.snapshotsDir());
        PrintMonitor monitor = new PrintMonitor(db, telemetry, moonraker, new CameraClient(),
                new ModelStore(config.modelPath(), config.modelUrl(), config.modelSha256()),
                notifier, snapshots, json);
        RetentionService retention = new RetentionService(db, config.snapshotsDir());
        ApiServer api = new ApiServer(db, telemetry, moonraker, new CameraClient(), monitor, retention,
                snapshots, notifier, json, version);

        telemetry.start();
        monitor.start();
        retention.start();
        api.start(config.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            api.stop();
            monitor.stop();
            retention.stop();
            telemetry.stop();
            notifier.close();
            db.close();
        }, "shutdown"));
        return db;
    }

    public static ObjectMapper jsonMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                // ISO-8601 strings rather than epoch numbers - readable in the API and in webhooks.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // The UI may send back read-only fields it received (apiKeySet, createdAt...).
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static String version() {
        String v = MoonEye.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
