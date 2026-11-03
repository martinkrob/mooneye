package h848.software.mooneye.detection;

/**
 * Running state of the failure decision for one printer. Persisted, because the long-term mean
 * describes the printer and its camera over many prints (~20 hours of frames) and must survive a
 * restart.
 * <p>
 * Field meaning follows Obico's {@code PrinterPrediction}:
 * <ul>
 *   <li>{@code currentP} - sum of box confidences in the latest frame</li>
 *   <li>{@code ewmMean} - fast exponential average of that, the "is it failing now" signal</li>
 *   <li>{@code rollingMeanShort} - this print's typical level</li>
 *   <li>{@code rollingMeanLong} - this printer's typical level across prints; what the camera
 *       normally sees (a purge bucket, a cable, a textured bed) cancels out against it</li>
 * </ul>
 */
public final class PredictionState {

    private int currentFrameNum;
    private int lifetimeFrameNum;
    private double currentP;
    private double ewmMean;
    private double rollingMeanShort;
    private double rollingMeanLong;

    public PredictionState() {
    }

    public PredictionState(int currentFrameNum, int lifetimeFrameNum, double currentP,
                           double ewmMean, double rollingMeanShort, double rollingMeanLong) {
        this.currentFrameNum = currentFrameNum;
        this.lifetimeFrameNum = lifetimeFrameNum;
        this.currentP = currentP;
        this.ewmMean = ewmMean;
        this.rollingMeanShort = rollingMeanShort;
        this.rollingMeanLong = rollingMeanLong;
    }

    /**
     * Forgets the current print but keeps what is known about the printer, as Obico does on a new
     * print. Also used after a resume: the operator has looked and decided to continue, so the
     * evidence that led to the pause must not immediately pause it again.
     */
    public void resetForNewPrint() {
        currentFrameNum = 0;
        currentP = 0.0;
        ewmMean = 0.0;
        rollingMeanShort = 0.0;
    }

    public PredictionState copy() {
        return new PredictionState(currentFrameNum, lifetimeFrameNum, currentP, ewmMean,
                rollingMeanShort, rollingMeanLong);
    }

    public int getCurrentFrameNum() { return currentFrameNum; }
    public void setCurrentFrameNum(int currentFrameNum) { this.currentFrameNum = currentFrameNum; }

    public int getLifetimeFrameNum() { return lifetimeFrameNum; }
    public void setLifetimeFrameNum(int lifetimeFrameNum) { this.lifetimeFrameNum = lifetimeFrameNum; }

    public double getCurrentP() { return currentP; }
    public void setCurrentP(double currentP) { this.currentP = currentP; }

    public double getEwmMean() { return ewmMean; }
    public void setEwmMean(double ewmMean) { this.ewmMean = ewmMean; }

    public double getRollingMeanShort() { return rollingMeanShort; }
    public void setRollingMeanShort(double rollingMeanShort) { this.rollingMeanShort = rollingMeanShort; }

    public double getRollingMeanLong() { return rollingMeanLong; }
    public void setRollingMeanLong(double rollingMeanLong) { this.rollingMeanLong = rollingMeanLong; }
}
