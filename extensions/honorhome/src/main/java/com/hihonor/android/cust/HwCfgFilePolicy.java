package com.hihonor.android.cust;

/**
 * Stand-in (written by hand from the generated one): MagicOS's product and cloud configuration files. Off MagicOS
 * there are none: no files (an empty list, which Honor Home walks without a null check), no cloud values.
 */
public class HwCfgFilePolicy {
    public static java.io.File getCfgFile(java.lang.String p0, int p1) {
        return null;
    }
    public static java.util.ArrayList getCfgFileList(java.lang.String p0, int p1) {
        return new java.util.ArrayList<>();
    }
    public static boolean getCloudCfgBoolValue(java.lang.String p0, java.lang.String p1, boolean p2) {
        return p2;
    }
    public static java.lang.String getCloudCfgListValue(java.lang.String p0, java.lang.String p1) {
        return null;
    }
    public static java.lang.String[] getDownloadCfgFile(java.lang.String p0, java.lang.String p1) {
        return new java.lang.String[0];
    }
    public static java.util.Map getFileConfig(java.lang.String p0) {
        return new java.util.HashMap<>();
    }
    public static java.lang.String getOpKey(int p0) {
        return "";
    }
}
