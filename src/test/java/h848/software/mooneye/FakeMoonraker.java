package h848.software.mooneye;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Just enough of Moonraker's HTTP API for the tests: printer info, the object query and the
 * G-code endpoint. The print state is set directly by the test.
 */
public final class FakeMoonraker implements AutoCloseable {

    private final HttpServer server;
    public volatile String printState = "standby";
    public volatile String filename = "";
    public volatile double printDuration;
    public volatile double filamentUsed;
    /** 0..1, as Klipper's display_status reports it. */
    public volatile double progress = 0.5;
    public final List<String> gcode = new CopyOnWriteArrayList<>();

    public FakeMoonraker() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/printer/info", ex ->
                reply(ex, "{\"result\":{\"state\":\"ready\",\"state_message\":\"Printer is ready\"}}"));
        server.createContext("/printer/objects/query", ex -> reply(ex, String.format(Locale.US, """
                {"result":{"status":{
                  "print_stats":{"state":"%s","filename":"%s","print_duration":%.1f,"filament_used":%.1f},
                  "display_status":{"progress":%.4f},
                  "extruder":{"temperature":210.0,"target":210.0,"power":0.4},
                  "heater_bed":{"temperature":60.0,"target":60.0},
                  "toolhead":{"position":[10,20,1.2,0]},
                  "fan":{"speed":1.0},
                  "motion_report":{"live_position":[10,20,1.2,0],"live_velocity":50.0}
                }}}""", printState, filename, printDuration, filamentUsed, progress)));
        server.createContext("/printer/gcode/script", ex -> {
            gcode.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, "{\"result\":\"ok\"}");
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void printing(String file, double duration) {
        printState = "printing";
        filename = file;
        printDuration = duration;
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
