package h848.software.mooneye.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import h848.software.mooneye.model.Printer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers events to a printer's webhook and MQTT topic.
 * <p>
 * Delivery happens on a background thread so a slow receiver can never hold up the detection
 * loop - the pause has already been sent by the time anything here runs. Payloads are built with
 * Jackson, so names and file names with quotes or non-ASCII characters cannot break the JSON.
 */
public final class Notifier implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Notifier.class);

    public static final String EVENT_WARNING = "failure_warning";
    public static final String EVENT_FAILURE = "failure_detected";
    public static final String EVENT_TELEMETRY = "telemetry";
    public static final String EVENT_TEST = "test";

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            // Some receivers (Node-RED behind certain proxies) mishandle HTTP/2 upgrade attempts.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifier");
        t.setDaemon(true);
        return t;
    });

    public Notifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Builds the common envelope every event shares. */
    public static Map<String, Object> envelope(String event, Printer printer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("printerId", printer.getId());
        payload.put("printerName", printer.getName());
        payload.put("timestamp", java.time.Instant.now().toString());
        return payload;
    }

    /** Sends an alert to every channel the printer has configured. Never blocks the caller. */
    public void send(Printer printer, Map<String, Object> payload, boolean telemetry) {
        boolean webhook = hasWebhook(printer) && (!telemetry || printer.isWebhookTelemetry());
        boolean mqtt = hasMqtt(printer) && (!telemetry || printer.isMqttTelemetry());
        if (!webhook && !mqtt) {
            return;
        }
        executor.submit(() -> {
            byte[] body = serialise(payload);
            if (webhook) {
                deliverWebhook(printer, body);
            }
            if (mqtt) {
                // Alerts must arrive; a lost telemetry sample does not matter.
                deliverMqtt(printer, body, telemetry ? 0 : 1);
            }
        });
    }

    /**
     * Sends a test event synchronously and reports what happened per channel, so the UI can say
     * "webhook OK, MQTT: connection refused" instead of "check the logs".
     */
    public List<String> test(Printer printer) {
        Map<String, Object> payload = envelope(EVENT_TEST, printer);
        payload.put("message", "MoonEye test notification");
        byte[] body = serialise(payload);
        List<String> results = new ArrayList<>();
        if (hasWebhook(printer)) {
            String error = deliverWebhook(printer, body);
            results.add("webhook: " + (error == null ? "OK" : error));
        }
        if (hasMqtt(printer)) {
            String error = deliverMqtt(printer, body, 1);
            results.add("mqtt: " + (error == null ? "OK" : error));
        }
        if (results.isEmpty()) {
            results.add("No notification channel is configured.");
        }
        return results;
    }

    private static boolean hasWebhook(Printer p) {
        return p.getWebhookUrl() != null && !p.getWebhookUrl().isBlank();
    }

    private static boolean hasMqtt(Printer p) {
        return p.getMqttBroker() != null && !p.getMqttBroker().isBlank()
                && p.getMqttTopic() != null && !p.getMqttTopic().isBlank();
    }

    private byte[] serialise(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise notification", e);
        }
    }

    /** @return null on success, otherwise a short description of the failure */
    private String deliverWebhook(Printer printer, byte[] body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(printer.getWebhookUrl().trim()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return null;
            }
            logger.warn("Webhook for printer {} returned HTTP {}", printer.getName(), response.statusCode());
            return "HTTP " + response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            logger.warn("Webhook for printer {} failed: {}", printer.getName(), e.toString());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** @return null on success, otherwise a short description of the failure */
    private String deliverMqtt(Printer printer, byte[] body, int qos) {
        String broker = printer.getMqttBroker().trim();
        if (!broker.contains("://")) {
            broker = "tcp://" + broker;
        }
        String clientId = printer.getMqttClientId() != null && !printer.getMqttClientId().isBlank()
                ? printer.getMqttClientId().trim()
                : "mooneye-" + printer.getId();

        MqttClient client = null;
        try {
            client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setConnectionTimeout(5);
            options.setCleanSession(true);
            if (printer.getMqttUsername() != null && !printer.getMqttUsername().isBlank()) {
                options.setUserName(printer.getMqttUsername());
                if (printer.getMqttPassword() != null) {
                    options.setPassword(printer.getMqttPassword().toCharArray());
                }
            }
            client.connect(options);
            MqttMessage message = new MqttMessage(body);
            message.setQos(qos);
            client.publish(printer.getMqttTopic().trim(), message);
            return null;
        } catch (MqttException e) {
            logger.warn("MQTT for printer {} failed: {}", printer.getName(), e.toString());
            return "MQTT error " + e.getReasonCode() + ": " + e.getMessage();
        } finally {
            if (client != null) {
                try {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                    client.close();
                } catch (MqttException ignored) {
                    // nothing useful to do while tearing down
                }
            }
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
