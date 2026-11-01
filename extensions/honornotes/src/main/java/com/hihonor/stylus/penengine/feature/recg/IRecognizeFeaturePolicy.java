package com.hihonor.stylus.penengine.feature.recg;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public interface IRecognizeFeaturePolicy {
    int getEngineCode();
    java.lang.String getEngineVersion();
    int[] getSupportedFeatureList();
    void init(android.content.Context p0, int p1);
    java.lang.String recognize(int p0, java.lang.Object[] p1);
}
