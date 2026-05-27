package com.showerideas.aura.ui.exchange

import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Profile
import com.showerideas.aura.network.FakeRelayClient
import com.showerideas.aura.ui.qr.QRExchangeViewModel
import com.showerideas.aura.utils.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import javax.crypto.SecretKey

/**
 * Stage-1 / A2 — QRExchangeViewModel relay-state flow tests using [FakeRelayClient].
 *
 * Verified state transitions:
 *   malformed JSON    → Error
 *   missing pubkey    → Invalid
 *   missing endpoint  → Invalid
 *   expired ts        → Expired
 *   GET always null   → RelayTimeout (POST fired)
 *   POST fails        → RelayTimeout or Error (graceful)
 *   valid exchange    → AwaitingSasConfirmation (SAS 6-digit)
 *   confirmSas()      → Success
 *   abortSas()        → Error (SAS mismatch message)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QRExchangeRelayFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakeProfile = Profile(
        id           = "local-profile",
        displayName  = "Local User",
        phone        = "+10000000001",
        email        = "local@test.com",
        company      = "TestCo",
        title        = "Dev"
    )

    private lateinit var profileRepo: ProfileRepository
    private lateinit var contactRepo: ContactRepository

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        profileRepo = mock { onBlocking { getOrCreate() } doReturn fakeProfile }
        contactRepo = mock { onBlocking { saveDeduped(any()) } doReturn null }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildVm(relay: FakeRelayClient = FakeRelayClient()) =
        QRExchangeViewModel(profileRepo, contactRepo, relay)

    private fun peerJson(
        kp: KeyPair   = CryptoUtils.generateEphemeralECDHKeyPair(),
        endpoint: String = "ep-${System.nanoTime()}",
        ts: Long      = System.currentTimeMillis()
    ): String = JSONObject().apply {
        put("v", 1)
        put("pubkey", Base64.getEncoder().encodeToString(kp.public.encoded))
        put("endpoint", endpoint)
        put("ts", ts)
    }.toString()

    /** Derive AES-256 shared key from peerPriv + localPub (mirrors VM's ECDH). */
    private fun deriveShared(peerPriv: PrivateKey, localPub: PublicKey): SecretKey =
        CryptoUtils.deriveSharedAESKey(peerPriv, localPub)

    /** Encrypt a minimal peer profile JSON with [sharedKey]. */
    private fun encryptPeerProfile(sharedKey: SecretKey): ByteArray {
        val json = JSONObject().apply {
            put("displayName", "Remote Peer")
            put("phone", "+19999999999")
            put("email", "peer@test.com")
        }.toString()
        return CryptoUtils.encrypt(sharedKey, json.toByteArray(Charsets.UTF_8))
    }

    /** Read the private `ourKeyPair` field from the VM via reflection. */
    private fun vmLocalKeyPair(vm: QRExchangeViewModel): KeyPair? {
        val f = QRExchangeViewModel::class.java.getDeclaredField("ourKeyPair")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(vm) as? KeyPair
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error / Invalid / Expired inputs (no relay needed)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun malformedJson_emitsError() = runTest {
        val vm = buildVm()
        vm.onPeerScanned("NOT_JSON")
        advanceUntilIdle()
        val result = vm.pairingResult.first { it != null }
        assertTrue("Expected Error, got $result",
            result is QRExchangeViewModel.PairingResult.Error)
    }

    @Test fun missingPubkey_emitsInvalid() = runTest {
        val vm = buildVm()
        val json = JSONObject().apply {
            put("v", 1)
            put("endpoint", "e")
            put("ts", System.currentTimeMillis())
        }.toString()
        vm.onPeerScanned(json)
        advanceUntilIdle()
        assertEquals(QRExchangeViewModel.PairingResult.Invalid,
            vm.pairingResult.first { it != null })
    }

    @Test fun missingEndpoint_emitsInvalid() = runTest {
        val vm = buildVm()
        val kp = CryptoUtils.generateEphemeralECDHKeyPair()
        val json = JSONObject().apply {
            put("v", 1)
            put("pubkey", Base64.getEncoder().encodeToString(kp.public.encoded))
            put("ts", System.currentTimeMillis())
        }.toString()
        vm.onPeerScanned(json)
        advanceUntilIdle()
        assertEquals(QRExchangeViewModel.PairingResult.Invalid,
            vm.pairingResult.first { it != null })
    }

    @Test fun expiredTimestamp_emitsExpired() = runTest {
        val vm = buildVm()
        val kp = CryptoUtils.generateEphemeralECDHKeyPair()
        vm.onPeerScanned(peerJson(kp = kp, ts = System.currentTimeMillis() - 120_000L))
        advanceUntilIdle()
        assertEquals(QRExchangeViewModel.PairingResult.Expired,
            vm.pairingResult.first { it != null })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relay timeout: GET always returns null
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun relayNeverResponds_emitsRelayTimeout() = runTest {
        val relay = FakeRelayClient()         // nothing enqueued → getSlot always null
        val vm    = buildVm(relay)
        val peerKp = CryptoUtils.generateEphemeralECDHKeyPair()
        vm.onPeerScanned(peerJson(kp = peerKp))
        advanceUntilIdle()

        assertEquals(QRExchangeViewModel.PairingResult.RelayTimeout,
            vm.pairingResult.first { it != null })
        // POST must have fired
        assertTrue("Expected at least one POST", relay.postedSlots.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST failure: graceful degradation → timeout (GET still polled)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun postFails_gracefullyTimesOut() = runTest {
        val relay = FakeRelayClient().also { it.postOk = false }
        val vm    = buildVm(relay)
        vm.onPeerScanned(peerJson())
        advanceUntilIdle()
        val result = vm.pairingResult.first { it != null }
        assertTrue(
            "Expected RelayTimeout or Error on POST failure, got $result",
            result is QRExchangeViewModel.PairingResult.RelayTimeout
                || result is QRExchangeViewModel.PairingResult.Error
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path: POST → GET → AwaitingSasConfirmation
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun validExchange_emitsAwaitingSasConfirmation() = runTest {
        val relay   = FakeRelayClient()
        val vm      = buildVm(relay)

        val peerKp     = CryptoUtils.generateEphemeralECDHKeyPair()
        val peerEndpt  = "peer-${System.nanoTime()}"
        val scanJson   = peerJson(kp = peerKp, endpoint = peerEndpt)

        // Read the VM's local public key BEFORE scanning — it's set in init.
        val localKeyPair = vmLocalKeyPair(vm)
        assertNotNull("VM should have initialised its local keypair", localKeyPair)

        // Derive the shared key from the peer's perspective:
        //   sharedKey = ECDH(peerPriv, localPub)
        //   VM derives: sharedKey = ECDH(localPriv, peerPub)
        //   Both yield the same raw secret.
        val sharedKey = deriveShared(peerKp.private, localKeyPair!!.public)
        val encrypted = encryptPeerProfile(sharedKey)

        // Enqueue the encrypted reply at the peer's endpoint
        relay.enqueueSlot(peerEndpt, encrypted)

        vm.onPeerScanned(scanJson)
        advanceUntilIdle()

        val result = vm.pairingResult.first { it != null }
        assertNotNull(result)
        assertTrue("Expected AwaitingSasConfirmation, got $result",
            result is QRExchangeViewModel.PairingResult.AwaitingSasConfirmation)

        val sas = (result as QRExchangeViewModel.PairingResult.AwaitingSasConfirmation).sasPin
        assertEquals("SAS pin must be exactly 6 characters", 6, sas.length)
        assertTrue("SAS pin must be all digits", sas.all { it.isDigit() })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAS confirm/abort
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun confirmSas_emitsSuccess() = runTest {
        val relay  = FakeRelayClient()
        val vm     = buildVm(relay)

        val peerKp    = CryptoUtils.generateEphemeralECDHKeyPair()
        val peerEndpt = "peer-${System.nanoTime()}"
        val localKp   = vmLocalKeyPair(vm)!!
        val sharedKey = deriveShared(peerKp.private, localKp.public)

        relay.enqueueSlot(peerEndpt, encryptPeerProfile(sharedKey))
        vm.onPeerScanned(peerJson(kp = peerKp, endpoint = peerEndpt))
        advanceUntilIdle()

        assertTrue(vm.pairingResult.first { it != null }
            is QRExchangeViewModel.PairingResult.AwaitingSasConfirmation)

        vm.consumePairingResult()
        vm.confirmSas()
        advanceUntilIdle()

        assertTrue("Expected Success after confirm",
            vm.pairingResult.first { it != null }
                is QRExchangeViewModel.PairingResult.Success)
    }

    @Test fun abortSas_emitsErrorWithMismatchMessage() = runTest {
        val relay  = FakeRelayClient()
        val vm     = buildVm(relay)

        val peerKp    = CryptoUtils.generateEphemeralECDHKeyPair()
        val peerEndpt = "peer-${System.nanoTime()}"
        val sharedKey = deriveShared(peerKp.private, vmLocalKeyPair(vm)!!.public)

        relay.enqueueSlot(peerEndpt, encryptPeerProfile(sharedKey))
        vm.onPeerScanned(peerJson(kp = peerKp, endpoint = peerEndpt))
        advanceUntilIdle()

        assertTrue(vm.pairingResult.first { it != null }
            is QRExchangeViewModel.PairingResult.AwaitingSasConfirmation)

        vm.consumePairingResult()
        vm.abortSas()

        val err = vm.pairingResult.first { it != null }
        assertTrue("Expected Error after abort", err is QRExchangeViewModel.PairingResult.Error)
        assertTrue("Error must mention SAS mismatch",
            (err as QRExchangeViewModel.PairingResult.Error).message.contains("SAS", ignoreCase = true))
    }
}

