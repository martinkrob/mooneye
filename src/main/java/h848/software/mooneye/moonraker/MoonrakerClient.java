package h848.software.mooneye.moonraker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import h848.software.mooneye.model.Printer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MoonrakerClient {
    private static final Logger logger = LoggerFactory.getLogger(MoonrakerClient.class);

    // One shared object list for both the fast and the slow path. Trimming it for the poller was
    // considered and dropped: the cost of a query is dominated by the round-trip, not the payload,
    // and a single object list keeps a single parsing path.
    private static final String OBJECTS_QUERY =
            "/printer/objects/query?print_stats&display_status&extruder&heater_bed&toolhead&fan&motion_report";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public MoonrakerClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Full status: Klipper info + all printer objects. Costs two HTTP round-trips.
     */
    public PrinterTelemetry getTelemetry(Printer printer) {
        PrinterTelemetry telemetry = new PrinterTelemetry();
        try {
            fetchKlipperInfo(printer, telemetry);
            fetchObjects(printer, telemetry);
        } catch (Exception e) {
            logger.warn("Failed to connect to printer {}: {}", printer.getName(), e.getMessage());
            telemetry.setKlipperState("offline");
            telemetry.setKlipperMessage("Connection refused or timeout: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return telemetry;
    }

    /**
     * Printer objects only - a single HTTP round-trip, without the /printer/info call.
     * This is the fast path used by the telemetry poller, which runs at ~1 Hz and does not
     * need the Klipper host state on every tick.
     *
     * @return the telemetry, or {@code null} if the printer could not be reached. The returned
     *         object has no {@code klipperState} set - use {@link #getTelemetry} when you need it.
     */
    public PrinterTelemetry queryObjects(Printer printer) {
        PrinterTelemetry telemetry = new PrinterTelemetry();
        try {
            fetchObjects(printer, telemetry);
            return telemetry;
        } catch (Exception e) {
            // Deliberately quiet: at 1 Hz an unreachable printer would flood the log.
            // The caller tracks the failure streak and logs the transitions instead.
            logger.debug("Object query failed for printer {}: {}", printer.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort variant of the Klipper info call for the telemetry poller, which refreshes the
     * host state on a slower cadence than the object query and must never fail a whole tick over it.
     *
     * @return true if the state was filled in
     */
    public boolean queryKlipperInfo(Printer printer, PrinterTelemetry target) {
        try {
            fetchKlipperInfo(printer, target);
            return true;
        } catch (Exception e) {
            logger.debug("Klipper info query failed for printer {}: {}", printer.getName(), e.getMessage());
            return false;
        }
    }

    private void fetchKlipperInfo(Printer printer, PrinterTelemetry telemetry) throws Exception {
        String url = formatBaseUrl(printer.getMoonrakerUrl()) + "/printer/info";
        HttpResponse<String> res = httpClient.send(buildRequest(url, printer.getApiKey()),
                HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 200) {
            JsonNode result = mapper.readTree(res.body()).path("result");
            telemetry.setKlipperState(result.path("state").asText("unknown"));
            telemetry.setKlipperMessage(result.path("state_message").asText(""));
        } else {
            telemetry.setKlipperState("error");
            telemetry.setKlipperMessage("HTTP " + res.statusCode());
        }
    }

    private void fetchObjects(Printer printer, PrinterTelemetry telemetry) throws Exception {
        String url = formatBaseUrl(printer.getMoonrakerUrl()) + OBJECTS_QUERY;
        HttpResponse<String> res = httpClient.send(buildRequest(url, printer.getApiKey()),
                HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            throw new RuntimeException("Object query returned HTTP " + res.statusCode());
        }

        JsonNode status = mapper.readTree(res.body()).path("result").path("status");

        // Print Stats
        JsonNode printStats = status.path("print_stats");
        telemetry.setPrintState(printStats.path("state").asText("unknown"));
        telemetry.setFilename(printStats.path("filename").asText(""));
        telemetry.setPrintDuration(printStats.path("print_duration").asDouble(0.0));
        // Cumulative and monotonic for the duration of a print - safe to difference over a window,
        // unlike the extruder axis, which G92 E0 and relative extrusion both reset.
        telemetry.setFilamentUsed(printStats.path("filament_used").asDouble(0.0));

        // Display Status (Progress)
        JsonNode displayStatus = status.path("display_status");
        telemetry.setProgress(displayStatus.path("progress").asDouble(0.0) * 100.0); // Convert to percentage

        // Temperatures
        JsonNode extruder = status.path("extruder");
        telemetry.setExtruderTemp(extruder.path("temperature").asDouble(0.0));
        telemetry.setExtruderTarget(extruder.path("target").asDouble(0.0));
        telemetry.setExtruderPower(extruder.path("power").asDouble(0.0));

        JsonNode heaterBed = status.path("heater_bed");
        telemetry.setBedTemp(heaterBed.path("temperature").asDouble(0.0));
        telemetry.setBedTarget(heaterBed.path("target").asDouble(0.0));

        // Toolhead Position (planned / gcode position)
        JsonNode pos = status.path("toolhead").path("position");
        if (pos.isArray() && pos.size() >= 3) {
            telemetry.setX(pos.get(0).asDouble(0.0));
            telemetry.setY(pos.get(1).asDouble(0.0));
            telemetry.setZ(pos.get(2).asDouble(0.0));
        }

        // Fan
        JsonNode fan = status.path("fan");
        telemetry.setFanSpeed(fan.path("speed").asDouble(0.0) * 100.0); // Convert to percentage

        // Motion Report: the real-time position and speed.
        JsonNode motionReport = status.path("motion_report");
        telemetry.setPrintSpeed(motionReport.path("live_velocity").asDouble(0.0));

        JsonNode livePos = motionReport.path("live_position");
        if (livePos.isArray() && livePos.size() >= 3) {
            telemetry.setLiveX(livePos.get(0).asDouble(0.0));
            telemetry.setLiveY(livePos.get(1).asDouble(0.0));
            telemetry.setLiveZ(livePos.get(2).asDouble(0.0));
        } else {
            // motion_report should always be present on a modern Klipper, but fall back to the
            // planned position rather than reporting the toolhead as parked at the origin.
            telemetry.setLiveX(telemetry.getX());
            telemetry.setLiveY(telemetry.getY());
            telemetry.setLiveZ(telemetry.getZ());
        }
    }

    /** Pauses the print through Klipper's own PAUSE macro, so user-defined park/retract logic runs. */
    public boolean pausePrint(Printer printer) {
        return sendGcodeScript(printer, "PAUSE");
    }

    /**
     * Shows a message on the printer's display and in Mainsail/Fluidd. The text is reduced to a
     * single line of printable characters - a newline in a G-code script starts a new command.
     */
    public boolean sendM117(Printer printer, String message) {
        String safe = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return sendGcodeScript(printer, "M117 " + safe);
    }

    private boolean sendGcodeScript(Printer printer, String script) {
        try {
            String payload = mapper.writeValueAsString(java.util.Map.of("script", script));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(formatBaseUrl(printer.getMoonrakerUrl()) + "/printer/gcode/script"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (printer.getApiKey() != null && !printer.getApiKey().isBlank()) {
                requestBuilder.header("X-Api-Key", printer.getApiKey().trim());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            logger.error("G-code '{}' failed on printer {}. HTTP {}: {}",
                    script, printer.getName(), response.statusCode(), response.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.error("Could not send G-code '{}' to printer {}: {}", script, printer.getName(), e.getMessage());
            return false;
        }
    }

    private HttpRequest buildRequest(String url, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET();
                
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.header("X-Api-Key", apiKey.trim());
        }
        return builder.build();
    }

    /**
     * Normalises what a user types into a base URL: a bare host gets {@code http://} and Moonraker's
     * default port 7125; a trailing slash is dropped.
     */
    public static String formatBaseUrl(String address) {
        String url = address.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri = URI.create(url);
        if (uri.getPort() == -1 && (uri.getPath() == null || uri.getPath().isEmpty())) {
            url = url + ":7125";
        }
        return url;
    }
}
