package h848.software.mooneye.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import h848.software.mooneye.detection.FailurePredictor.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailurePredictorTest {

    private final FailurePredictor predictor = new FailurePredictor(PredictionParams.OBICO_GEN1);

    private static List<Detection> frame(float... confidences) {
        return java.util.stream.IntStream.range(0, confidences.length)
                .mapToObj(i -> new Detection(confidences[i], 0.1f * i, 0.1f, 0.1f * i + 0.05f, 0.2f))
                .toList();
    }

    private void feed(PredictionState s, int frames, List<Detection> detections) {
        for (int i = 0; i < frames; i++) {
            predictor.update(s, detections);
        }
    }

    @Test
    void rollingMeanIsAnOrdinaryMeanUntilTheWindowFills() {
        double m = 0;
        m = FailurePredictor.nextRollingMean(1.0, m, 0, 10);
        m = FailurePredictor.nextRollingMean(0.0, m, 1, 10);
        m = FailurePredictor.nextRollingMean(0.5, m, 2, 10);
        assertEquals(0.5, m, 1e-9);
        // Once full, each new value moves the mean by 1/window of the difference.
        assertEquals(0.5 + (1.5 - 0.5) / 10, FailurePredictor.nextRollingMean(1.5, 0.5, 10, 10), 1e-9);
    }

    @Test
    void nothingIsJudgedDuringWarmUp() {
        PredictionState s = new PredictionState();
        feed(s, PredictionParams.OBICO_GEN1.initSafeFrames() - 1, frame(0.9f, 0.9f));
        assertEquals(Decision.NONE, predictor.decide(s, 1.0));
        assertEquals(1, predictor.warmupFramesRemaining(s));
    }

    @Test
    void aCleanPrintStaysQuiet() {
        PredictionState s = new PredictionState();
        feed(s, 400, frame());
        assertEquals(Decision.NONE, predictor.decide(s, 1.0));
        assertTrue(predictor.normalizedScore(s, 1.0) < 1.0 / 3.0);
    }

    @Test
    void aSuddenFailureWarnsAndThenFails() {
        PredictionState s = new PredictionState();
        feed(s, 60, frame(0.05f)); // ten minutes of an almost clean view

        Decision first = Decision.NONE;
        int framesToWarning = -1;
        int framesToFailure = -1;
        for (int i = 1; i <= 30; i++) {
            predictor.update(s, frame(0.7f, 0.6f)); // a tangle appears
            Decision d = predictor.decide(s, 1.0);
            if (d != Decision.NONE && first == Decision.NONE) {
                first = d;
            }
            if (d == Decision.WARNING && framesToWarning < 0) {
                framesToWarning = i;
            }
            if (d == Decision.FAILURE) {
                framesToFailure = i;
                break;
            }
        }
        assertEquals(Decision.WARNING, first, "a failure is preceded by a warning");
        assertTrue(framesToFailure > framesToWarning && framesToFailure <= 10,
                "failure confirmed within ~100 s, was frame " + framesToFailure);
        assertTrue(predictor.normalizedScore(s, 1.0) > 2.0 / 3.0);
    }

    @Test
    void permanentSceneryIsAbsorbedByTheLongTermMean() {
        PredictionState s = new PredictionState();
        // The camera always sees something failure-like (a purge bucket) - over many prints.
        for (int print = 0; print < 30; print++) {
            s.resetForNewPrint();
            feed(s, 300, frame(0.5f));
        }
        assertEquals(Decision.NONE, predictor.decide(s, 1.0));
    }

    @Test
    void sensitivityScalesTheEvidence() {
        PredictionState s = new PredictionState();
        feed(s, 60, frame());
        feed(s, 3, frame(0.6f));
        double normal = predictor.normalizedScore(s, 1.0);
        double sensitive = predictor.normalizedScore(s, 1.5);
        assertTrue(sensitive > normal);
    }

    @Test
    void resetForNewPrintKeepsWhatIsKnownAboutThePrinter() {
        PredictionState s = new PredictionState();
        feed(s, 50, frame(0.3f));
        double longMean = s.getRollingMeanLong();
        s.resetForNewPrint();
        assertEquals(0, s.getCurrentFrameNum());
        assertEquals(0, s.getEwmMean());
        assertEquals(longMean, s.getRollingMeanLong());
        assertEquals(50, s.getLifetimeFrameNum());
    }

    @Test
    void onlyConfidentBoxesAreShown() {
        List<Detection> visible = predictor.visible(frame(0.1f, 0.5f, 0.25f));
        assertEquals(2, visible.size());
        assertEquals(0.5f, visible.getFirst().confidence());
    }
}
