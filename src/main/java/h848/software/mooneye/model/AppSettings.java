package h848.software.mooneye.model;

/** Global settings editable from the UI. */
public class AppSettings {

    public static final int DEFAULT_RETENTION_PRINTS = 20;
    public static final int DEFAULT_SNAPSHOT_INTERVAL_SEC = 60;

    /** Keep everything belonging to this many most recent prints per printer. */
    private int retentionPrintCount = DEFAULT_RETENTION_PRINTS;
    /**
     * How often a frame is kept on disk while printing (timelapse and future training data).
     * Frames the model found suspicious are kept regardless. 0 = only suspicious frames.
     */
    private int snapshotIntervalSec = DEFAULT_SNAPSHOT_INTERVAL_SEC;

    public int getRetentionPrintCount() { return retentionPrintCount; }
    public void setRetentionPrintCount(int retentionPrintCount) { this.retentionPrintCount = retentionPrintCount; }

    public int getSnapshotIntervalSec() { return snapshotIntervalSec; }
    public void setSnapshotIntervalSec(int snapshotIntervalSec) { this.snapshotIntervalSec = snapshotIntervalSec; }
}
