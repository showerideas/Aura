package com.showerideas.aura.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.showerideas.aura.R

/**
 * PR-16: thin wrapper around AndroidX BiometricPrompt so the rest of the
 * app can ask the system for a fingerprint / face-unlock confirmation
 * without dealing with the verbose builder API directly.
 *
 * AURA uses biometrics as an *alternative* to gesture auth, gated by the
 * user's choice in Settings (PR-19). The biometric check is only a UI
 * gate — the cryptographic security of the exchange still flows from the
 * ECDH handshake and the device identity challenge (PR-13).
 */
object BiometricAuthHelper {

    /**
     * True when the device has class-3 (BIOMETRIC_STRONG) biometrics
     * enrolled and available. False when the user has not enrolled any
     * biometric, when hardware is missing, or when the platform reports
     * any of the transient unavailable codes.
     */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Show the system biometric prompt. [onSuccess] fires after a
     * confirmed match; [onFailure] fires for both hard errors (cancel,
     * lockout) and soft failures (sensor didn't recognise the print).
     * Tests using this helper should stub out the BiometricPrompt — the
     * helper itself is intentionally thin so there's nothing to mock here.
     */
    fun authenticate(
        fragment: Fragment,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(fragment.requireContext())
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                onFailure(msg.toString())
            }
            override fun onAuthenticationFailed() {
                onFailure(fragment.getString(R.string.biometric_auth_failed))
            }
        }
        val ctx = fragment.requireContext()
        BiometricPrompt(fragment, executor, callback).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(ctx.getString(R.string.biometric_title))
                .setSubtitle(ctx.getString(R.string.biometric_subtitle))
                .setNegativeButtonText(ctx.getString(R.string.biometric_negative))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        )
    }
}
