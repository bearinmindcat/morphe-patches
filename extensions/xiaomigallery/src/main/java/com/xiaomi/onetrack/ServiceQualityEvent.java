package com.xiaomi.onetrack;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Gallery uses, answering "absent" off HyperOS (no Xiaomi code). */
public class ServiceQualityEvent {
    public ServiceQualityEvent() {
    }
    public static class Builder {
        public Builder() {
        }
        public com.xiaomi.onetrack.ServiceQualityEvent build() {
            return new com.xiaomi.onetrack.ServiceQualityEvent();
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setDuration(java.lang.Long p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setExceptionTag(java.lang.String p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setExtraParams(java.util.Map p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setHost(java.lang.String p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setPath(java.lang.String p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setRequestTimestamp(java.lang.Long p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setResponseCode(java.lang.Integer p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setResultType(com.xiaomi.onetrack.ServiceQualityEvent.ResultType p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setRetryCount(java.lang.Integer p0) {
            return this;
        }
        public com.xiaomi.onetrack.ServiceQualityEvent.Builder setScheme(java.lang.String p0) {
            return this;
        }
    }
    public static class ResultType {
        public static com.xiaomi.onetrack.ServiceQualityEvent.ResultType FAILED;
        public static com.xiaomi.onetrack.ServiceQualityEvent.ResultType SUCCESS;
        public static com.xiaomi.onetrack.ServiceQualityEvent.ResultType TIMEOUT;
    }
}
