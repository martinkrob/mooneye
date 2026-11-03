package h848.software.mooneye.detection;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes sure the failure model is on disk, downloading it on first start.
 * <p>
 * The model is not baked into the image: it is ~200 MB, it is Obico's work with its own terms,
 * and keeping it in the data volume means an image update does not re-download it. Downloads go
 * to a temporary file and are checksum-verified before being moved into place, so an interrupted
 * or tampered download never ends up being loaded.
 */
public final class ModelStore {

    private static final Logger logger = LoggerFactory.getLogger(ModelStore.class);

    /** The real file is ~200 MB; anything this small is an error page, not a model. */
    private static final long MIN_MODEL_BYTES = 1_000_000;

    private final Path modelPath;
    private final String url;
    private final String sha256;

    public ModelStore(Path modelPath, String url, String sha256) {
        this.modelPath = modelPath;
        this.url = url;
        this.sha256 = sha256;
    }

    public Path path() {
        return modelPath;
    }

    /**
     * Returns the model path, downloading the file first if it is missing.
     *
     * @throws IOException if the file is missing and cannot be obtained
     */
    public Path ensureAvailable() throws IOException {
        if (Files.isRegularFile(modelPath) && Files.size(modelPath) >= MIN_MODEL_BYTES) {
            return modelPath;
        }
        if (url == null) {
            throw new IOException("Model not found at " + modelPath
                    + " and downloading is disabled (MOONEYE_MODEL_URL is empty).");
        }
        download();
        return modelPath;
    }

    private void download() throws IOException {
        Files.createDirectories(modelPath.toAbsolutePath().getParent());
        Path tmp = modelPath.resolveSibling(modelPath.getFileName() + ".part");
        logger.info("Downloading failure model from {} (about 200 MB, first start only)...", url);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Model download failed: HTTP " + response.statusCode());
            }
            MessageDigest digest = sha256Digest();
            try (InputStream in = new DigestInputStream(response.body(), digest)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(tmp);
            if (size < MIN_MODEL_BYTES) {
                throw new IOException("Downloaded model is only " + size + " bytes - not a model.");
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (sha256 != null && !sha256.equalsIgnoreCase(actual)) {
                throw new IOException("Model checksum mismatch: expected " + sha256 + ", got " + actual);
            }
            Files.move(tmp, modelPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Model saved to {} ({} MB, sha256 {})", modelPath, size / 1_000_000,
                    sha256 != null ? "verified" : "not checked");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
