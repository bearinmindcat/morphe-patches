package motorola.core_services.cli;

import android.content.Context;

/**
 * Stand-in for Motorola's cover-screen (CLI, Razr's outer display) manager: off Motorola there is no cover screen. The
 * launcher asks it only for the cover screen's rotation. Carries Motorola's names and signatures only.
 */
public class CLIManager {
    private static final CLIManager INSTANCE = new CLIManager();

    private CLIManager() {
    }

    public static CLIManager getInstance(Context context) {
        return INSTANCE;
    }

    public int getCliRealRotation() {
        return 0;
    }
}
