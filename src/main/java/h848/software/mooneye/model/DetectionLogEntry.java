package h848.software.mooneye.model;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * One analysed frame: what the model saw, how the running averages moved, and what was decided.
 * Written every detection cycle while printing; this is the raw material for charts and for
 * judging whether the thresholds suit a particular camera.
 */
public class DetectionLogEntry {

    private Long id;
    private String printerId;
    private Long jobId;
    private Instant ts;
    private double currentP;
    private double ewmMean;
    private double rollingMeanShort;
    private double rollingMeanLong;
    private double score;
    private String decision;
    private int frameNum;
    private String detections;
    private Double extruderTemp;
    private Double bedTemp;
    private Double progress;
    private String telemetryWindow;
    private Integer inferenceMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }

    public double getCurrentP() { return currentP; }
    public void setCurrentP(double currentP) { this.currentP = currentP; }

    public double getEwmMean() { return ewmMean; }
    public void setEwmMean(double ewmMean) { this.ewmMean = ewmMean; }

    public double getRollingMeanShort() { return rollingMeanShort; }
    public void setRollingMeanShort(double rollingMeanShort) { this.rollingMeanShort = rollingMeanShort; }

    public double getRollingMeanLong() { return rollingMeanLong; }
    public void setRollingMeanLong(double rollingMeanLong) { this.rollingMeanLong = rollingMeanLong; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public int getFrameNum() { return frameNum; }
    public void setFrameNum(int frameNum) { this.frameNum = frameNum; }

    @JsonRawValue
    public String getDetections() { return detections; }
    public void setDetections(String detections) { this.detections = detections; }

    public Double getExtruderTemp() { return extruderTemp; }
    public void setExtruderTemp(Double extruderTemp) { this.extruderTemp = extruderTemp; }

    public Double getBedTemp() { return bedTemp; }
    public void setBedTemp(Double bedTemp) { this.bedTemp = bedTemp; }

    public Double getProgress() { return progress; }
    public void setProgress(Double progress) { this.progress = progress; }

    @JsonRawValue
    public String getTelemetryWindow() { return telemetryWindow; }
    public void setTelemetryWindow(String telemetryWindow) { this.telemetryWindow = telemetryWindow; }

    public Integer getInferenceMs() { return inferenceMs; }
    public void setInferenceMs(Integer inferenceMs) { this.inferenceMs = inferenceMs; }
}
