package com.showerideas.aura.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.network.RelayClient
import com.showerideas.aura.utils.CryptoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Backing ViewModel for the Settings screen. Centralises all app-wide knobs.
 *
 * Phase 6.5 addition: [rotateIdentityKey] — generates a new Android Keystore
 * identity key and produces a rotation certificate for known peers.
 *
 * Phase 8.3 addition: [setTorProxyEnabled] — routes relay traffic via Orbot.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val gestureAuthManager: GestureAuthManager,
    private val contactRepository: ContactRepository,
    private val authPreferences: AuthPreferences,
    private val relayClient: RelayClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val authMethod: StateFlow<String> = authPreferences.authMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            AuthPreferences.DEFAULT_METHOD)

    val bgActivationEnabled: StateFlow<Boolean> = authPreferences.bgActivationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val torProxyEnabled: StateFlow<Boolean> = authPreferences.torProxyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAuthMethod(method: String) {
        viewModelScope.launch { authPreferences.setAuthMethod(method) }
    }

    fun setBgActivation(enabled: Boolean) {
        viewModelScope.launch { authPreferences.setBgActivationEnabled(enabled) }
    }

    fun clearGesture() {
        gestureAuthManager.clearPattern()
    }

    fun clearAllContacts() {
        viewModelScope.launch { contactRepository.deleteAll() }
    }

    suspend fun contactCount(): Int = contactRepository.count()

    // -------------------------------------------------------------------------
    // Phase 6.5: Identity key rotation
    // -------------------------------------------------------------------------

    /**
     * Rotate the device identity key in Android Keystore.
     *
     * Runs on [Dispatchers.IO] — Keystore ops must not block the main thread.
     * Returns `true` on success, `false` if the rotation failed (UI shows error toast).
     *
     * The [CryptoUtils.RotationCertificate] produced here is logged;
     * in Phase 6.5.2 it will be stored in KnownPeer.rotationCertificate and
     * broadcast to known peers on next exchange.
     */
    suspend fun rotateIdentityKey(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cert = CryptoUtils.rotateDeviceIdentityKey()
            Timber.i(
                "Identity key rotated at ${cert.rotatedAtMs}. " +
                "New key: ${cert.newPublicKeyBytes.size} bytes, " +
                "Cert sig: ${cert.signatureBytes.size} bytes"
            )
            // TODO (Phase 6.5.2): persist cert to KnownPeerRepository + broadcast on next exchange
        }.onFailure { e ->
            Timber.e(e, "Identity key rotation failed")
        }.isSuccess
    }

    // -------------------------------------------------------------------------
    // Phase 8.3 — Orbot/Tor anonymization proxy
    // -------------------------------------------------------------------------

    /** True if org.torproject.android (Orbot) is installed on this device. */
    val isOrbotInstalled: Boolean
        get() = try {
            context.packageManager.getPackageInfo("org.torproject.android", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Enable or disable routing relay traffic through Orbot (127.0.0.1:9050).
     * Persisted to [AuthPreferences]. Effective immediately via [RelayClient.setAnonymizationProxy].
     */
    fun setTorProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPreferences.setTorProxyEnabled(enabled)
            if (enabled && isOrbotInstalled) {
                relayClient.setAnonymizationProxy(java.net.InetSocketAddress("127.0.0.1", 9050))
            } else {
                relayClient.setAnonymizationProxy(null)
            }
        }
    }
}
