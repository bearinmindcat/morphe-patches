package com.hihonor.android.security.hwassetmanager;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Files uses, answering "absent" off MagicOS (no Honor code). */
public class HwAssetManager {
    public HwAssetManager() {
    }
    public static java.lang.String BUNDLE_AEADASSET = "AEADAsset";
    public static java.lang.String BUNDLE_APPTAG = "AppTag";
    public static java.lang.String BUNDLE_ASSETHANDLE = "AssetHandle";
    public static java.lang.String BUNDLE_ASSETTYPE = "AssetType";
    public static java.lang.String BUNDLE_BATCHASSET = "BatchAsset";
    public static java.lang.String BUNDLE_RESETFLAG = "ResetFlag";
    public com.hihonor.android.security.hwassetmanager.HwAssetManager.AssetResult assetDelete(android.content.Context p0, android.os.Bundle p1) {
        
        AssetResult result = new AssetResult();
        result.resultCode = org.ungoogled.honorfiles.MagicAssets.delete(p0, p1 == null ? null : p1.getString(BUNDLE_ASSETHANDLE)) ? 0 : -1;
        result.resultInfo = new java.util.ArrayList<>();
        return result;
    }
    public com.hihonor.android.security.hwassetmanager.HwAssetManager.AssetResult assetInsert(android.content.Context p0, android.os.Bundle p1) {
        
        String handle = org.ungoogled.honorfiles.MagicAssets.insert(p0, p1 == null ? null : p1.getString(BUNDLE_AEADASSET));
        AssetResult result = new AssetResult();
        result.resultCode = handle == null ? -1 : 0;
        result.resultInfo = new java.util.ArrayList<>();
        if (handle != null) result.resultInfo.add(handle);
        return result;
    }
    public com.hihonor.android.security.hwassetmanager.HwAssetManager.AssetResult assetSelect(android.content.Context p0, android.os.Bundle p1) {
        
        String value = org.ungoogled.honorfiles.MagicAssets.select(p0, p1 == null ? null : p1.getString(BUNDLE_ASSETHANDLE));
        AssetResult result = new AssetResult();
        result.resultCode = value == null ? -1 : 0;
        result.resultInfo = new java.util.ArrayList<>();
        if (value != null) {
            try { result.resultInfo.add(new org.json.JSONObject().put("AEADAsset", value).toString()); } catch (org.json.JSONException e) { result.resultCode = -1; }
        }
        return result;
    }
    public static com.hihonor.android.security.hwassetmanager.HwAssetManager getInstance() {
        return new com.hihonor.android.security.hwassetmanager.HwAssetManager();
    }
    public static class AssetResult {
        public int resultCode;
        public java.util.List resultInfo;
    }
}
