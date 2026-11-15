package com.nuvio.tv.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.ConnectivityMonitor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.max
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

// Glass surfaces shared with the sign-in screen, so offline reads as part of the same app.
private val GlassPane = Color.White.copy(alpha = 0.03f)
private val GlassBorder = Color.White.copy(alpha = 0.08f)
private val GlassButton = Color.White.copy(alpha = 0.06f)
private val GlassButtonBorder = Color.White.copy(alpha = 0.10f)

/**
 * Full-screen state for a screen that has nothing to show because the TV is offline: a
 * themed diagonal gradient (pure black in AMOLED mode), the theme wordmark, the message and
 * Network settings on the left, and things to try on a glass panel on the right. There is
 * no Retry: pair it with [ReloadOnReconnect] placed outside the offline branch, which
 * reloads the screen when the TV reconnects and this leaves composition.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OfflineState(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
    DisposableEffect(Unit) {
        visibleOfflineStates.intValue++
        onDispose { visibleOfflineStates.intValue-- }
    }

    val amoled = isAmoled()
    val error = NuvioTheme.colors.Error

    Row(
        modifier = modifier
            .fillMaxSize()
            .offlineGradientBackground(
                accent = NuvioTheme.palette.secondary,
                base = NuvioTheme.colors.Background,
                warning = error,
                amoled = amoled
            )
            .padding(horizontal = 96.dp, vertical = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Follows the selected theme (Gold, Jade, Rose Gold, Arctic Blue, Graphite, default).
            BrandWordmark(modifier = Modifier.height(32.dp))
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = offlineTitle(highlight = error),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 44.sp,
                    lineHeight = 50.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.offline_page_body),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.widthIn(max = 420.dp)
            )
            Spacer(modifier = Modifier.height(40.dp))
            // No Retry: the page reloads on its own the moment the connection is back.
            OfflineActionButton(
                label = stringResource(R.string.offline_network_settings),
                icon = Icons.Default.Settings,
                onClick = { openNetworkSettings(context) },
                modifier = Modifier.focusRequester(settingsFocus)
            )
        }

        OfflineTipsPanel(amoled = amoled, modifier = Modifier.width(380.dp))
    }
}

/** AMOLED mode makes the background pure black; offline surfaces follow it. */
@Composable
private fun isAmoled(): Boolean = NuvioTheme.colors.Background == Color.Black

/** "You're offline" with the highlight word in the error colour, found in any translation. */
@Composable
private fun offlineTitle(highlight: Color): AnnotatedString {
    val title = stringResource(R.string.offline_page_title)
    val word = stringResource(R.string.offline_page_title_highlight)
    val start = title.indexOf(word, ignoreCase = true)
    return buildAnnotatedString {
        append(title)
        if (word.isNotBlank() && start >= 0) {
            addStyle(SpanStyle(color = highlight), start, start + word.length)
        }
    }
}

/**
 * The sign-in screen's 122° diagonal, tinted with the active theme's accent and a faint warm
 * glow behind the glyph so the page reads as "something is wrong" without shouting.
 */
private fun Modifier.offlineGradientBackground(
    accent: Color,
    base: Color,
    warning: Color,
    amoled: Boolean,
): Modifier =
    drawWithCache {
        if (amoled) return@drawWithCache onDrawBehind { drawRect(Color.Black) }
        val top = accent.copy(alpha = 0.20f).compositeOver(base)
        val mid = accent.copy(alpha = 0.07f).compositeOver(base)
        val diagonal = Brush.linearGradient(
            colorStops = arrayOf(
                0f to top,
                0.30f to mid,
                0.62f to base,
                1f to Color.Black
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
        val glow = Brush.radialGradient(
            colors = listOf(warning.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.16f, size.height * 0.42f),
            radius = max(size.width, size.height) * 0.38f
        )
        onDrawBehind {
            drawRect(diagonal)
            drawRect(glow)
        }
    }

@Composable
private fun OfflineTipsPanel(amoled: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .background(if (amoled) Color.Black else GlassPane, shape)
            .border(1.dp, GlassBorder, shape)
            .padding(horizontal = 28.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(
            text = stringResource(R.string.offline_tips_title),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
            color = NuvioTheme.colors.TextTertiary
        )
        OfflineTip(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.offline_tip_connection_title),
            body = stringResource(R.string.offline_tip_connection_body)
        )
        OfflineTip(
            icon = Icons.Default.Router,
            title = stringResource(R.string.offline_tip_router_title),
            body = stringResource(R.string.offline_tip_router_body)
        )
        OfflineTip(
            icon = Icons.Default.Devices,
            title = stringResource(R.string.offline_tip_other_device_title),
            body = stringResource(R.string.offline_tip_other_device_body)
        )
    }
}

@Composable
private fun OfflineTip(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(GlassButton, RoundedCornerShape(12.dp))
                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

/** Same pill as the sign-in screen: glass at rest, solid white with black text when focused. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OfflineActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = GlassButton,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ButtonDefaults.shape(shape),
        border = ButtonDefaults.border(
            border = Border(border = BorderStroke(1.dp, GlassButtonBorder), shape = shape)
        )
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

private enum class BannerState { Hidden, Offline, BackOnline }

/**
 * App-wide pill at the top of the screen while the TV is offline, then a short "Back online"
 * when it reconnects. It never takes focus, so D-pad navigation underneath is unaffected.
 */
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
            BannerPill(isOffline = shown == BannerState.Offline)
        }
    }
}

/**
 * One pill that morphs between states instead of swapping: the accent colour fades from red
 * to green, the width eases to the new text, and the icon and label crossfade with a short
 * vertical slide.
 */
@Composable
private fun BannerPill(isOffline: Boolean) {
    val duration = NuvioTheme.motion.durations.medium
    val targetAccent = if (isOffline) NuvioTheme.colors.Error else NuvioTheme.colors.Success
    val accent by animateColorAsState(targetAccent, tween(duration), label = "bannerAccent")
    val pill = RoundedCornerShape(NuvioTheme.radii.full)
    val surface = if (isAmoled()) Color.Black else NuvioTheme.colors.Menu
    Box(
        modifier = Modifier
            .padding(top = 20.dp)
            .shadow(elevation = 18.dp, shape = pill, ambientColor = Color.Black, spotColor = Color.Black)
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.16f).compositeOver(surface), surface)
                ),
                pill
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), pill)
            .animateContentSize(tween(duration))
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
    ) {
        AnimatedContent(
            targetState = isOffline,
            transitionSpec = {
                (fadeIn(tween(duration, delayMillis = duration / 3)) +
                    slideInVertically(tween(duration)) { it / 2 }) togetherWith
                    (fadeOut(tween(duration / 2)) + slideOutVertically(tween(duration / 2)) { -it / 2 }) using
                    SizeTransform(clip = false)
            },
            label = "bannerContent"
        ) { offline ->
            BannerPillContent(isOffline = offline, accent = accent)
        }
    }
}

@Composable
private fun BannerPillContent(isOffline: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOffline) Icons.Default.SignalWifiOff else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(if (isOffline) R.string.offline_title else R.string.offline_back_online),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioTheme.colors.TextPrimary
        )
        if (isOffline) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .size(3.dp)
                    .background(NuvioTheme.colors.TextTertiary, CircleShape)
            )
            Text(
                text = stringResource(R.string.offline_banner_reconnecting),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

private const val BACK_ONLINE_VISIBLE_MS = 2_500L
