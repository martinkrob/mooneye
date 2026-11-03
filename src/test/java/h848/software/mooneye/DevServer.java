package h848.software.mooneye;

import com.sun.net.httpserver.HttpServer;
import h848.software.mooneye.config.AppConfig;
import h848.software.mooneye.db.Database;
import h848.software.mooneye.model.Printer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Runs MoonEye against simulated printers, for working on the UI without real hardware.
 * <p>
 * Starts three fake printers - one printing, one idle, one unreachable - and a fake camera, then
 * the real application on port 8090 (YoloRaker may still be on 8080; override with
 * {@code -Dmooneye.port}) with its data in {@code target/dev-data}. Open {@code http://localhost:8090}.
 * <p>
 * The printing one advances by itself. Its camera shows an empty bed; to stage a failure, open
 * {@code http://127.0.0.1:<camera port>/tangle?on=true} (the port is printed at startup).
 * Detection needs the real model: pass {@code -Dmooneye.model=/path/to/obico-failure-gen1.onnx},
 * otherwise the default data path is used and the model is downloaded on first start.
 */
public final class DevServer {

    private static volatile boolean tangle;

    private DevServer() {
    }

    public static void main(String[] args) throws Exception {
        FakeMoonraker printing = new FakeMoonraker();
        printing.printing("Voron_Cube_PLA_2h12m.gcode", 0);
        printing.progress = 0;
        FakeMoonraker idle = new FakeMoonraker();

        HttpServer camera = startCamera();
        String cameraUrl = "http://127.0.0.1:" + camera.getAddress().getPort();

        // The printing printer moves on by itself: one second per second, a 2 h print.
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            if ("printing".equals(printing.printState)) {
                printing.printDuration += 1;
                printing.filamentUsed += 7;
                printing.progress = Math.min(1.0, printing.printDuration / 7200.0);
            }
        }, 1, 1, TimeUnit.SECONDS);

        Path data = Path.of("target/dev-data");
        Path model = Path.of(System.getProperty("mooneye.model", "data/models/" + AppConfig.DEFAULT_MODEL_FILE));
        int port = Integer.getInteger("mooneye.port", 8090);
        AppConfig config = new AppConfig(port, data, data.resolve("mooneye"), model,
                AppConfig.DEFAULT_MODEL_URL, AppConfig.DEFAULT_MODEL_SHA256, 1000);
        Database db = MoonEye.start(config);

        if (db.printers().findAll().isEmpty()) {
            db.printers().insert(printer("Voron 2.4 (simulace)", printing.url(), cameraUrl + "/snapshot"));
            db.printers().insert(printer("Prusa MK4 (nečinná)", idle.url(), cameraUrl + "/snapshot"));
            db.printers().insert(printer("Ender 3 (offline)", "http://127.0.0.1:1", null));
        } else {
            // Fake servers get new ports on every start; point the seeded printers at them again.
            for (Printer p : db.printers().findAll()) {
                if (p.getName().contains("simulace")) {
                    p.setMoonrakerUrl(printing.url());
                    p.setWebcamUrl(cameraUrl + "/snapshot");
                } else if (p.getName().contains("nečinná")) {
                    p.setMoonrakerUrl(idle.url());
                    p.setWebcamUrl(cameraUrl + "/snapshot");
                }
                db.printers().update(p);
            }
        }

        System.out.println();
        System.out.println("MoonEye dev server:  http://localhost:" + port);
        System.out.println("Stage a failure:     " + cameraUrl + "/tangle?on=true");
        System.out.println("Clear it:            " + cameraUrl + "/tangle?on=false");
        System.out.println();
    }

    private static Printer printer(String name, String moonraker, String webcam) {
        Printer p = new Printer();
        p.setId(UUID.randomUUID().toString());
        p.setName(name);
        p.setMoonrakerUrl(moonraker);
        p.setWebcamUrl(webcam);
        return p;
    }

    private static HttpServer startCamera() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/snapshot", ex -> {
            byte[] jpeg = frame(tangle);
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, jpeg.length);
            ex.getResponseBody().write(jpeg);
            ex.close();
        });
        server.createContext("/tangle", ex -> {
            String q = ex.getRequestURI().getQuery();
            tangle = q == null || !q.contains("on=false");
            byte[] body = ("tangle " + (tangle ? "on" : "off") + "\n").getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    /** A dark enclosure with a bed, and optionally a tangle of white filament on it. */
    private static byte[] frame(boolean withTangle) throws IOException {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(35, 35, 40));
        g.fillRect(0, 0, 1280, 720);
        g.setColor(new Color(70, 70, 75));
        g.fillPolygon(new int[]{200, 1080, 1230, 50}, new int[]{380, 380, 710, 710}, 4);
        g.setColor(new Color(120, 120, 128));
        g.fillRect(560, 470, 120, 80); // the part being printed
        if (withTangle) {
            Random r = new Random(System.currentTimeMillis() / 10_000);
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(240, 240, 240));
            for (int i = 0; i < 250; i++) {
                double x = 520 + r.nextGaussian() * 90;
                double y = 520 + r.nextGaussian() * 50;
                g.draw(new CubicCurve2D.Double(x, y,
                        x + r.nextGaussian() * 120, y + r.nextGaussian() * 90,
                        x + r.nextGaussian() * 120, y + r.nextGaussian() * 90,
                        x + r.nextGaussian() * 80, y + r.nextGaussian() * 60));
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
