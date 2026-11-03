package h848.software.mooneye.detection;

import java.util.Comparator;
import java.util.List;

/**
 * Turns a stream of per-frame detections into a WARNING / FAILURE decision.
 * <p>
 * A straight port of Obico's {@code lib/prediction.py} and {@code lib/failure_detection.py}.
 * Kept deliberately faithful: the thresholds in {@link PredictionParams} were fitted to exactly
 * this arithmetic, so "improving" it locally would silently invalidate them.
 * <p>
 * The idea in one sentence: a print is failing when the fast average of "how much failure-like
 * stuff is in the frame" rises clearly above both this printer's long-term normal and this
 * print's own recent level.
 */
public final class FailurePredictor {

    public enum Decision {
        NONE, WARNING, FAILURE
    }

    private final PredictionParams params;

    public FailurePredictor(PredictionParams params) {
        this.params = params;
    }

    public PredictionParams params() {
        return params;
    }

    /** Folds one analysed frame into the state. */
    public void update(PredictionState s, List<Detection> detections) {
        double p = frameScore(detections);
        s.setCurrentP(p);
        s.setCurrentFrameNum(s.getCurrentFrameNum() + 1);
        s.setLifetimeFrameNum(s.getLifetimeFrameNum() + 1);
        s.setEwmMean(p * params.ewmAlpha() + s.getEwmMean() * (1 - params.ewmAlpha()));
        s.setRollingMeanShort(nextRollingMean(p, s.getRollingMeanShort(),
                s.getCurrentFrameNum(), params.rollingWinShort()));
        s.setRollingMeanLong(nextRollingMean(p, s.getRollingMeanLong(),
                s.getLifetimeFrameNum(), params.rollingWinLong()));
    }

    /** Sum of box confidences: several mid-confidence boxes count as much as one confident one. */
    public static double frameScore(List<Detection> detections) {
        double sum = 0;
        for (Detection d : detections) {
            sum += d.confidence();
        }
        return sum;
    }

    public Decision decide(PredictionState s, double sensitivity) {
        if (isFailing(s, sensitivity, params.escalatingFactor())) {
            return Decision.FAILURE;
        }
        if (isFailing(s, sensitivity, 1.0)) {
            return Decision.WARNING;
        }
        return Decision.NONE;
    }

    boolean isFailing(PredictionState s, double sensitivity, double escalatingFactor) {
        if (s.getCurrentFrameNum() < params.initSafeFrames()) {
            return false;
        }
        double adjusted = (s.getEwmMean() - s.getRollingMeanLong()) * sensitivity / escalatingFactor;
        if (adjusted < params.thresholdLow()) {
            return false;
        }
        if (adjusted > params.thresholdHigh()) {
            return true;
        }
        return adjusted > (s.getRollingMeanShort() - s.getRollingMeanLong()) * params.rollingMeanShortMultiple();
    }

    /**
     * A 0..1 score for display: 0..1/3 is normal, 1/3..2/3 means a warning has fired, above 2/3
     * means the failure line was crossed. Mirrors Obico's {@code calc_normalized_p}, so it is
     * already relative to this printer's normal and does not need a baseline drawn next to it.
     */
    public double normalizedScore(PredictionState s, double sensitivity) {
        double warn = (s.getRollingMeanShort() - s.getRollingMeanLong()) * params.rollingMeanShortMultiple();
        warn = Math.min(params.thresholdHigh(), Math.max(params.thresholdLow(), warn));
        double fail = warn * params.escalatingFactor();

        double p = (s.getEwmMean() - s.getRollingMeanLong()) * sensitivity;
        if (p > fail) {
            return scale(p, fail, fail * 1.5, 2.0 / 3.0, 1.0);
        } else if (p > warn) {
            return scale(p, warn, fail, 1.0 / 3.0, 2.0 / 3.0);
        }
        return scale(p, 0, warn, 0, 1.0 / 3.0);
    }

    /** How many frames are left before this print starts being judged at all. */
    public int warmupFramesRemaining(PredictionState s) {
        return Math.max(0, params.initSafeFrames() - s.getCurrentFrameNum());
    }

    /** Whether a frame is worth keeping on disk regardless of the timelapse interval. */
    public boolean isSuspicious(PredictionState s) {
        // Obico keeps "high prediction" frames above the same fraction of the low threshold.
        return s.getCurrentP() > params.thresholdLow() * 0.2;
    }

    /** Boxes a human should see, strongest first. */
    public List<Detection> visible(List<Detection> detections) {
        return detections.stream()
                .filter(d -> d.confidence() > params.visualizationThreshold())
                .sorted(Comparator.comparingDouble(Detection::confidence).reversed())
                .toList();
    }

    /**
     * Streaming approximation of a rolling mean: an ordinary mean until the window is full, an
     * exponential one with weight 1/window after that.
     */
    static double nextRollingMean(double p, double current, int count, int window) {
        return current + (p - current) / (window <= count ? window : count + 1);
    }

    private static double scale(double v, double oldMin, double oldMax, double newMin, double newMax) {
        if (oldMax == oldMin) {
            return newMin;
        }
        double out = (v - oldMin) * (newMax - newMin) / (oldMax - oldMin) + newMin;
        return Math.min(newMax, Math.max(newMin, out));
    }
}
