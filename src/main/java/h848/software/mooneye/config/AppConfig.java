package h848.software.mooneye.config;

import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level configuration, read once from the environment at startup.
 * <p>
 * Everything an operator may want to change at runtime lives in the database instead (see
 * {@code SettingsRepository}); this holds only what has to be known before the database is open
 * or what describes the deployment rather than the user's preferences.
 *
 * @param dataDir             root of everything persistent: database, models, snapshots
 * @param dbPath              H2 database path without the {@code .mv.db} extension
 * @param modelPath           ONNX failure-detection model
 * @param modelUrl            where to fetch the model from when it is missing, or null to never download
 * @param modelSha256         expected checksum of the downloaded model, or null to skip verification
 * @param telemetryIntervalMs Moonraker sampling period; 0 disables continuous collection
 */
public record AppConfig(
        int port,
        Path dataDir,
        Path dbPath,
        Path modelPath,
        String modelUrl,
        String modelSha256,
        long telemetryIntervalMs) {

    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    /** Obico's open-source failure model (1st generation, YOLOv2, single class "failure"). */
    public static final String DEFAULT_MODEL_URL =
            "https://www.obico.io/static_pub/ml-models/model-weights-5a6b1be1fa.onnx";
    public static final String DEFAULT_MODEL_SHA256 =
            "0a6ebd8e30dbf6a450c50f9c0a5406f04ba7eb1c99fd5996e888c78bb383b9aa";
    public static final String DEFAULT_MODEL_FILE = "obico-failure-gen1.onnx";

    private static final int DEFAULT_PORT = 8080;
    private static final long DEFAULT_TELEMETRY_INTERVAL_MS = 1000;
    /** Klipper refreshes its objects only a few times a second; polling faster just loads Moonraker. */
    private static final long MIN_TELEMETRY_INTERVAL_MS = 200;

    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    /** Separate from {@link #fromEnvironment()} so tests can pass an arbitrary map. */
    public static AppConfig from(Map<String, String> env) {
        Path dataDir = Path.of(env.getOrDefault("MOONEYE_DATA_PATH", "./data"));
        Path dbPath = env.containsKey("MOONEYE_DB_PATH")
                ? Path.of(env.get("MOONEYE_DB_PATH"))
                : dataDir.resolve("mooneye");
        Path modelPath = env.containsKey("MOONEYE_MODEL_PATH")
                ? Path.of(env.get("MOONEYE_MODEL_PATH"))
                : dataDir.resolve("models").resolve(DEFAULT_MODEL_FILE);

        // An explicitly empty URL means "never download" - useful on an air-gapped printer network
        // where the model is copied into the volume by hand.
        String modelUrl = env.getOrDefault("MOONEYE_MODEL_URL", DEFAULT_MODEL_URL);
        if (modelUrl.isBlank()) {
            modelUrl = null;
        }
        // A custom URL almost certainly serves a different file, so the default checksum must not
        // be applied to it.
        String sha = env.containsKey("MOONEYE_MODEL_SHA256")
                ? blankToNull(env.get("MOONEYE_MODEL_SHA256"))
                : (DEFAULT_MODEL_URL.equals(modelUrl) ? DEFAULT_MODEL_SHA256 : null);

        return new AppConfig(
                parsePort(env.get("MOONEYE_PORT")),
                dataDir,
                dbPath,
                modelPath,
                modelUrl,
                sha,
                parseTelemetryInterval(env.get("MOONEYE_TELEMETRY_INTERVAL_MS")));
    }

    public Path snapshotsDir() {
        return dataDir.resolve("snapshots");
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            logger.warn("Invalid MOONEYE_PORT '{}'. Using {}.", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    private static long parseTelemetryInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TELEMETRY_INTERVAL_MS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed == 0 ? 0 : Math.max(MIN_TELEMETRY_INTERVAL_MS, parsed);
        } catch (NumberFormatException e) {
            logger.warn("Invalid MOONEYE_TELEMETRY_INTERVAL_MS '{}'. Using {} ms.", raw, DEFAULT_TELEMETRY_INTERVAL_MS);
            return DEFAULT_TELEMETRY_INTERVAL_MS;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
