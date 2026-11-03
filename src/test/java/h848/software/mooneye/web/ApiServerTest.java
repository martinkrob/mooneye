package h848.software.mooneye.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import h848.software.mooneye.MoonEye;
import h848.software.mooneye.alerts.Notifier;
import h848.software.mooneye.camera.CameraClient;
import h848.software.mooneye.core.PrintMonitor;
import h848.software.mooneye.core.RetentionService;
import h848.software.mooneye.core.SnapshotStore;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.detection.ModelStore;
import h848.software.mooneye.moonraker.MoonrakerClient;
import h848.software.mooneye.telemetry.TelemetryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiServerTest {

    @TempDir
    Path dir;

    private final ObjectMapper json = MoonEye.jsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private Database db;
    private ApiServer api;
    private String base;

    @BeforeEach
    void start() {
        db = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        MoonrakerClient moonraker = new MoonrakerClient();
        TelemetryService telemetry = new TelemetryService(db, moonraker, 0);
        Notifier notifier = new Notifier(json);
        SnapshotStore snapshots = new SnapshotStore(dir.resolve("snapshots"));
        PrintMonitor monitor = new PrintMonitor(db, telemetry, moonraker, new CameraClient(),
                new ModelStore(dir.resolve("none.onnx"), null, null), notifier, snapshots, json);
        api = new ApiServer(db, telemetry, moonraker, new CameraClient(), monitor,
                new RetentionService(db, dir.resolve("snapshots")), snapshots, notifier, json, "test");
        api.start(0);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        api.stop();
        db.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theApiNeedsNoCredentials() throws Exception {
        assertEquals(200, send("GET", "/api/health", null).statusCode());
        assertEquals(200, send("GET", "/api/printers", null).statusCode());
        assertEquals(404, send("POST", "/api/auth/login", "{}").statusCode());
    }

    @Test
    void settingsRoundTrip() throws Exception {
        HttpResponse<String> saved = send("PUT", "/api/settings", "{\"retentionPrintCount\":7,\"snapshotIntervalSec\":30}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(7, json.readTree(saved.body()).get("retentionPrintCount").asInt());
        assertEquals(30, db.settings().load().getSnapshotIntervalSec());
    }

    @Test
    void printerSecretsAreWriteOnly() throws Exception {
        String body = """
                {"name":"Voron","moonrakerUrl":"192.168.1.50","apiKey":"k3y","mqttPassword":"pw",
                 "webcamUrl":"http://192.168.1.50/webcam/?action=snapshot","roiX":0.1,"roiY":0.1,"roiW":0.8,"roiH":0.8}
                """;
        HttpResponse<String> created = send("POST", "/api/printers", body);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode p = json.readTree(created.body());
        assertNotNull(p.get("id").asText());
        assertFalse(p.has("apiKey"));
        assertFalse(p.has("mqttPassword"));
        assertTrue(p.get("apiKeySet").asBoolean());
        assertEquals("PAUSE", p.get("onFailure").asText());
        assertEquals("k3y", db.printers().find(p.get("id").asText()).orElseThrow().getApiKey());
    }

    @Test
    void invalidPrinterIsABadRequest() throws Exception {
        assertEquals(400, send("POST", "/api/printers", "{\"moonrakerUrl\":\"x\"}").statusCode());
        assertEquals(400, send("POST", "/api/printers",
                "{\"name\":\"a\",\"moonrakerUrl\":\"x\",\"roiX\":0.1}").statusCode());
        assertEquals(400, send("POST", "/api/printers",
                "{\"name\":\"a\",\"moonrakerUrl\":\"x\",\"sensitivity\":5}").statusCode());
    }

    @Test
    void unknownPrinterIs404() throws Exception {
        assertEquals(404, send("GET", "/api/printers/nope/status", null).statusCode());
    }
}
