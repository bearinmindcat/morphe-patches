package android.app;

/**
 * Stand-in for One UI's status bar manager (no Samsung code): One Hand Operation + asks whether the notification panel
 * is open and opens or closes it (by reflection); off One UI the accessibility service does that (Actions).
 */
public class SemStatusBarManager {
    public boolean isPanelExpanded() {
        return org.ungoogled.samsungonehand.Actions.panelExpanded();
    }

    public void collapsePanels() {
        org.ungoogled.samsungonehand.Actions.collapsePanels();
    }

    public void expandNotificationsPanel() {
        org.ungoogled.samsungonehand.Actions.expandPanel(false);
    }

    public void expandQuickSettingsPanel() {
        org.ungoogled.samsungonehand.Actions.expandPanel(true);
    }
}
