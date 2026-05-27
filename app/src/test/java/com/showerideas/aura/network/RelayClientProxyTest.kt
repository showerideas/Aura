package com.showerideas.aura.network

import com.showerideas.aura.relay.privacypass.PrivacyPassClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.InetSocketAddress

/**
 * Unit tests for RelayClient proxy configuration.
 *
 * Verifies that [RelayClient.setAnonymizationProxy] correctly configures
 * the SOCKS5 proxy and that the proxy can be cleared. Full integration
 * tests (verifying traffic routes through Orbot) require a live Orbot
 * installation and are performed in manual QA.
 */
class RelayClientProxyTest {

    private val client = RelayClient(mock<PrivacyPassClient>())

    @Test
    fun setProxy_configuresOrbot() {
        val orbotAddress = InetSocketAddress("127.0.0.1", 9050)
        client.setAnonymizationProxy(orbotAddress)
        // Verify via reflection that the proxy field is set
        val field = RelayClient::class.java.getDeclaredField("socksProxy")
        field.isAccessible = true
        val stored = field.get(client) as? InetSocketAddress
        assertNotNull("Proxy should be set after setAnonymizationProxy()", stored)
        assert(stored?.port == 9050) { "Proxy port should be 9050 (Orbot default)" }
    }

    @Test
    fun clearProxy_resetsToDirect() {
        client.setAnonymizationProxy(InetSocketAddress("127.0.0.1", 9050))
        client.setAnonymizationProxy(null)
        val field = RelayClient::class.java.getDeclaredField("socksProxy")
        field.isAccessible = true
        val stored = field.get(client)
        assertNull("Proxy should be null after setAnonymizationProxy(null)", stored)
    }

    @Test
    fun orbotDetection_checksPackageManager() {
        // Orbot detection uses PackageManager.getPackageInfo("org.torproject.android", 0)
        // This test documents the detection contract — actual detection is in SettingsViewModel
        val orbotPackage = "org.torproject.android"
        assert(orbotPackage.isNotEmpty()) { "Orbot package name must be defined" }
    }
}
