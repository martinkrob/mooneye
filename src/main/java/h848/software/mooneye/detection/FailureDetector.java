package h848.software.mooneye.detection;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import h848.software.mooneye.model.Roi;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Obico's failure model (YOLOv2, one class: "failure") on a camera frame.
 * <p>
 * The ONNX export already decodes the YOLO region layer, so the graph returns ready boxes:
 * <ul>
 *   <li>{@code boxes} [1, N, 1, 4] - x1, y1, x2, y2 as fractions of the input</li>
 *   <li>{@code confs} [1, N, classes] - per-class confidence</li>
 * </ul>
 * What is left to do here is preprocessing, thresholding and non-maximum suppression, matching
 * Obico's {@code ml_api/lib/onnx.py}.
 * <p>
 * Preprocessing deliberately stretches the frame to the square input instead of letterboxing it.
 * That is what Obico does, and therefore what the model was trained and tuned on; letterboxing
 * would be "more correct" for a model trained that way, but not for this one.
 * <p>
 * Thread-safe: inference is serialised per instance, and one instance is shared by all printers.
 * At ~0.1 s per frame on a desktop CPU against a 10 s cycle there is no reason to run several.
 */
public final class FailureDetector implements FrameDetector {

    private static final Logger logger = LoggerFactory.getLogger(FailureDetector.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;
    private final float boxThreshold;
    private final float nmsThreshold;

    public FailureDetector(Path modelPath, PredictionParams params) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        long start = System.nanoTime();
        this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        this.boxThreshold = params.boxThreshold();
        this.nmsThreshold = params.nmsThreshold();

        Map.Entry<String, NodeInfo> input = session.getInputInfo().entrySet().iterator().next();
        this.inputName = input.getKey();
        long[] shape = ((TensorInfo) input.getValue().getInfo()).getShape();
        if (shape.length != 4 || shape[1] != 3) {
            throw new IllegalArgumentException("Expected an NCHW RGB input, got " + java.util.Arrays.toString(shape));
        }
        this.inputHeight = (int) shape[2];
        this.inputWidth = (int) shape[3];

        if (!session.getOutputNames().containsAll(List.of("boxes", "confs"))) {
            throw new IllegalArgumentException("Model outputs " + session.getOutputNames()
                    + " - expected the Obico export with 'boxes' and 'confs'.");
        }
        logger.info("Failure model loaded from {} in {} ms (input {}x{})",
                modelPath, (System.nanoTime() - start) / 1_000_000, inputWidth, inputHeight);
    }

    /** Result of one inference, with the time it took for diagnostics. */
    public record Result(List<Detection> detections, int inferenceMs) {
    }

    /**
     * Analyses a frame.
     *
     * @param roi region to analyse, or null for the whole frame. Boxes are returned in
     *            full-frame coordinates either way.
     */
    @Override
    public synchronized Result detect(BufferedImage frame, Roi roi) throws OrtException {
        long start = System.nanoTime();
        Roi area = roi != null ? roi : new Roi(0, 0, 1, 1);
        BufferedImage input = crop(frame, area);

        float[] chw = toChw(input, inputWidth, inputHeight);
        List<Detection> raw;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw),
                new long[]{1, 3, inputHeight, inputWidth});
             OrtSession.Result out = session.run(Map.of(inputName, tensor))) {

            float[][][][] boxes = (float[][][][]) out.get("boxes").orElseThrow().getValue();
            float[][][] confs = (float[][][]) out.get("confs").orElseThrow().getValue();
            raw = decode(boxes[0], confs[0], boxThreshold, area);
        }
        List<Detection> kept = nms(raw, nmsThreshold);
        return new Result(kept, (int) ((System.nanoTime() - start) / 1_000_000));
    }

    /**
     * Thresholds the raw boxes and maps them from the ROI back into frame coordinates. With a
     * single class "max over classes" is that class; kept general so a multi-class export would
     * still produce sensible boxes.
     */
    static List<Detection> decode(float[][][] boxes, float[][] confs, float threshold, Roi area) {
        List<Detection> out = new ArrayList<>();
        for (int i = 0; i < boxes.length; i++) {
            float conf = 0f;
            for (float c : confs[i]) {
                conf = Math.max(conf, c);
            }
            if (conf <= threshold) {
                continue;
            }
            float[] b = boxes[i][0];
            out.add(new Detection(conf,
                    toFrame(b[0], area.x(), area.w()),
                    toFrame(b[1], area.y(), area.h()),
                    toFrame(b[2], area.x(), area.w()),
                    toFrame(b[3], area.y(), area.h())));
        }
        return out;
    }

    /** Greedy NMS, highest confidence first - the same as Obico's {@code nms_cpu}. */
    static List<Detection> nms(List<Detection> detections, float iouThreshold) {
        List<Detection> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparingDouble(Detection::confidence).reversed());
        List<Detection> keep = new ArrayList<>();
        for (Detection candidate : sorted) {
            boolean overlaps = false;
            for (Detection k : keep) {
                if (k.iou(candidate) > iouThreshold) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                keep.add(candidate);
            }
        }
        return keep;
    }

    private static float toFrame(float v, double offset, double span) {
        return (float) (offset + Math.clamp(v, 0f, 1f) * span);
    }

    static BufferedImage crop(BufferedImage frame, Roi roi) {
        if (roi.isFullFrame()) {
            return frame;
        }
        int x = (int) Math.round(roi.x() * frame.getWidth());
        int y = (int) Math.round(roi.y() * frame.getHeight());
        int w = Math.max(1, Math.min(frame.getWidth() - x, (int) Math.round(roi.w() * frame.getWidth())));
        int h = Math.max(1, Math.min(frame.getHeight() - y, (int) Math.round(roi.h() * frame.getHeight())));
        return frame.getSubimage(x, y, w, h);
    }

    /** Bilinear resize to the input size, then RGB scaled to 0..1 in planar CHW layout. */
    static float[] toChw(BufferedImage src, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        int plane = width * height;
        int[] rgb = resized.getRGB(0, 0, width, height, null, 0, width);
        float[] chw = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = rgb[i];
            chw[i] = ((p >> 16) & 0xFF) / 255f;
            chw[plane + i] = ((p >> 8) & 0xFF) / 255f;
            chw[2 * plane + i] = (p & 0xFF) / 255f;
        }
        return chw;
    }

    @Override
    public synchronized void close() {
        try {
            session.close();
        } catch (OrtException e) {
            logger.warn("Failed to close ONNX session", e);
        }
    }
}
