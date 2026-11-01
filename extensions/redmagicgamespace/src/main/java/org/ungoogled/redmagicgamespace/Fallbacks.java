package org.ungoogled.redmagicgamespace;

/**
 * Members RedMagic Game Space needs a real answer for off RedMagic OS (beyond RedMagic's common ones). Everything else
 * the phone refuses or lacks answers "absent" (see RedMagic).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /**
     * Game Space's parts as ZTE_FEATURE_GAMESPACE_CONFIG ("id:0/1,...", GameSpaceConfig) switches them: the RedMagic
     * global build's own defaults, with what needs RedMagic hardware or RedMagic OS apps cut -- 0 game keys (shoulder
     * triggers), 1 Mora (RedMagic's assistant app), 8/9/a playing by mouse, gamepad or screen mirror (key mapping
     * through input injection), b cooling fan, c Super Base (recordings and plugins of RedMagic OS's in-game
     * assistant), d live atmosphere (Red Magic Moments), g WeChat mini-game shortcut. Kept: 6 game notes and 7 Red
     * Magic Time (both shown only where their apps are installed), i recognising games by their features.
     */
    private static final String GAME_SPACE_CONFIG = "0:0,1:0,2:0,3:0,4:0,5:0,6:1,7:1,8:0,9:0,a:0,b:0,c:0,d:0,e:0,f:0,g:0,h:0,i:1,j:0";

    /**
     * Game Space Center's pages (ZTE_FEATURE_GAME_CENTER_MENU): game time ("Game Achievements", from Android's usage
     * statistics), wallpapers, other options, about. Left out, as they only set what RedMagic OS applies in games or
     * need its hardware, apps or account: Profile (Nubia account), Data (Red Magic Moments and Game Notes storage),
     * network (dual SIM, acceleration), screen, do not disturb and false-touch options, peripheral (host) mode,
     * shoulder triggers, key lights, the in-game watermark.
     */
    private static final String GAME_CENTER_MENU = "SummaryFragment,divider,WallpaperListFragment,OtherOptionsFragment,AboutFragment";

    /**
     * "Other options" (ZTE_FEATURE_GAME_CENTER_OTHER_OPTIONS): the sound of Game Space's opening animation. Left out:
     * hiding game icons (ZTE's launcher), the game assistant's start animation and time reminders (RedMagic OS's game
     * assistant), lobby recommendations (China only) and Fiber Catcher (the Game Notes app). Recognising games by their
     * features is always listed.
     */
    private static final String GAME_CENTER_OTHER_OPTIONS = "game_space_start_anim_volume";

    /** "About game space" (ZTE_FEATURE_GAME_CENTER_ABOUT): what is new and the privacy policy; updates come with the port. */
    private static final String GAME_CENTER_ABOUT = "gcs_about_update,gcs_privacy_policy";

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        return NONE;
    }

    /** The value of a ZTE feature switch off RedMagic OS (see RedMagic.feature), or null: the app's default. */
    static String feature(String key) {
        switch (key) {
            case "ZTE_FEATURE_GAMESPACE_CONFIG": return GAME_SPACE_CONFIG;
            case "ZTE_FEATURE_GAME_CENTER_MENU": return GAME_CENTER_MENU;
            case "ZTE_FEATURE_GAME_CENTER_OTHER_OPTIONS": return GAME_CENTER_OTHER_OPTIONS;
            case "ZTE_FEATURE_GAME_CENTER_ABOUT": return GAME_CENTER_ABOUT;
        }
        return null;
    }
}
