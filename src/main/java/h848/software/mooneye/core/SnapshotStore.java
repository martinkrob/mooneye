package h848.software.mooneye.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frames kept on disk during a print: {@code <root>/<printerId>/<jobId>/<epochMillis>.jpg}.
 * <p>
 * They serve as a timelapse now and as raw material for evaluating or tuning the model later.
 * Every name that reaches a path comes from a strict pattern, so a request can never escape the
 * snapshot folder.
 */
public final class SnapshotStore {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotStore.class);

    private static final Pattern PRINTER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern FILE_NAME = Pattern.compile("\\d{1,20}\\.jpg");

    private final Path root;

    public SnapshotStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public void save(String printerId, long jobId, long timestamp, byte[] jpeg) {
        try {
            Path dir = jobDir(printerId, jobId);
            Files.createDirectories(dir);
            Files.write(dir.resolve(timestamp + ".jpg"), jpeg);
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Could not store snapshot for printer {} job {}: {}", printerId, jobId, e.getMessage());
        }
    }

    /** File names (timestamps) for a job, oldest first. */
    public List<String> list(String printerId, long jobId) {
        Path dir = jobDir(printerId, jobId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> FILE_NAME.matcher(n).matches())
                    .sorted((a, b) -> Long.compare(stamp(a), stamp(b)))
                    .toList();
        } catch (IOException e) {
            logger.warn("Could not list snapshots in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    public Optional<byte[]> read(String printerId, long jobId, String fileName) {
        if (!FILE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path file = jobDir(printerId, jobId).resolve(fileName);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path jobDir(String printerId, long jobId) {
        if (!PRINTER_ID.matcher(printerId).matches()) {
            throw new IllegalArgumentException("Invalid printer id");
        }
        return root.resolve(printerId).resolve(Long.toString(jobId));
    }

    private static long stamp(String fileName) {
        return Long.parseLong(fileName.substring(0, fileName.length() - 4));
    }
}
