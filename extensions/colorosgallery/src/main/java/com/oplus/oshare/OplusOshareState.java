package com.oplus.oshare;

/**
 * Stand-in (hand-written over .github/scripts/coloros-standins output): the enum OnePlus Photos' HeyTap addon reads as
 * a java.lang.Enum; no OPPO code. On an OPPO / OnePlus / realme phone the framework's own class loads first.
 */
public enum OplusOshareState {
    IDLE, READY, TRANSIT_WAIT, TRANSITING, CANCEL, TRANSIT_SUCCESS, TRANSIT_FAILED, TRANSIT_REJECT, TRANSIT_TIMEOUT,
    BUSUY, BUSY, CANCEL_WAIT, SPACE_NOT_ENOUGH
}
