package com.hihonor.stylus.penengine.feature.recg;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public interface IRecgFeature {
    int addStrokes(float[] p0, float[] p1, int[] p2);
    void clearContent();
    boolean delStrokes(int p0);
    void destory();
    java.util.List getPixelRects(java.util.List p0);
    java.lang.String getRecognizeMode();
    java.util.List getSupportLanguages();
    void init(android.content.Context p0, java.lang.String p1);
    android.graphics.Path pathShape(java.lang.String p0, float p1, float p2);
    java.lang.String recognize(float[] p0, float[] p1, int[] p2);
    int setEngineLanguage(java.lang.String p0);
    void setRecgListener(com.hihonor.stylus.penengine.feature.listener.IRecgListener p0);
    void setRecognizeMode(int p0);
}
