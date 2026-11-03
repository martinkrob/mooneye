package h848.software.mooneye.moonraker;

/**
 * One reading of a printer's state as reported by Moonraker. Mutable bean, owned by whoever
 * received it; the telemetry pipeline hands out a fresh instance per reading.
 */
public class PrinterTelemetry {

    // Klipper Info
    private String klipperState;
    private String klipperMessage;

    // Print Stats
    private String printState;
    private String filename;
    private double progress;
    private double printDuration;
    private double filamentUsed;

    // Hardware Status
    private double extruderTemp;
    private double extruderTarget;
    private double bedTemp;
    private double bedTarget;

    // Toolhead (gcode position after lookahead - runs ahead of what is physically printed)
    private double x;
    private double y;
    private double z;
    private double printSpeed;

    // motion_report.live_position - the position the toolhead actually occupies right now.
    // This is what must be used for anything that has to line up with a camera frame.
    private double liveX;
    private double liveY;
    private double liveZ;

    // Heater duty cycle 0..1. A high power combined with a falling temperature means a clog.
    private double extruderPower;

    // Fan
    private double fanSpeed;

    // Getters and Setters
    public String getKlipperState() {
        return klipperState;
    }

    public void setKlipperState(String klipperState) {
        this.klipperState = klipperState;
    }

    public String getKlipperMessage() {
        return klipperMessage;
    }

    public void setKlipperMessage(String klipperMessage) {
        this.klipperMessage = klipperMessage;
    }

    public String getPrintState() {
        return printState;
    }

    public void setPrintState(String printState) {
        this.printState = printState;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = progress;
    }

    public double getPrintDuration() {
        return printDuration;
    }

    public void setPrintDuration(double printDuration) {
        this.printDuration = printDuration;
    }

    public double getExtruderTemp() {
        return extruderTemp;
    }

    public void setExtruderTemp(double extruderTemp) {
        this.extruderTemp = extruderTemp;
    }

    public double getExtruderTarget() {
        return extruderTarget;
    }

    public void setExtruderTarget(double extruderTarget) {
        this.extruderTarget = extruderTarget;
    }

    public double getBedTemp() {
        return bedTemp;
    }

    public void setBedTemp(double bedTemp) {
        this.bedTemp = bedTemp;
    }

    public double getBedTarget() {
        return bedTarget;
    }

    public void setBedTarget(double bedTarget) {
        this.bedTarget = bedTarget;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public double getLiveX() {
        return liveX;
    }

    public void setLiveX(double liveX) {
        this.liveX = liveX;
    }

    public double getLiveY() {
        return liveY;
    }

    public void setLiveY(double liveY) {
        this.liveY = liveY;
    }

    public double getLiveZ() {
        return liveZ;
    }

    public void setLiveZ(double liveZ) {
        this.liveZ = liveZ;
    }

    public double getExtruderPower() {
        return extruderPower;
    }

    public void setExtruderPower(double extruderPower) {
        this.extruderPower = extruderPower;
    }

    public double getFanSpeed() {
        return fanSpeed;
    }

    public void setFanSpeed(double fanSpeed) {
        this.fanSpeed = fanSpeed;
    }

    public double getFilamentUsed() {
        return filamentUsed;
    }

    public void setFilamentUsed(double filamentUsed) {
        this.filamentUsed = filamentUsed;
    }

    public double getPrintSpeed() {
        return printSpeed;
    }

    public void setPrintSpeed(double printSpeed) {
        this.printSpeed = printSpeed;
    }
}
