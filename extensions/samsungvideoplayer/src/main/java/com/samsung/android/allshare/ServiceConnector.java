package com.samsung.android.allshare;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Video Player uses, answering "absent" off One UI (no Samsung code). */
public class ServiceConnector {
    public static com.samsung.android.allshare.ERROR createServiceProvider(android.content.Context p0, com.samsung.android.allshare.ServiceConnector.IServiceConnectEventListener p1, java.lang.String p2) {
        return null;
    }
    public static void deleteServiceProvider(com.samsung.android.allshare.ServiceProvider p0) {
    }
    public interface IServiceConnectEventListener {
    }
    public static class ServiceState {
        public static com.samsung.android.allshare.ServiceConnector.ServiceState DISABLED;
        public static com.samsung.android.allshare.ServiceConnector.ServiceState ENABLED;
        public int ordinal() {
            return 0;
        }
        public static com.samsung.android.allshare.ServiceConnector.ServiceState[] values() {
            return new com.samsung.android.allshare.ServiceConnector.ServiceState[0];
        }
    }
}
