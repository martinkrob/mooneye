package h848.software.mooneye.model;

/**
 * A rectangle within a camera frame, in fractions of the frame size (0..1), so it survives a
 * change of camera resolution.
 */
public record Roi(double x, double y, double w, double h) {

    /** Below this a crop is almost certainly a mistake (a click instead of a drag in the UI). */
    private static final double MIN_SIZE = 0.05;

    /** Clamps into the frame and rejects degenerate sizes. */
    public static Roi of(double x, double y, double w, double h) {
        double cx = clamp01(x);
        double cy = clamp01(y);
        double cw = Math.min(clamp01(w), 1.0 - cx);
        double ch = Math.min(clamp01(h), 1.0 - cy);
        if (cw < MIN_SIZE || ch < MIN_SIZE) {
            throw new IllegalArgumentException("Region of interest is too small: " + w + " x " + h);
        }
        return new Roi(cx, cy, cw, ch);
    }

    public boolean isFullFrame() {
        return x <= 0 && y <= 0 && w >= 1 && h >= 1;
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0 : Math.clamp(v, 0.0, 1.0);
    }
}
