package miui.app.backup;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Calendar uses, answering "absent" off HyperOS (no Xiaomi code). */
public class FullBackupAgent extends android.app.backup.BackupAgent {
    public FullBackupAgent() {
    }
    public void addAttachedFile(java.lang.String p0) {
    }
    public int onAttachRestore(miui.app.backup.BackupMeta p0, android.os.ParcelFileDescriptor p1, java.lang.String p2) {
        return 0;
    }
    public int onRestoreEnd(miui.app.backup.BackupMeta p0) {
        return 0;
    }
    @Override
    public void onBackup(android.os.ParcelFileDescriptor p0, android.app.backup.BackupDataOutput p1, android.os.ParcelFileDescriptor p2) {
    }
    @Override
    public void onRestore(android.app.backup.BackupDataInput p0, int p1, android.os.ParcelFileDescriptor p2) {
    }
}
