package com.lge.sui.widget.util;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: the palette of LG's colour picker: 14 colours (the last one stands where LG
 * offers its custom colour), with their names for TalkBack.
 */
public class SUIColorUtil {
    public static final int[] COLORS = {
            0xff000000, 0xff5e5e5e, 0xffb2b2b2, 0xffffffff, 0xffe53935, 0xfffb8c00, 0xfffdd835,
            0xff7cb342, 0xff2e7d32, 0xff29b6f6, 0xff1e88e5, 0xff283593, 0xff8e24aa, 0xffec407a,
    };
    public static final String[] NAMES = {
            "Black", "Dark grey", "Light grey", "White", "Red", "Orange", "Yellow",
            "Light green", "Green", "Sky blue", "Blue", "Navy", "Purple", "Pink",
    };

    public static java.util.ArrayList getDefaultColorList() {
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int color : COLORS) list.add(color);
        return list;
    }
    public static java.util.ArrayList getDefaultColorListWithoutCustomColor() {
        return getDefaultColorList();
    }
    public static int getDefaultColorNameFlagByColorList(java.util.ArrayList p0) {
        return 0;
    }
    public static java.util.ArrayList getDefaultColorNameList(android.content.Context p0) {
        return new java.util.ArrayList<>(java.util.Arrays.asList(NAMES));
    }
    public static java.util.ArrayList getDefaultColorNameListWithoutCustomColor(android.content.Context p0) {
        return getDefaultColorNameList(p0);
    }
}
