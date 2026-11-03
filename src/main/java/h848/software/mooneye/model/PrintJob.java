package h848.software.mooneye.model;

import java.time.Instant;

/**
 * One physical print. A pause - manual or by MoonEye - does not end it: the record stays open
 * with status {@code paused} and continues when the print is resumed. Only a terminal Klipper
 * state (complete, cancelled, error, standby) closes it.
 */
public class PrintJob {

    public static final String STATUS_PRINTING = "printing";
    public static final String STATUS_PAUSED = "paused";

    private Long id;
    private String printerId;
    private String filename;
    private Instant startTime;
    private Instant endTime;
    private String status;
    private double durationSeconds;
    private double filamentUsed;
    /** How many times MoonEye paused this print. */
    private int aiPauses;
    /** The operator silenced failure alerts for the rest of this print. */
    private boolean alertsMuted;

    public boolean isOpen() {
        return endTime == null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }

    public double getFilamentUsed() { return filamentUsed; }
    public void setFilamentUsed(double filamentUsed) { this.filamentUsed = filamentUsed; }

    public int getAiPauses() { return aiPauses; }
    public void setAiPauses(int aiPauses) { this.aiPauses = aiPauses; }

    public boolean isAlertsMuted() { return alertsMuted; }
    public void setAlertsMuted(boolean alertsMuted) { this.alertsMuted = alertsMuted; }
}
