package h848.software.mooneye.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import h848.software.mooneye.model.Roi;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailureDetectorTest {

    private static final Roi FULL = new Roi(0, 0, 1, 1);

    @Test
    void decodeKeepsOnlyBoxesAboveTheThreshold() {
        float[][][] boxes = {
                {{0.1f, 0.1f, 0.3f, 0.3f}},
                {{0.5f, 0.5f, 0.9f, 0.9f}},
        };
        float[][] confs = {{0.05f}, {0.6f}};
        List<Detection> out = FailureDetector.decode(boxes, confs, 0.08f, FULL);
        assertEquals(1, out.size());
        assertEquals(0.6f, out.getFirst().confidence());
        assertEquals(0.5f, out.getFirst().x1(), 1e-6);
    }

    @Test
    void decodeMapsBoxesFromTheRoiBackToTheFullFrame() {
        float[][][] boxes = {{{0f, 0f, 1f, 0.5f}}};
        float[][] confs = {{0.9f}};
        Roi roi = Roi.of(0.25, 0.5, 0.5, 0.5);
        Detection d = FailureDetector.decode(boxes, confs, 0.08f, roi).getFirst();
        assertEquals(0.25f, d.x1(), 1e-6);
        assertEquals(0.5f, d.y1(), 1e-6);
        assertEquals(0.75f, d.x2(), 1e-6);
        assertEquals(0.75f, d.y2(), 1e-6);
    }

    @Test
    void decodeClampsBoxesThatSpillOutOfTheInput() {
        float[][][] boxes = {{{-0.2f, -0.1f, 1.3f, 1.1f}}};
        Detection d = FailureDetector.decode(boxes, new float[][]{{0.5f}}, 0.08f, FULL).getFirst();
        assertEquals(0f, d.x1());
        assertEquals(1f, d.x2());
    }

    @Test
    void nmsDropsOverlapsButKeepsSeparateObjects() {
        List<Detection> in = List.of(
                new Detection(0.5f, 0.10f, 0.10f, 0.30f, 0.30f),
                new Detection(0.9f, 0.11f, 0.11f, 0.31f, 0.31f), // same object, more confident
                new Detection(0.4f, 0.60f, 0.60f, 0.80f, 0.80f)); // elsewhere
        List<Detection> out = FailureDetector.nms(in, 0.45f);
        assertEquals(2, out.size());
        assertEquals(0.9f, out.get(0).confidence());
        assertEquals(0.4f, out.get(1).confidence());
    }

    @Test
    void preprocessingProducesPlanarRgbScaledToOne() {
        BufferedImage img = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                img.setRGB(x, y, new Color(255, 0, 51).getRGB());
            }
        }
        float[] chw = FailureDetector.toChw(img, 4, 2);
        assertEquals(24, chw.length);
        assertEquals(1f, chw[0], 1e-6);       // R plane
        assertEquals(0f, chw[8], 1e-6);       // G plane
        assertEquals(0.2f, chw[16], 1e-6);    // B plane
    }

    @Test
    void cropUsesTheRoiFractions() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage cropped = FailureDetector.crop(img, Roi.of(0.5, 0.5, 0.5, 0.5));
        assertEquals(100, cropped.getWidth());
        assertEquals(50, cropped.getHeight());
    }

    /**
     * Runs the real model when it is available locally (set {@code -Dmooneye.model=...} or keep it
     * at the default data path). Skipped otherwise - the file is 200 MB and not in the repository.
     */
    @Test
    void realModelSeesNothingOnAnEmptyBed() throws Exception {
        Path model = Path.of(System.getProperty("mooneye.model", "data/models/obico-failure-gen1.onnx"));
        assumeTrue(Files.isRegularFile(model), "model not available at " + model);

        BufferedImage bed = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bed.createGraphics();
        g.setColor(new Color(40, 40, 45));
        g.fillRect(0, 0, 1280, 720);
        g.dispose();

        try (FailureDetector detector = new FailureDetector(model, PredictionParams.OBICO_GEN1)) {
            FailureDetector.Result r = detector.detect(bed, null);
            assertTrue(FailurePredictor.frameScore(r.detections()) < PredictionParams.OBICO_GEN1.thresholdLow(),
                    "empty frame scored " + FailurePredictor.frameScore(r.detections()));
            assertTrue(r.inferenceMs() > 0);
        }
    }
}
