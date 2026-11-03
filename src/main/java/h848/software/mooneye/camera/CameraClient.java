package h848.software.mooneye.camera;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.imageio.ImageIO;

/**
 * Fetches single JPEG frames from a webcam snapshot URL (crowsnest, ustreamer, mjpg-streamer...).
 */
public class CameraClient {

    /** A snapshot larger than this is not a webcam frame; refuse it rather than exhaust memory. */
    private static final int MAX_FRAME_BYTES = 20 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** A fetched frame: the original bytes (stored and served as-is) and the decoded image. */
    public record Frame(byte[] jpeg, BufferedImage image, long capturedAt) {
    }

    public Frame fetch(String webcamUrl) throws IOException {
        byte[] bytes = fetchBytes(webcamUrl);
        long capturedAt = System.currentTimeMillis();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Webcam returned something that is not an image (" + bytes.length + " bytes)");
        }
        return new Frame(bytes, image, capturedAt);
    }

    public byte[] fetchBytes(String webcamUrl) throws IOException {
        if (webcamUrl == null || webcamUrl.isBlank()) {
            throw new IOException("No webcam URL configured");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(toSnapshotUrl(webcamUrl.trim())))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Webcam returned HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > MAX_FRAME_BYTES) {
                throw new IOException("Webcam returned " + body.length + " bytes");
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching webcam frame", e);
        }
    }

    /**
     * People paste the stream URL as often as the snapshot one. A stream never ends, so asking for
     * it would just time out - rewrite the usual mjpg-streamer/ustreamer form instead.
     */
    static String toSnapshotUrl(String url) {
        return url.replace("action=stream", "action=snapshot");
    }
}
