package com.showerideas.aura.ui.fido

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.showerideas.aura.R
import com.showerideas.aura.fido.PasskeyRepository
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Task 83/84 — Gesture gate activity for FIDO2 passkey operations.
 *
 * Launched by [AuraCredentialProviderService] via PendingIntent when:
 *   - ACTION_CREATE_PASSKEY: user is creating a new passkey
 *   - ACTION_GET_PASSKEY: user is asserting an existing passkey
 *
 * Biometric verification is required before any FIDO2 operation proceeds.
 * On confirmation:
 *   - CREATE: delegates to [PasskeyRepository.createPasskey] and returns
 *             a [CreatePublicKeyCredentialResponse] via [PendingIntentHandler].
 *   - GET:    delegates to [PasskeyRepository.signAssertion] and returns
 *             a [GetCredentialResponse] via [PendingIntentHandler].
 *
 * On failure or cancellation, RESULT_CANCELED is returned.
 *
 * Requires API 34 (UPSIDE_DOWN_CAKE) — consistent with [AuraCredentialProviderService].
 */
@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyGestureGateActivity : FragmentActivity() {

    companion object {
        const val ACTION_CREATE_PASSKEY = "com.showerideas.aura.fido.CREATE_PASSKEY"
        const val ACTION_GET_PASSKEY    = "com.showerideas.aura.fido.GET_PASSKEY"

        // Bundle key used by GetPublicKeyCredentialOption to carry the FIDO2 request JSON.
        private const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }

    @Inject lateinit var passkeyRepository: PasskeyRepository

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("PasskeyGestureGateActivity: action=${intent.action}")
        when (intent.action) {
            ACTION_CREATE_PASSKEY -> showBiometricGate(::performCreate)
            ACTION_GET_PASSKEY    -> showBiometricGate(::performGet)
            else                  -> cancel()
        }
    }

    // ── Biometric gate ────────────────────────────────────────────────────────

    private fun showBiometricGate(onConfirmed: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.d("PasskeyGestureGateActivity: biometric confirmed")
                onConfirmed()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Timber.w("PasskeyGestureGateActivity: biometric error $errorCode: $errString")
                cancel()
            }
            override fun onAuthenticationFailed() {
                Timber.w("PasskeyGestureGateActivity: biometric attempt failed")
                // do not cancel — allow retry
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_negative))
            .build()
        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    // ── FIDO2 CREATE ──────────────────────────────────────────────────────────

    private fun performCreate() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        if (request == null) {
            Timber.e("PasskeyGestureGateActivity: missing create credential request in intent")
            cancel(); return
        }
        try {
            val requestJson = (request.callingRequest as? CreatePublicKeyCredentialRequest)
                ?.requestJson.orEmpty()
            val rpId = parseNestedField(requestJson, "rp", "id") ?: packageName
            val userHandleB64 = parseNestedField(requestJson, "user", "id").orEmpty()
            val userHandle = if (userHandleB64.isNotEmpty())
                android.util.Base64.decode(
                    userHandleB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
            else byteArrayOf()

            val credentialId = passkeyRepository.createPasskey(rpId, userHandle)
            val credIdB64    = credentialId.toBase64Url()
            val responseJson = buildCreateResponse(credIdB64)

            val resultData = Intent()
            PendingIntentHandler.setCreateCredentialResponse(
                resultData, CreatePublicKeyCredentialResponse(responseJson)
            )
            setResult(RESULT_OK, resultData)
            Timber.d("PasskeyGestureGateActivity: passkey created credentialId=$credentialId")
        } catch (e: Exception) {
            Timber.e(e, "PasskeyGestureGateActivity: create passkey error")
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    // ── FIDO2 GET ─────────────────────────────────────────────────────────────

    private fun performGet() {
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        if (request == null) {
            Timber.e("PasskeyGestureGateActivity: missing get credential request in intent")
            cancel(); return
        }
        try {
            var rpId      = packageName
            var credId    = ""
            var challenge = byteArrayOf()

            // Iterate credential requests for the public-key option that carries
            // the FIDO2 assertion options JSON (rpId, challenge, allowCredentials).
            for (req in request.credentialOptions) {
                val optJson = req.requestData
                    .getString(BUNDLE_KEY_REQUEST_JSON) ?: continue
                rpId = parseTopField(optJson, "rpId") ?: rpId
                val challengeB64 = parseTopField(optJson, "challenge") ?: continue
                challenge = android.util.Base64.decode(
                    challengeB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
                // Extract first allowCredentials entry — its id is the credential to sign with
                credId = runCatching {
                    val arr  = org.json.JSONObject(optJson).optJSONArray("allowCredentials")
                    val idB64 = arr?.getJSONObject(0)?.getString("id").orEmpty()
                    String(
                        android.util.Base64.decode(
                            idB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                        ),
                        Charsets.UTF_8
                    )
                }.getOrElse { "" }
                break
            }

            val signature = passkeyRepository.signAssertion(rpId, credId, challenge)
            val credIdB64 = credId.toBase64Url()
            val sigB64    = android.util.Base64.encodeToString(
                signature, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val responseJson = buildGetResponse(credIdB64, sigB64)

            val resultData = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                resultData, GetCredentialResponse(PublicKeyCredential(responseJson))
            )
            setResult(RESULT_OK, resultData)
            Timber.d("PasskeyGestureGateActivity: assertion signed credId=$credId")
        } catch (e: Exception) {
            Timber.e(e, "PasskeyGestureGateActivity: get passkey error")
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cancel() { setResult(RESULT_CANCELED); finish() }

    /** Extract a field from a nested JSON object: `json.obj.field`. */
    private fun parseNestedField(json: String, obj: String, field: String): String? =
        runCatching {
            org.json.JSONObject(json).getJSONObject(obj).getString(field)
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** Extract a top-level string field from JSON. */
    private fun parseTopField(json: String, field: String): String? =
        runCatching {
            org.json.JSONObject(json).getString(field).takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** Minimal WebAuthn attestation response JSON for CREATE. */
    private fun buildCreateResponse(credIdB64: String): String =
        """{"id":"$credIdB64","rawId":"$credIdB64","type":"public-key",""" +
        """"response":{"attestationObject":"","clientDataJSON":""}}"""

    /** Minimal WebAuthn assertion response JSON for GET. */
    private fun buildGetResponse(credIdB64: String, sigB64: String): String =
        """{"id":"$credIdB64","rawId":"$credIdB64","type":"public-key",""" +
        """"response":{"authenticatorData":"","clientDataJSON":"",""" +
        """"signature":"$sigB64","userHandle":""}}"""

    private fun String.toBase64Url(): String = android.util.Base64.encodeToString(
        toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
    )
}
