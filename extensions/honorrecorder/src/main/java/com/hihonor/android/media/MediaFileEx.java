package com.hihonor.android.media;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Recorder uses, answering "absent" off MagicOS (no Honor code). */
public class MediaFileEx {
    public static java.lang.String getMimeTypeForFile(java.lang.String p0) {
        String t = p0 == null || p0.lastIndexOf('.') < 0 ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(p0.substring(p0.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT)); return t == null ? "" : t;
    }
}
