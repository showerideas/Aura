package com.showerideas.aura.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import timber.log.Timber

/**
 * Biometric-only auth gate for Android Auto / Automotive OS.
 *
 * In Automotive OS and Android Auto sessions the phone camera is NOT available
 * to the car app — the gesture-biometric pipeline (MediaPipe hand landmarks)
 * cannot run. This transparent Activity provides a fallback: it shows a
 * [BiometricPrompt] (fingerprint / face / device credential) and sends the
 * result back to the calling screen via a broadcast.
 *
 * Flow
 * 1. [IdleScreen] (or [AuraVoiceActivity]) launches this Activity.
 * 2. [BiometricPrompt] is shown using the FragmentActivity host.
 * 3. On SUCCESS: broadcast [ACTION_BIOMETRIC_SUCCESS] → AdvertisingScreen launches.
 * 4. On ERROR / FAIL: broadcast [ACTION_BIOMETRIC_FAILED] → IdleScreen shows error.
 *
 * Why not BiometricPrompt in the Car App Library?
 * The Car App Library runs on the head unit's display; BiometricPrompt must run
 * on the phone's display (where the sensor is). This Activity runs on the phone
 * and bridges the two surfaces via a broadcast.
 */
class AuraBiometricAutoActivity : FragmentActivity() {

    companion object {
        const val ACTION_BIOMETRIC_SUCCESS = "com.showerideas.aura.auto.BIOMETRIC_SUCCESS"
        const val ACTION_BIOMETRIC_FAILED  = "com.showerideas.aura.auto.BIOMETRIC_FAILED"

        /** Extra: human-readable error message on failure. */
        const val EXTRA_ERROR_MSG = "error_msg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!canAuthenticate()) {
            Timber.w("AuraBiometricAutoActivity: no biometric enrolled — reporting failure")
            broadcastResult(success = false, errorMsg = "No biometric enrolled")
            return
        }

        showBiometricPrompt()
    }

    private fun canAuthenticate(): Boolean {
        val bm = BiometricManager.from(this)
        val result = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.i("AuraBiometricAutoActivity: biometric succeeded")
                broadcastResult(success = true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Timber.w("AuraBiometricAutoActivity: biometric error %d — %s", errorCode, errString)
                broadcastResult(success = false, errorMsg = errString.toString())
            }

            override fun onAuthenticationFailed() {
                Timber.w("AuraBiometricAutoActivity: biometric failed (did not match)")
                // Do NOT finish — BiometricPrompt retries automatically.
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("AURA Authentication")
            .setSubtitle("Authenticate to start exchange")
            .setDescription(
                "Your phone camera is unavailable in the car. " +
                "Use biometric authentication to proceed."
            )
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    private fun broadcastResult(success: Boolean, errorMsg: String = "") {
        val action = if (success) ACTION_BIOMETRIC_SUCCESS else ACTION_BIOMETRIC_FAILED
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            if (errorMsg.isNotBlank()) putExtra(EXTRA_ERROR_MSG, errorMsg)
        })
        finish()
    }
}

