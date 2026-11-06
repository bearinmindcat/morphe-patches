package com.motorola.android.checkin.provider;

/**
 * Stand-in for Motorola's check-in (usage report) event: off Motorola there is no check-in service, and nothing is
 * collected or sent. Carries Motorola's names and signatures only.
 */
public class CheckinEventWrapper {
    public CheckinEventWrapper(String tag, String event, String version, long time) {
    }

    public void setValue(String key, String value) {
    }

    public void publish(Object resolver) {
    }
}
