package h848.software.mooneye.db;

import h848.software.mooneye.model.Printer;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

public final class PrinterRepository {

    private static final String COLUMNS = "id, name, moonraker_url, api_key, webcam_url, enabled, "
            + "detection_enabled, on_failure, sensitivity, roi_x, roi_y, roi_w, roi_h, "
            + "webhook_url, webhook_telemetry, mqtt_broker, mqtt_topic, mqtt_username, mqtt_password, "
            + "mqtt_client_id, mqtt_telemetry, m117_status";

    private static final String VALUES = ":id, :name, :moonrakerUrl, :apiKey, :webcamUrl, :enabled, "
            + ":detectionEnabled, :onFailure, :sensitivity, :roiX, :roiY, :roiW, :roiH, "
            + ":webhookUrl, :webhookTelemetry, :mqttBroker, :mqttTopic, :mqttUsername, :mqttPassword, "
            + ":mqttClientId, :mqttTelemetry, :m117Status";

    private final Jdbi jdbi;

    PrinterRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public List<Printer> findAll() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM printers ORDER BY created_at, name")
                .mapToBean(Printer.class)
                .list());
    }

    public Optional<Printer> find(String id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM printers WHERE id = :id")
                .bind("id", id)
                .mapToBean(Printer.class)
                .findOne());
    }

    public void insert(Printer p) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO printers (" + COLUMNS + ") VALUES (" + VALUES + ")")
                .bindBean(p)
                .execute());
    }

    /**
     * Updates everything but the secrets, which are only overwritten when a new value is supplied.
     *
     * @return false if the printer does not exist
     */
    public boolean update(Printer p) {
        return jdbi.withHandle(h -> h.createUpdate("""
                UPDATE printers SET name = :name, moonraker_url = :moonrakerUrl, webcam_url = :webcamUrl,
                    enabled = :enabled, detection_enabled = :detectionEnabled, on_failure = :onFailure,
                    sensitivity = :sensitivity, roi_x = :roiX, roi_y = :roiY, roi_w = :roiW, roi_h = :roiH,
                    webhook_url = :webhookUrl, webhook_telemetry = :webhookTelemetry,
                    mqtt_broker = :mqttBroker, mqtt_topic = :mqttTopic, mqtt_username = :mqttUsername,
                    mqtt_client_id = :mqttClientId, mqtt_telemetry = :mqttTelemetry, m117_status = :m117Status,
                    api_key = COALESCE(:apiKey, api_key),
                    mqtt_password = COALESCE(:mqttPassword, mqtt_password)
                WHERE id = :id
                """)
                .bindBean(p)
                .execute() > 0);
    }

    public boolean delete(String id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM printers WHERE id = :id")
                .bind("id", id)
                .execute() > 0);
    }
}
