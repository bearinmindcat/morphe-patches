package org.ungoogled.ui;

/**
 * Maps' own resources that the extension uses, by name. Resource ids move with every Maps
 * build (26.44 moved 39 of the 45 the extension used to hard-code, and one old id now names a
 * different icon), so none is written here: the patches look each field's resource up by name
 * in the APK being patched and give this class a static initializer that sets them all.
 *
 * A field's name is {@code <type>_<resource name>}; every static int field here must name a
 * resource Maps has, or patching stops. Read them at run time (or into a non-constant static),
 * never copy a value into a compile-time constant.
 */
@SuppressWarnings("unused")
public final class Res {
    private Res() {}

    // Colours
    public static int color_google_blue200;
    public static int color_quantum_googblue500;

    // Icons
    public static int drawable_gs_add_location_alt_vd_theme_24;
    public static int drawable_gs_add_vd_theme_24;
    public static int drawable_gs_bookmark_check_fill1_vd_theme_24;
    public static int drawable_gs_bookmark_fill1_vd_theme_24;
    public static int drawable_gs_bookmark_vd_theme_24;
    public static int drawable_gs_close_vd_theme_24;
    public static int drawable_gs_delete_vd_theme_24;
    public static int drawable_gs_edit_vd_theme_24;
    public static int drawable_gs_history_vd_theme_24;
    public static int drawable_gs_home_vd_theme_24;
    public static int drawable_gs_more_vert_vd_theme_24;
    public static int drawable_gs_share_vd_theme_24;
    public static int drawable_gs_work_vd_theme_24;
    // In the density splits only; their names are kept in the merged APK's table.
    public static int drawable_ic_qu_fab_circle;
    public static int drawable_ic_qu_fab_shadow;
    public static int drawable_ic_suitcase;

    // Fonts
    public static int font_GoogleSansMedium;
    public static int font_GoogleSansRegular;
    public static int font_GoogleSansTextMedium;
    public static int font_GoogleSansTextRegular;

    // View ids
    public static int id_gesture_detector_view;
    public static int id_header;
    public static int id_media_app_remote_control;
    public static int id_nav_sheet;

    // Strings
    public static int plurals_REVIEW_COUNT_SHORT;
    public static int string_EXPAND_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION;
    public static int string_MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION;
}
