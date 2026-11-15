package com.nuvio.tv.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityVerdictTest {

    private val validatedWifi = NetworkSnapshot(
        networkPresent = true,
        hasInternet = true,
        isVpn = false,
        validated = true,
        captivePortal = false,
    )

    @Test
    fun `no default network is offline`() {
        val none = NetworkSnapshot(false, false, false, false, false)
        assertTrue(isDeviceOffline(none, probeReachable = null))
    }

    @Test
    fun `validated network is online without a probe`() {
        assertFalse(isDeviceOffline(validatedWifi, probeReachable = null))
    }

    @Test
    fun `unvalidated network stays online until a probe fails`() {
        val unvalidated = validatedWifi.copy(validated = false)
        assertFalse(isDeviceOffline(unvalidated, probeReachable = null))
        assertFalse(isDeviceOffline(unvalidated, probeReachable = true))
        assertTrue(isDeviceOffline(unvalidated, probeReachable = false))
    }

    @Test
    fun `failed probe wins over stale validation`() {
        assertTrue(isDeviceOffline(validatedWifi, probeReachable = false))
    }

    @Test
    fun `dns vpn without internet capability counts as connected`() {
        val dnsVpn = NetworkSnapshot(
            networkPresent = true,
            hasInternet = false,
            isVpn = true,
            validated = false,
            captivePortal = false,
        )
        assertFalse(isDeviceOffline(dnsVpn, probeReachable = null))
    }

    @Test
    fun `network without internet capability is offline`() {
        assertTrue(isDeviceOffline(validatedWifi.copy(hasInternet = false), probeReachable = null))
    }

    @Test
    fun `captive portal is offline`() {
        val portal = validatedWifi.copy(validated = false, captivePortal = true)
        assertTrue(isDeviceOffline(portal, probeReachable = true))
    }
}
