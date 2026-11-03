package h848.software.mooneye.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A monitored printer and everything configured for it.
 * <p>
 * Secrets ({@code apiKey}, {@code mqttPassword}) are write-only over the API: they are accepted
 * on create/update but never serialised back. An update that leaves them {@code null} keeps the
 * stored value, so the UI does not have to round-trip a secret it was never shown.
 */
public class Printer {

    private String id;
    private String name;
    /** Moonraker base address. A bare host gets {@code http://} and port 7125 added. */
    private String moonrakerUrl;
    private String apiKey;
    /** Snapshot URL (e.g. crowsnest {@code /webcam/?action=snapshot}). Empty = no camera. */
    private String webcamUrl;
    private boolean enabled = true;

    // --- Failure detection ---
    private boolean detectionEnabled = true;
    private FailureAction onFailure = FailureAction.PAUSE;
    /**
     * Multiplier on the failure score, as in Obico's "detective sensitivity". 1.0 is the tuned
     * default; higher alarms earlier, lower tolerates more.
     */
    private double sensitivity = 1.0;
    /**
     * Optional region of interest as fractions of the frame (0..1). Everything outside is cut away
     * before inference so cables, the room and the spool cannot trigger anything. All four null =
     * use the whole frame.
     */
    private Double roiX;
    private Double roiY;
    private Double roiW;
    private Double roiH;

    // --- Notifications ---
    private String webhookUrl;
    private boolean webhookTelemetry;
    private String mqttBroker;
    private String mqttTopic;
    private String mqttUsername;
    private String mqttPassword;
    private String mqttClientId;
    private boolean mqttTelemetry;
    /** Show the failure score on the printer's display via M117. */
    private boolean m117Status;

    private Instant createdAt;

    public enum FailureAction {
        /** Pause the print and notify. */
        PAUSE,
        /** Only notify; the print continues. */
        NOTIFY
    }

    /** The configured region of interest, or null when the whole frame is used. */
    @JsonIgnore
    public Roi getRoi() {
        if (roiX == null || roiY == null || roiW == null || roiH == null) {
            return null;
        }
        return Roi.of(roiX, roiY, roiW, roiH);
    }

    @JsonIgnore
    public boolean hasWebcam() {
        return webcamUrl != null && !webcamUrl.isBlank();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMoonrakerUrl() { return moonrakerUrl; }
    public void setMoonrakerUrl(String moonrakerUrl) { this.moonrakerUrl = moonrakerUrl; }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /** Lets the UI show "API key set" without ever receiving the key. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isApiKeySet() { return apiKey != null && !apiKey.isBlank(); }

    public String getWebcamUrl() { return webcamUrl; }
    public void setWebcamUrl(String webcamUrl) { this.webcamUrl = webcamUrl; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDetectionEnabled() { return detectionEnabled; }
    public void setDetectionEnabled(boolean detectionEnabled) { this.detectionEnabled = detectionEnabled; }

    public FailureAction getOnFailure() { return onFailure; }
    public void setOnFailure(FailureAction onFailure) { this.onFailure = onFailure; }

    public double getSensitivity() { return sensitivity; }
    public void setSensitivity(double sensitivity) { this.sensitivity = sensitivity; }

    public Double getRoiX() { return roiX; }
    public void setRoiX(Double roiX) { this.roiX = roiX; }

    public Double getRoiY() { return roiY; }
    public void setRoiY(Double roiY) { this.roiY = roiY; }

    public Double getRoiW() { return roiW; }
    public void setRoiW(Double roiW) { this.roiW = roiW; }

    public Double getRoiH() { return roiH; }
    public void setRoiH(Double roiH) { this.roiH = roiH; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public boolean isWebhookTelemetry() { return webhookTelemetry; }
    public void setWebhookTelemetry(boolean webhookTelemetry) { this.webhookTelemetry = webhookTelemetry; }

    public String getMqttBroker() { return mqttBroker; }
    public void setMqttBroker(String mqttBroker) { this.mqttBroker = mqttBroker; }

    public String getMqttTopic() { return mqttTopic; }
    public void setMqttTopic(String mqttTopic) { this.mqttTopic = mqttTopic; }

    public String getMqttUsername() { return mqttUsername; }
    public void setMqttUsername(String mqttUsername) { this.mqttUsername = mqttUsername; }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getMqttPassword() { return mqttPassword; }
    public void setMqttPassword(String mqttPassword) { this.mqttPassword = mqttPassword; }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isMqttPasswordSet() { return mqttPassword != null && !mqttPassword.isEmpty(); }

    public String getMqttClientId() { return mqttClientId; }
    public void setMqttClientId(String mqttClientId) { this.mqttClientId = mqttClientId; }

    public boolean isMqttTelemetry() { return mqttTelemetry; }
    public void setMqttTelemetry(boolean mqttTelemetry) { this.mqttTelemetry = mqttTelemetry; }

    public boolean isM117Status() { return m117Status; }
    public void setM117Status(boolean m117Status) { this.m117Status = m117Status; }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
