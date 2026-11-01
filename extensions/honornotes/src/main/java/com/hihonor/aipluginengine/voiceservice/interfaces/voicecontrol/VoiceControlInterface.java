package com.hihonor.aipluginengine.voiceservice.interfaces.voicecontrol;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Notes uses, answering "absent" off MagicOS (no Honor code). */
public interface VoiceControlInterface {
    void cancelRecognize();
    void cancelRecognizeAndBusiness();
    void cancelStreamResult();
    void cancelUploadPoiInfo();
    void clearContext();
    void deleteAppInfo();
    void deleteContactInfo();
    long getDialogId();
    int getInteractionId();
    void handleBusinessFinished(com.hihonor.aipluginengine.voiceservice.common.Session p0);
    void init(android.content.Intent p0, com.hihonor.aipluginengine.voiceservice.interfaces.voicecontrol.VoiceControlListener p1);
    boolean isReady();
    boolean isRecognizing();
    void postOperationMessage(android.content.Intent p0, com.hihonor.aipluginengine.voiceservice.interfaces.voicecontrol.VoicekitCallback p1);
    void recognizeText(android.content.Intent p0);
    void recognizeVoice(android.content.Intent p0);
    void refreshData(android.content.Intent p0);
    void release();
    void renewSession(android.content.Intent p0);
    void stopBusiness(android.content.Intent p0);
    void stopRecognize();
    void switchRecognizeEngine(android.content.Intent p0);
    void updateLexicon(java.util.Map p0);
    void updateNluSceneHotwords(java.lang.String p0);
    void updateSwitch(android.content.Intent p0);
    void updateVoiceContext(java.lang.String p0);
    void updateVoiceEvent(java.lang.String p0);
    void uploadAppInfo(java.lang.String p0);
    void uploadClientInfo(com.hihonor.aipluginengine.voiceservice.common.Session p0, java.lang.String p1);
    void uploadContactInfo(android.os.Bundle p0);
    void uploadDataToObs(java.lang.String p0, java.util.Map p1, java.lang.String p2, com.hihonor.aipluginengine.voiceservice.interfaces.voicecontrol.VoicekitCallback p3);
    void uploadHotWords(android.os.Bundle p0);
    void uploadPoiInfo(android.os.Bundle p0, com.hihonor.aipluginengine.voiceservice.interfaces.voicecontrol.UploadCallback p1);
    void writeAudio(byte[] p0, int p1, int p2);
}
