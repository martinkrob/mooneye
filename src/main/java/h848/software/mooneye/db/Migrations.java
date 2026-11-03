package h848.software.mooneye.db;

import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned schema migrations.
 * <p>
 * Each entry is applied once, in order, and recorded in {@code schema_version}. Never edit a
 * migration that has shipped - append a new one. This replaces the "ALTER TABLE ... IF NOT EXISTS
 * on every start" approach, which could add columns but never express a rename, a type change or
 * a data fix.
 */
final class Migrations {

    private static final Logger logger = LoggerFactory.getLogger(Migrations.class);

    private Migrations() {
    }

    private static final List<List<String>> MIGRATIONS = List.of(
            // v1 - initial schema
            List.of(
                    """
                    CREATE TABLE settings (
                        k VARCHAR(100) PRIMARY KEY,
                        v VARCHAR(4000) NOT NULL)
                    """,
                    """
                    CREATE TABLE printers (
                        id VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        moonraker_url VARCHAR(255) NOT NULL,
                        api_key VARCHAR(255),
                        webcam_url VARCHAR(500),
                        enabled BOOLEAN DEFAULT TRUE NOT NULL,
                        detection_enabled BOOLEAN DEFAULT TRUE NOT NULL,
                        on_failure VARCHAR(20) DEFAULT 'PAUSE' NOT NULL,
                        sensitivity DOUBLE PRECISION DEFAULT 1.0 NOT NULL,
                        roi_x DOUBLE PRECISION,
                        roi_y DOUBLE PRECISION,
                        roi_w DOUBLE PRECISION,
                        roi_h DOUBLE PRECISION,
                        webhook_url VARCHAR(500),
                        webhook_telemetry BOOLEAN DEFAULT FALSE NOT NULL,
                        mqtt_broker VARCHAR(255),
                        mqtt_topic VARCHAR(255),
                        mqtt_username VARCHAR(255),
                        mqtt_password VARCHAR(255),
                        mqtt_client_id VARCHAR(255),
                        mqtt_telemetry BOOLEAN DEFAULT FALSE NOT NULL,
                        m117_status BOOLEAN DEFAULT FALSE NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)
                    """,
                    """
                    CREATE TABLE print_jobs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printer_id VARCHAR(36) NOT NULL REFERENCES printers(id) ON DELETE CASCADE,
                        filename VARCHAR(500),
                        start_time TIMESTAMP WITH TIME ZONE NOT NULL,
                        end_time TIMESTAMP WITH TIME ZONE,
                        status VARCHAR(30) NOT NULL,
                        duration_seconds DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        filament_used DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        ai_pauses INT DEFAULT 0 NOT NULL,
                        alerts_muted BOOLEAN DEFAULT FALSE NOT NULL)
                    """,
                    "CREATE INDEX idx_jobs_printer ON print_jobs(printer_id, id)",
                    """
                    CREATE TABLE predictions (
                        printer_id VARCHAR(36) PRIMARY KEY REFERENCES printers(id) ON DELETE CASCADE,
                        current_frame_num INT DEFAULT 0 NOT NULL,
                        lifetime_frame_num INT DEFAULT 0 NOT NULL,
                        current_p DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        ewm_mean DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        rolling_mean_short DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        rolling_mean_long DOUBLE PRECISION DEFAULT 0 NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)
                    """,
                    """
                    CREATE TABLE incidents (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printer_id VARCHAR(36) NOT NULL REFERENCES printers(id) ON DELETE CASCADE,
                        job_id BIGINT REFERENCES print_jobs(id) ON DELETE SET NULL,
                        ts TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                        filename VARCHAR(500),
                        level VARCHAR(10) NOT NULL,
                        action VARCHAR(20) NOT NULL,
                        score DOUBLE PRECISION NOT NULL,
                        current_p DOUBLE PRECISION NOT NULL,
                        detections CLOB,
                        image BLOB,
                        ground_truth VARCHAR(20),
                        reviewed_at TIMESTAMP WITH TIME ZONE)
                    """,
                    "CREATE INDEX idx_incidents_printer ON incidents(printer_id, id)",
                    """
                    CREATE TABLE detection_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printer_id VARCHAR(36) NOT NULL REFERENCES printers(id) ON DELETE CASCADE,
                        job_id BIGINT,
                        ts TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                        current_p DOUBLE PRECISION NOT NULL,
                        ewm_mean DOUBLE PRECISION NOT NULL,
                        rolling_mean_short DOUBLE PRECISION NOT NULL,
                        rolling_mean_long DOUBLE PRECISION NOT NULL,
                        score DOUBLE PRECISION NOT NULL,
                        decision VARCHAR(10) NOT NULL,
                        frame_num INT NOT NULL,
                        detections CLOB,
                        extruder_temp DOUBLE PRECISION,
                        bed_temp DOUBLE PRECISION,
                        progress DOUBLE PRECISION,
                        telemetry_window CLOB,
                        inference_ms INT)
                    """,
                    "CREATE INDEX idx_detlog_printer ON detection_log(printer_id, id)",
                    "CREATE INDEX idx_detlog_job ON detection_log(job_id)",
                    """
                    CREATE TABLE events (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printer_id VARCHAR(36),
                        ts TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                        event_type VARCHAR(50) NOT NULL,
                        notes VARCHAR(2000))
                    """
            )
    );

    static void apply(Jdbi jdbi) {
        jdbi.useTransaction(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INT PRIMARY KEY, "
                    + "applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");
            int current = currentVersion(h);
            if (current > MIGRATIONS.size()) {
                throw new IllegalStateException("Database schema version " + current
                        + " is newer than this MoonEye build supports (" + MIGRATIONS.size()
                        + "). Refusing to start rather than risk damaging it.");
            }
            for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
                logger.info("Applying database migration v{}", v);
                for (String sql : MIGRATIONS.get(v - 1)) {
                    h.execute(sql);
                }
                h.execute("INSERT INTO schema_version (version) VALUES (?)", v);
            }
        });
    }

    private static int currentVersion(Handle h) {
        return h.createQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")
                .mapTo(Integer.class)
                .one();
    }
}
