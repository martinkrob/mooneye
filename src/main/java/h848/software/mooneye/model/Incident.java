package h848.software.mooneye.model;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * A moment the detector decided a print looked like it was failing, together with what MoonEye
 * did about it. The frame itself is stored alongside but loaded separately.
 * <p>
 * The operator's verdict ({@link #groundTruth}) turns an incident into labelled data, so reviewed
 * incidents are never removed by retention.
 */
public class Incident {

    public static final String LEVEL_WARNING = "WARNING";
    public static final String LEVEL_FAILURE = "FAILURE";

    public static final String ACTION_NOTIFIED = "NOTIFIED";
    public static final String ACTION_PAUSED = "PAUSED";

    public static final String TRUE_POSITIVE = "TRUE_POSITIVE";
    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";

    private Long id;
    private String printerId;
    private Long jobId;
    private Instant ts;
    private String filename;
    private String level;
    private String action;
    /** 0..1 score as shown in the UI (1/3 = warning line, 2/3 = failure line). */
    private double score;
    /** Sum of box confidences in the frame that tipped the decision. */
    private double currentP;
    /** JSON array of the boxes in that frame, in frame-relative coordinates. */
    private String detections;
    private String groundTruth;
    private Instant reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public double getCurrentP() { return currentP; }
    public void setCurrentP(double currentP) { this.currentP = currentP; }

    @JsonRawValue
    public String getDetections() { return detections; }
    public void setDetections(String detections) { this.detections = detections; }

    public String getGroundTruth() { return groundTruth; }
    public void setGroundTruth(String groundTruth) { this.groundTruth = groundTruth; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
