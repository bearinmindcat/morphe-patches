package com.oplus.dispatch;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class QueueAttr {
    public QueueAttr() {
    }
    public static com.oplus.dispatch.QueueAttr QUEUE_CONCURRENT = new com.oplus.dispatch.QueueAttr();
    public static com.oplus.dispatch.QueueAttr QUEUE_FG = new com.oplus.dispatch.QueueAttr();
    public static com.oplus.dispatch.QueueAttr QUEUE_IO = new com.oplus.dispatch.QueueAttr();
    public static com.oplus.dispatch.QueueAttr QUEUE_SERIAL = new com.oplus.dispatch.QueueAttr();
    public static class Builder {
        public Builder() {
        }
        public com.oplus.dispatch.QueueAttr build() {
            return new com.oplus.dispatch.QueueAttr();
        }
        public com.oplus.dispatch.QueueAttr.Builder setConcurrent(boolean p0) {
            return this;
        }
        public com.oplus.dispatch.QueueAttr.Builder setConcurrent(boolean p0, int p1) {
            return this;
        }
        public com.oplus.dispatch.QueueAttr.Builder setIoQueue(boolean p0) {
            return this;
        }
        public com.oplus.dispatch.QueueAttr.Builder setIsTimeQueue(boolean p0) {
            return this;
        }
    }
}
