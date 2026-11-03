package h848.software.mooneye.detection;

import java.time.Instant;
import java.util.List;

/**
 * What the detector currently thinks about one printer, for the dashboard.
 *
 * @param modelReady        the model is loaded and frames can be analysed
 * @param modelError        why the model is not ready, if it is not
 * @param watching          a print is in progress and frames are being judged
 * @param lastFrameAt       when the frame behind these numbers was captured
 * @param score             0..1, 1/3 = warning line, 2/3 = failure line
 * @param decision          NONE / WARNING / FAILURE for the latest frame
 * @param currentP          sum of box confidences in the latest frame
 * @param warmupSeconds     time left before this print starts being judged
 * @param detections        boxes worth showing, in full-frame coordinates
 * @param inferenceMs       how long the latest inference took
 * @param cameraError       why the latest frame could not be fetched, if it could not
 */
public record DetectionStatus(
        boolean modelReady,
        String modelError,
        boolean watching,
        Instant lastFrameAt,
        double score,
        FailurePredictor.Decision decision,
        double currentP,
        int warmupSeconds,
        List<Detection> detections,
        Integer inferenceMs,
        String cameraError) {
}
