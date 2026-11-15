package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the default network reports, reduced to the facts the verdict needs. */
internal data class NetworkSnapshot(
    val networkPresent: Boolean,
    val hasInternet: Boolean,
    val isVpn: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
)

/**
 * Decides whether the TV has internet. Only the device's own connection is judged here;
 * whether an addon or stream host answers is reported by that request's own error.
 *
 * Android's validation check can fail while the internet works: some TV firmware, DNS
 * filters that block the check host, and DNS VPNs that never report validation. So an
 * unvalidated network is only offline when our own reachability probe also fails, and
 * counts as online until a probe has run.
 *
 * The reverse also happens: a router that loses its uplink keeps Wi-Fi up and Android can
 * keep reporting the network as validated for minutes. A failed probe therefore wins over
 * validation. Probes only run on an unvalidated network or when [ConnectivityMonitor.recheck]
 * is asked after a real network failure.
 */
internal fun isDeviceOffline(snapshot: NetworkSnapshot, probeReachable: Boolean?): Boolean {
    if (!snapshot.networkPresent) return true
    if (!snapshot.hasInternet && !snapshot.isVpn) return true
    if (snapshot.captivePortal) return true
    return probeReachable == false
}

/**
 * App-wide view of whether the TV has internet, for the offline banner, the offline error
 * screen, automatic reloads on reconnect, and the player's wait-for-connection recovery.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val snapshot = MutableStateFlow(currentSnapshot())
    private val probeReachable = MutableStateFlow<Boolean?>(null)
    private var probeJob: Job? = null

    private val rawOffline = MutableStateFlow(isDeviceOffline(snapshot.value, null))

    /** Undebounced state, for callers that already hold a network failure in hand. */
    val isOfflineNow: Boolean get() = rawOffline.value

    /**
     * Debounced state for UI. Going offline must hold for [OFFLINE_DEBOUNCE_MS] so a Wi-Fi
     * handover does not flash the banner; coming back online is reported at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isOffline: StateFlow<Boolean> = rawOffline
        .mapLatest { offline ->
            if (offline) delay(OFFLINE_DEBOUNCE_MS)
            offline
        }
        .stateIn(scope, SharingStarted.Eagerly, rawOffline.value)

    init {
        registerCallback()
        scope.launch {
            snapshot.collect { snap ->
                if (snap.networkPresent && !snap.validated && !snap.captivePortal) {
                    startProbing()
                } else {
                    probeJob?.cancel()
                    probeJob = null
                    probeReachable.value = null
                }
                publish()
            }
        }
        scope.launch { probeReachable.collect { publish() } }
    }

    /**
     * Probes now, for a caller that just saw a request fail at the network level, and
     * returns whether the TV has internet. A failed probe keeps re-probing until it passes.
     */
    suspend fun recheck(): Boolean {
        val reachable = probeInternet()
        probeReachable.value = reachable
        if (!reachable) startProbing()
        publish()
        return !rawOffline.value
    }

    /** Suspends until the TV has internet again; returns at once if it already does. */
    suspend fun awaitOnline() {
        if (!rawOffline.value) return
        rawOffline.first { !it }
    }

    private fun publish() {
        val offline = isDeviceOffline(snapshot.value, probeReachable.value)
        if (rawOffline.value != offline) {
            Log.i(TAG, "Device ${if (offline) "offline" else "online"}: ${snapshot.value}, probe=${probeReachable.value}")
        }
        rawOffline.value = offline
    }

    private fun startProbing() {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            while (isActive) {
                val reachable = probeInternet()
                if (reachable && snapshot.value.validated) {
                    // Validation is trustworthy again; stop overriding it.
                    probeReachable.value = null
                    break
                }
                probeReachable.value = reachable
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private fun registerCallback() {
        val cm = manager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    snapshot.value = snapshotOf(cm.getNetworkCapabilities(network), present = true)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    snapshot.value = snapshotOf(caps, present = true)
                }

                override fun onLost(network: Network) {
                    // Do not re-read activeNetwork here: some TVs still return the lost network
                    // for a moment, and no further callback follows when nothing replaces it.
                    // A replacement network arrives through onAvailable.
                    snapshot.value = NetworkSnapshot(false, false, false, false, false)
                }
            })
        }.onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    private fun currentSnapshot(): NetworkSnapshot {
        val cm = manager ?: return ONLINE_FALLBACK
        return runCatching {
            val network = cm.activeNetwork
            snapshotOf(network?.let { cm.getNetworkCapabilities(it) }, present = network != null)
        }.getOrDefault(ONLINE_FALLBACK)
    }

    private fun snapshotOf(caps: NetworkCapabilities?, present: Boolean): NetworkSnapshot {
        if (!present || caps == null) {
            return NetworkSnapshot(false, false, false, false, false)
        }
        return NetworkSnapshot(
            networkPresent = true,
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }

    /**
     * Opens a TCP connection to well-known anycast resolvers by IP. No DNS lookup is made, so
     * a cached answer cannot hide an outage, and nothing about the user is sent.
     */
    private suspend fun probeInternet(): Boolean = withContext(Dispatchers.IO) {
        coroutineScope {
            PROBE_HOSTS.map { host ->
                async {
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress(host, 443), PROBE_TIMEOUT_MS) }
                        true
                    }.getOrDefault(false)
                }
            }.awaitAll().any { it }
        }
    }

    private companion object {
        const val TAG = "ConnectivityMonitor"
        const val OFFLINE_DEBOUNCE_MS = 2_000L
        const val PROBE_INTERVAL_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 3_000
        val PROBE_HOSTS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

        /** If the system service is unavailable, never claim the TV is offline. */
        val ONLINE_FALLBACK = NetworkSnapshot(true, true, false, true, false)
    }
}
