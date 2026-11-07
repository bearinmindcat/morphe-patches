package com.lge.lgdrm;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * LG's DRM content stream is an InputStream (Gallery reads protected files through it); off LG's ROM no file is ever
 * protected, so it is an empty one.
 */
public class DrmStream extends java.io.InputStream {
    @Override
    public int read() {
        return -1;
    }
}
