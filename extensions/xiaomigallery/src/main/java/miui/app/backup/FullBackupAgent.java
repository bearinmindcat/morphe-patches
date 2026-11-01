package miui.app.backup;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Gallery uses, answering "absent" off HyperOS (no Xiaomi code). */
public class FullBackupAgent extends android.app.backup.BackupAgent {
    public FullBackupAgent() {
    }
    public void addAttachedFile(java.lang.String p0) {
    }
    public void attachBaseContext(android.content.Context p0) {
    }
    public java.io.File getFilesDir() {
        return null;
    }
    public int tarAttaches(java.lang.String p0, android.app.backup.FullBackupDataOutput p1, int p2) {
        return 0;
    }
    @Override
    public void onBackup(android.os.ParcelFileDescriptor p0, android.app.backup.BackupDataOutput p1, android.os.ParcelFileDescriptor p2) {
    }
    @Override
    public void onRestore(android.app.backup.BackupDataInput p0, int p1, android.os.ParcelFileDescriptor p2) {
    }
}
