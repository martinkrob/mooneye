package h848.software.mooneye.tools;

import h848.software.mooneye.detection.Detection;
import h848.software.mooneye.detection.FailureDetector;
import h848.software.mooneye.detection.FailurePredictor;
import h848.software.mooneye.detection.PredictionParams;
import h848.software.mooneye.detection.PredictionState;
import h848.software.mooneye.model.Roi;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Offline check of the failure model on still images - no printer, no server, no database.
 * <p>
 * Typical use: stage a failure (a tangle of filament on the bed), save a few frames from the
 * printer's camera, and see what the model makes of them before trusting it with a real print.
 *
 * <pre>
 * java -cp mooneye.jar h848.software.mooneye.tools.Evaluate \
 *      --model data/models/obico-failure-gen1.onnx [--roi 0.1,0.2,0.8,0.7] [--out annotated/] \
 *      [--sequence] image.jpg folder/ ...
 * </pre>
 *
 * {@code --sequence} feeds the images, sorted by name, through the decision logic as consecutive
 * frames 10 s apart - e.g. a print's saved snapshots - and prints the score and decision for each.
 * Remember the first 30 frames of a print are never judged.
 */
public final class Evaluate {

    private Evaluate() {
    }

    public static void main(String[] args) throws Exception {
        Path model = Path.of("data/models/obico-failure-gen1.onnx");
        Path out = null;
        Roi roi = null;
        boolean sequence = false;
        List<Path> inputs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model" -> model = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--roi" -> roi = parseRoi(args[++i]);
                case "--sequence" -> sequence = true;
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> inputs.add(Path.of(args[i]));
            }
        }
        List<Path> images = collect(inputs);
        if (images.isEmpty()) {
            usage();
            System.exit(2);
        }
        if (out != null) {
            Files.createDirectories(out);
        }

        PredictionParams params = PredictionParams.OBICO_GEN1;
        FailurePredictor predictor = new FailurePredictor(params);
        PredictionState state = new PredictionState();

        System.out.printf(Locale.US, "%-40s %6s %6s %5s %6s%s%n", "image", "maxConf", "sumP", "boxes", "ms",
                sequence ? "   frame  score  decision" : "");
        try (FailureDetector detector = new FailureDetector(model, params)) {
            for (Path image : images) {
                BufferedImage frame = ImageIO.read(image.toFile());
                if (frame == null) {
                    System.out.printf("%-40s not an image%n", image.getFileName());
                    continue;
                }
                FailureDetector.Result r = detector.detect(frame, roi);
                float max = (float) r.detections().stream().mapToDouble(Detection::confidence).max().orElse(0);
                String line = String.format(Locale.US, "%-40s %6.3f %6.3f %5d %6d", image.getFileName(), max,
                        FailurePredictor.frameScore(r.detections()), r.detections().size(), r.inferenceMs());
                if (sequence) {
                    predictor.update(state, r.detections());
                    line += String.format(Locale.US, "   %5d  %5.2f  %s", state.getCurrentFrameNum(),
                            predictor.normalizedScore(state, 1.0), predictor.decide(state, 1.0));
                }
                System.out.println(line);
                if (out != null) {
                    ImageIO.write(annotate(frame, predictor.visible(r.detections()), roi), "jpg",
                            out.resolve(image.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".jpg").toFile());
                }
            }
        }
    }

    /** Draws the region of interest and every box worth showing, with its confidence. */
    static BufferedImage annotate(BufferedImage frame, List<Detection> detections, Roi roi) {
        BufferedImage copy = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(frame, 0, 0, null);
            int w = frame.getWidth();
            int h = frame.getHeight();
            float stroke = Math.max(2f, w / 400f);
            g.setStroke(new BasicStroke(stroke));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, w / 60)));
            if (roi != null) {
                g.setColor(Color.CYAN);
                g.drawRect((int) (roi.x() * w), (int) (roi.y() * h), (int) (roi.w() * w), (int) (roi.h() * h));
            }
            for (Detection d : detections) {
                g.setColor(d.confidence() >= 0.5f ? Color.RED : Color.ORANGE);
                int x = (int) (d.x1() * w);
                int y = (int) (d.y1() * h);
                g.drawRect(x, y, (int) ((d.x2() - d.x1()) * w), (int) ((d.y2() - d.y1()) * h));
                g.drawString(String.format(Locale.US, "%.2f", d.confidence()), x + 4, Math.max(y - 4, g.getFont().getSize()));
            }
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static List<Path> collect(List<Path> inputs) throws IOException {
        List<Path> images = new ArrayList<>();
        for (Path p : inputs) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> files = Files.list(p)) {
                    files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)"))
                            .forEach(images::add);
                }
            } else {
                images.add(p);
            }
        }
        images.sort(null);
        return images;
    }

    private static Roi parseRoi(String s) {
        String[] parts = s.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("--roi expects x,y,w,h as fractions, e.g. 0.1,0.2,0.8,0.7");
        }
        return Roi.of(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
    }

    private static void usage() {
        System.err.println("""
                Usage: java -cp mooneye.jar h848.software.mooneye.tools.Evaluate [options] <image|folder>...
                  --model <path>     ONNX model (default data/models/obico-failure-gen1.onnx)
                  --roi x,y,w,h      analyse only this part of each frame (fractions 0..1)
                  --out <folder>     write copies of the images with the detected boxes drawn in
                  --sequence         treat the images as consecutive frames and show the decision
                """);
    }
}
