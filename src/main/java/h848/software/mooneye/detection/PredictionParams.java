package h848.software.mooneye.detection;

/**
 * Hyperparameters of the failure decision, taken verbatim from Obico's open-source server
 * ({@code backend/config/settings.py}, {@code FD_1ST_GEN_PARAMS}). Obico tuned them by grid search
 * on a large set of real prints, for this model and for a detection every 10 seconds - so the
 * cadence is part of the tuning, not an independent knob.
 *
 * @param ewmSpan                  span of the exponential moving average of the frame score
 * @param rollingWinShort          frames in the per-print rolling mean (~52 min at 10 s)
 * @param rollingWinLong           frames in the per-printer long-term mean (~20 h at 10 s)
 * @param thresholdLow             below this the print is never considered failing
 * @param thresholdHigh            above this it always is
 * @param initSafeFrames           frames at the start of a print that are never judged (~5 min)
 * @param rollingMeanShortMultiple in between, failing if the EWM exceeds the short mean by this factor
 * @param escalatingFactor         how much stronger the evidence must be to pause rather than warn
 * @param boxThreshold             minimum confidence for a box to count at all
 * @param nmsThreshold             IoU above which overlapping boxes are merged
 * @param visualizationThreshold   boxes below this are not worth drawing for a human
 */
public record PredictionParams(
        int ewmSpan,
        int rollingWinShort,
        int rollingWinLong,
        double thresholdLow,
        double thresholdHigh,
        int initSafeFrames,
        double rollingMeanShortMultiple,
        double escalatingFactor,
        float boxThreshold,
        float nmsThreshold,
        float visualizationThreshold) {

    /** Obico 1st generation, matching the {@code obico-failure-gen1.onnx} model. */
    public static final PredictionParams OBICO_GEN1 = new PredictionParams(
            12, 310, 7200, 0.38, 0.78, 30, 3.8, 1.75, 0.08f, 0.45f, 0.2f);

    /** The detection period the parameters were tuned for. */
    public static final int CYCLE_SECONDS = 10;

    public double ewmAlpha() {
        return 2.0 / (ewmSpan + 1);
    }
}
