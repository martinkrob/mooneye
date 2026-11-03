package h848.software.mooneye.detection;

/**
 * One box the model reported, in fractions of the <em>full</em> camera frame (0..1) - already
 * mapped back from the region of interest - so it can be drawn over the original image.
 */
public record Detection(float confidence, float x1, float y1, float x2, float y2) {

    public float area() {
        return Math.max(0f, x2 - x1) * Math.max(0f, y2 - y1);
    }

    /** Intersection over union, for non-maximum suppression. */
    public float iou(Detection o) {
        float ix = Math.max(0f, Math.min(x2, o.x2) - Math.max(x1, o.x1));
        float iy = Math.max(0f, Math.min(y2, o.y2) - Math.max(y1, o.y1));
        float inter = ix * iy;
        float union = area() + o.area() - inter;
        return union <= 0f ? 0f : inter / union;
    }
}
