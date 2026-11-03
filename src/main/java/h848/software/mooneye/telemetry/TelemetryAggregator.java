package h848.software.mooneye.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a short rolling history of {@link TelemetrySample}s per printer and folds arbitrary
 * slices of it into a {@link TelemetryWindow} on demand.
 * <p>
 * Windows are computed lazily rather than maintained incrementally: a buffer holds at most a
 * minute of samples (~60 records at 1 Hz), so a full pass costs nothing and avoids a whole class
 * of bugs around evicting values out of a running aggregate.
 */
public final class TelemetryAggregator {

    /**
     * How much history to keep. Longer than the 10 s detection window so that a late camera
     * frame, or a future consumer wanting a longer baseline, still finds its samples.
     */
    private static final long RETAIN_MS = 60_000;

    private final Map<String, Deque<TelemetrySample>> buffers = new ConcurrentHashMap<>();
    private final long expectedIntervalMs;

    public TelemetryAggregator(long expectedIntervalMs) {
        this.expectedIntervalMs = expectedIntervalMs;
    }

    /** Records a sample. Called from the polling threads. */
    public void accept(String printerId, TelemetrySample sample) {
        Deque<TelemetrySample> buffer = buffers.computeIfAbsent(printerId, id -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(sample);
            long cutoff = sample.ts() - RETAIN_MS;
            while (!buffer.isEmpty() && buffer.peekFirst().ts() < cutoff) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * Aggregates the samples in {@code (endTs - windowMs, endTs]}.
     * <p>
     * The window <em>ends</em> at the given instant rather than starting there: it is meant to
     * describe what the printer was doing in the run-up to a camera frame, so callers pass the
     * moment the frame was captured.
     *
     * @return empty if no samples fall inside the range
     */
    public Optional<TelemetryWindow> windowEndingAt(String printerId, long endTs, long windowMs) {
        Deque<TelemetrySample> buffer = buffers.get(printerId);
        if (buffer == null) {
            return Optional.empty();
        }

        long startTs = endTs - windowMs;
        List<TelemetrySample> slice = new ArrayList<>();
        synchronized (buffer) {
            for (TelemetrySample s : buffer) {
                if (s.ts() > startTs && s.ts() <= endTs) {
                    slice.add(s);
                }
            }
        }

        if (slice.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TelemetryWindow.of(slice, startTs, endTs, expectedIntervalMs));
    }

    /** The most recent sample, if any. Cheap enough to serve on every UI poll. */
    public Optional<TelemetrySample> latest(String printerId) {
        Deque<TelemetrySample> buffer = buffers.get(printerId);
        if (buffer == null) {
            return Optional.empty();
        }
        synchronized (buffer) {
            return Optional.ofNullable(buffer.peekLast());
        }
    }

    /** Drops all history for a printer that is being deleted or disabled. */
    public void forget(String printerId) {
        buffers.remove(printerId);
    }

    public long getExpectedIntervalMs() {
        return expectedIntervalMs;
    }
}
