package com.android.sec.org.bouncycastle.x509;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Email uses, answering "absent" off One UI (no Samsung code). */
public class ExtendedPKIXParameters extends java.security.cert.PKIXParameters {
    public ExtendedPKIXParameters() throws java.security.InvalidAlgorithmParameterException {
        super(refuse());
    }
    public java.lang.Object clone() {
        return null;
    }
    public java.util.List getAdditionalStores() {
        return new java.util.ArrayList<>();
    }
    public java.util.List getCertPathCheckers() {
        return new java.util.ArrayList<>();
    }
    public java.util.List getCertStores() {
        return new java.util.ArrayList<>();
    }
    public java.util.Date getDate() {
        return null;
    }
    public java.util.Set getInitialPolicies() {
        return new java.util.HashSet<>();
    }
    public static com.android.sec.org.bouncycastle.x509.ExtendedPKIXParameters getInstance(java.security.cert.PKIXParameters p0) {
        return null;
    }
    public java.lang.String getSigProvider() {
        return "";
    }
    public java.util.List getStores() {
        return new java.util.ArrayList<>();
    }
    public java.util.Set getTrustAnchors() {
        return new java.util.HashSet<>();
    }
    public int getValidityModel() {
        return 0;
    }
    public boolean isAdditionalLocationsEnabled() {
        return false;
    }
    public boolean isAnyPolicyInhibited() {
        return false;
    }
    public boolean isExplicitPolicyRequired() {
        return false;
    }
    public boolean isPolicyMappingInhibited() {
        return false;
    }
    public boolean isRevocationEnabled() {
        return false;
    }
    public boolean isUseDeltasEnabled() {
        return false;
    }
    public void setTargetCertConstraints(java.security.cert.CertSelector p0) {
    }
    private static java.util.Set<java.security.cert.TrustAnchor> refuse() {
        throw new UnsupportedOperationException("not on this phone");
    }
}
