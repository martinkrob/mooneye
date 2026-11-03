package h848.software.mooneye.detection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelStoreTest {

    private static final byte[] MODEL = new byte[2_000_000];

    static {
        new Random(42).nextBytes(MODEL);
    }

    @TempDir
    Path dir;

    private HttpServer server;
    private String url;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.onnx", ex -> {
            ex.sendResponseHeaders(200, MODEL.length);
            ex.getResponseBody().write(MODEL);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.onnx";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void downloadsAndVerifiesAMissingModel() throws Exception {
        Path target = dir.resolve("models/m.onnx");
        Path got = new ModelStore(target, url, sha256(MODEL)).ensureAvailable();
        assertArrayEquals(MODEL, Files.readAllBytes(got));
        assertFalse(Files.exists(target.resolveSibling("m.onnx.part")));
    }

    @Test
    void aChecksumMismatchLeavesNothingBehind() {
        Path target = dir.resolve("m.onnx");
        ModelStore store = new ModelStore(target, url, "00".repeat(32));
        IOException e = assertThrows(IOException.class, store::ensureAvailable);
        assertTrue(e.getMessage().contains("checksum"));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("m.onnx.part")));
    }

    @Test
    void anExistingModelIsNotDownloadedAgain() throws Exception {
        Path target = dir.resolve("m.onnx");
        Files.write(target, MODEL);
        // Unreachable URL: if it tried to download, this would fail.
        new ModelStore(target, "http://127.0.0.1:1/none", null).ensureAvailable();
    }

    @Test
    void downloadingCanBeDisabled() {
        ModelStore store = new ModelStore(dir.resolve("m.onnx"), null, null);
        assertThrows(IOException.class, store::ensureAvailable);
    }
}
