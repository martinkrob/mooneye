package h848.software.mooneye.detection;

import h848.software.mooneye.model.Roi;
import java.awt.image.BufferedImage;

/**
 * Something that finds failure-like objects in a camera frame. {@link FailureDetector} is the
 * real one; tests substitute a scripted one to drive the monitor through a failure.
 */
public interface FrameDetector extends AutoCloseable {

    FailureDetector.Result detect(BufferedImage frame, Roi roi) throws Exception;

    @Override
    void close();
}
