package org.ungoogled.colorosnotes;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Stands in for ColorOS's privacy-password screens (see Locks): with a secure screen lock it asks for it (Android's
 * biometric / screen-lock prompt, drawn over the app like ColorOS's own password sheet, so the notes list underneath is
 * never stopped) and answers RESULT_OK once it is entered; without one it opens Android's "set a screen lock" page and
 * answers RESULT_OK if a lock was set. The app treats RESULT_OK exactly as ColorOS's confirm and choose screens' answer.
 */
public class LockActivity extends Activity {
    private static final int CHOOSE = 2;
    private CancellationSignal cancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isDeviceSecure()) {
            confirm();
            return;
        }
        Toast.makeText(this, "Set a screen lock to lock notes", Toast.LENGTH_LONG).show();
        try {
            startActivityForResult(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD), CHOOSE);
        } catch (RuntimeException e) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_SECURITY_SETTINGS), CHOOSE);
            } catch (RuntimeException e2) {
                done(RESULT_CANCELED);
            }
        }
    }

    private void confirm() {
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(label)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
            cancel = new CancellationSignal();
            prompt.authenticate(cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    done(RESULT_OK);
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    done(RESULT_CANCELED);
                }
            });
        } catch (RuntimeException e) {
            done(RESULT_CANCELED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CHOOSE) {
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            done(keyguard != null && keyguard.isDeviceSecure() ? RESULT_OK : RESULT_CANCELED);
        }
    }

    private void done(int result) {
        if (isFinishing()) return;
        setResult(result);
        finish();
    }
}
