package android.app.appsearch;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures One UI Home uses, answering "absent" off One UI (no Samsung code). */
public class SchemaVisibilityConfig {
    public java.util.List getAllowedPackages() {
        return new java.util.ArrayList<>();
    }
    public android.app.appsearch.PackageIdentifier getPubliclyVisibleTargetPackage() {
        return null;
    }
    public java.util.Set getRequiredPermissions() {
        return new java.util.HashSet<>();
    }
    public static class Builder {
        public Builder() {
        }
        public android.app.appsearch.SchemaVisibilityConfig.Builder addAllowedPackage(android.app.appsearch.PackageIdentifier p0) {
            return this;
        }
        public android.app.appsearch.SchemaVisibilityConfig.Builder addRequiredPermissions(java.util.Set p0) {
            return this;
        }
        public android.app.appsearch.SchemaVisibilityConfig build() {
            return null;
        }
        public android.app.appsearch.SchemaVisibilityConfig.Builder setPubliclyVisibleTargetPackage(android.app.appsearch.PackageIdentifier p0) {
            return this;
        }
    }
}
