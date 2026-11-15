package com.nuvio.tv.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.ConnectivityMonitor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The app's connectivity monitor; null in previews and tests, which then count as online. */
val LocalConnectivityMonitor = staticCompositionLocalOf<ConnectivityMonitor?> { null }

/**
 * How many full-screen [OfflineState]s are on screen. The banner hides while any is, so the
 * user is not told twice; it still shows "Back online" when they close on reconnect.
 */
private val visibleOfflineStates = mutableIntStateOf(0)

/** Whether the TV currently has no internet (debounced). */
@Composable
fun rememberDeviceOffline(): State<Boolean> {
    val monitor = LocalConnectivityMonitor.current
        ?: return remember { mutableStateOf(false) }
    return monitor.isOffline.collectAsState()
}

/**
 * Calls [onReconnect] once each time the TV comes back online while this is in composition.
 * Nothing runs on first composition, so a screen that is already online does not reload.
 */
@Composable
fun ReloadOnReconnect(onReconnect: () -> Unit) {
    val offline by rememberDeviceOffline()
    val latest by rememberUpdatedState(onReconnect)
    var wasOffline by remember { mutableStateOf(offline) }
    LaunchedEffect(offline) {
        if (wasOffline && !offline) latest()
        wasOffline = offline
    }
}

internal fun openNetworkSettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Some TV builds strip the Wi-Fi screen; try the next one.
        } catch (_: SecurityException) {
        }
    }
}

/**
 * Full-screen state for a screen that has nothing to show because the TV is offline.
 * Retry is focused so the remote lands on it. Pair it with [ReloadOnReconnect] placed
 * outside the offline branch, since this leaves composition the moment the TV reconnects.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OfflineState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    DisposableEffect(Unit) {
        visibleOfflineStates.intValue++
        onDispose { visibleOfflineStates.intValue-- }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.SignalWifiOff,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = NuvioTheme.colors.TextTertiary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
        Text(
            text = stringResource(R.string.offline_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.offline_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp)
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            OfflineActionButton(
                label = stringResource(R.string.action_retry),
                onClick = onRetry,
                modifier = Modifier.focusRequester(retryFocus)
            )
            OfflineActionButton(
                label = stringResource(R.string.offline_network_settings),
                onClick = { openNetworkSettings(context) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OfflineActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Text(label)
    }
}

private enum class BannerState { Hidden, Offline, BackOnline }

/**
 * App-wide pill at the top of the screen while the TV is offline, then a short "Back online"
 * when it reconnects. It never takes focus, so D-pad navigation underneath is unaffected.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OfflineBanner(
    suppressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val offline by rememberDeviceOffline()
    var state by remember { mutableStateOf(if (offline) BannerState.Offline else BannerState.Hidden) }
    LaunchedEffect(offline) {
        if (offline) {
            state = BannerState.Offline
        } else if (state == BannerState.Offline) {
            state = BannerState.BackOnline
            delay(BACK_ONLINE_VISIBLE_MS)
            state = BannerState.Hidden
        }
    }
    // Remember the last visible content so the exit animation does not blank the pill.
    var shown by remember { mutableStateOf(BannerState.Offline) }
    if (state != BannerState.Hidden) shown = state

    val duration = NuvioTheme.motion.durations.medium
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = state != BannerState.Hidden && !suppressed &&
                !(state == BannerState.Offline && visibleOfflineStates.intValue > 0),
            enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { -it },
            exit = fadeOut(tween(duration)) + slideOutVertically(tween(duration)) { -it }
        ) {
            val isOffline = shown == BannerState.Offline
            val accent = if (isOffline) NuvioTheme.colors.Error else NuvioTheme.colors.Success
            val pill = RoundedCornerShape(NuvioTheme.radii.full)
            Row(
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.lg)
                    .background(NuvioTheme.colors.Menu, pill)
                    .border(1.dp, accent.copy(alpha = 0.45f), pill)
                    .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.SignalWifiOff else Icons.Default.Wifi,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(if (isOffline) R.string.offline_title else R.string.offline_back_online),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                if (isOffline) {
                    Text(
                        text = stringResource(R.string.offline_banner_reconnecting),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }
}

private const val BACK_ONLINE_VISIBLE_MS = 2_500L
