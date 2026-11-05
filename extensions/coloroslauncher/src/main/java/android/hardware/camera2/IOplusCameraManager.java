package android.hardware.camera2;

/**
 * Stand-in (.github/scripts/coloros-launcher/overrides): names OnePlus System Launcher uses, answering "absent" off
 * ColorOS (no OPPO code). Cmd is an enum, as the launcher switches over it (ordinal()).
 */
public class IOplusCameraManager {
    public enum Cmd {
        CMD_PRE_OPEN
    }
}
