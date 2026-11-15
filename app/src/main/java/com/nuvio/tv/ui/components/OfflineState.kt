package com.nuvio.tv.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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

private enum class OfflinePagePhase { Hidden, Offline, BackOnline }

/** How many screens currently want the full-screen offline page (see [OfflineState]). */
private val offlinePageRequests = mutableIntStateOf(0)

/** What [OfflineFullscreenHost] is showing; the banner stays out of the way while it shows. */
private val offlinePagePhase = mutableStateOf(OfflinePagePhase.Hidden)

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
 * Placed by a screen that has nothing to show because the TV is offline. It only asks for the
 * page: [OfflineFullscreenHost] at the app root draws it, so the page can stay up through the
 * reconnect while the screen behind it reloads (pair this with [ReloadOnReconnect]).
 */
@Composable
fun OfflineState(
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        offlinePageRequests.intValue++
        onDispose { offlinePageRequests.intValue-- }
    }
    Box(modifier = modifier.fillMaxSize())
}

/**
 * App-root host for the full-screen offline page. Offline: theme gradient, wordmark, message,
 * Network settings and things to try. On reconnect the same page morphs to a green gradient
 * with the wordmark and "Back online" centred, holds briefly while the screen behind reloads,
 * then the whole page fades out.
 */
@Composable
fun OfflineFullscreenHost(
    suppressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val offline by rememberDeviceOffline()
    val requested = offlinePageRequests.intValue > 0
    var phase by offlinePagePhase
    LaunchedEffect(offline, requested) {
        when {
            offline && requested -> phase = OfflinePagePhase.Offline
            !offline && phase == OfflinePagePhase.Offline -> phase = OfflinePagePhase.BackOnline
            // Navigated away while still offline: nothing to celebrate, just close.
            offline && !requested && phase == OfflinePagePhase.Offline -> phase = OfflinePagePhase.Hidden
        }
    }
    // Separate effect so the reloading screen dropping its request cannot cancel the hold.
    LaunchedEffect(phase) {
        if (phase == OfflinePagePhase.BackOnline) {
            delay(BACK_ONLINE_PAGE_HOLD_MS)
            phase = OfflinePagePhase.Hidden
        }
    }
    // Keep the last visible phase so the fade-out still shows "Back online".
    var shown by remember { mutableStateOf(OfflinePagePhase.Offline) }
    if (phase != OfflinePagePhase.Hidden) shown = phase

    AnimatedVisibility(
        visible = phase != OfflinePagePhase.Hidden && !suppressed,
        enter = fadeIn(tween(NuvioTheme.motion.durations.medium)),
        exit = fadeOut(tween(PAGE_FADE_OUT_MS)),
        modifier = modifier
    ) {
        OfflinePage(backOnline = shown == OfflinePagePhase.BackOnline)
    }
}

@Composable
private fun OfflinePage(backOnline: Boolean) {
    val amoled = isAmoled()
    // One progress value (0 offline, 1 back online) drives every background layer through
    // graphicsLayer, so the morph only moves and fades cached layers: no per-frame shaders,
    // measuring or layout, which low-end TV GPUs cannot keep up with.
    val progress by animateFloatAsState(
        targetValue = if (backOnline) 1f else 0f,
        animationSpec = tween(MORPH_MS, easing = FastOutSlowInEasing),
        label = "offlineMorph"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        // AMOLED Mode keeps the background pure black, like every other screen.
        if (!amoled) {
            val accent = NuvioTheme.palette.secondary.copy(alpha = 0.22f)
            val success = NuvioTheme.colors.Success.copy(alpha = 0.24f)
            val warning = NuvioTheme.colors.Error.copy(alpha = 0.10f)
            GlowLayer(
                color = warning,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - progress
                    translationX = -size.width * 0.34f
                    translationY = -size.height * 0.05f
                    scaleX = 0.7f
                    scaleY = 0.7f
                }
            )
            // The travelling light: drawn once, centred. Offline it sits on the top-left corner
            // at 1.75x; back online it rests in the centre at 1x and turns green.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = lerp(-size.width * 0.5f, 0f, progress)
                        translationY = lerp(-size.height * 0.5f, -size.height * 0.05f, progress)
                        val scale = lerp(1.75f, 1f, progress)
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                GlowLayer(color = accent, modifier = Modifier.graphicsLayer { alpha = 1f - progress })
                GlowLayer(color = success, modifier = Modifier.graphicsLayer { alpha = progress })
            }
        }
        AnimatedContent(
            targetState = backOnline,
            transitionSpec = {
                // Sequential, not overlapping: never composite two full layouts at once.
                fadeIn(tween(MORPH_MS / 2, delayMillis = MORPH_MS / 2)) togetherWith
                    fadeOut(tween(MORPH_MS / 2))
            },
            label = "offlinePageContent"
        ) { online ->
            if (online) BackOnlineContent() else OfflineContent(blackSurfaces = pureBlackSurfaces())
        }
    }
}

/**
 * A soft radial light centred in its bounds, drawn over a 3x area so it is never cut off
 * when its layer is scaled or moved. The brush is built once per size.
 */
@Composable
private fun GlowLayer(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color,
                        0.45f to color.copy(alpha = color.alpha * 0.35f),
                        1f to Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = max(size.width, size.height) * 0.6f
                )
                onDrawBehind {
                    drawRect(brush, topLeft = Offset(-size.width, -size.height), size = size * 3f)
                }
            }
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OfflineContent(blackSurfaces: Boolean) {
    val context = LocalContext.current
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
    val error = NuvioTheme.colors.Error

    Row(
        modifier = Modifier
            .fillMaxSize()
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
                style = pageTitleStyle(),
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
            // The only focusable thing here, so keep focus from wandering to the screen behind.
            OfflineActionButton(
                label = stringResource(R.string.offline_network_settings),
                icon = Icons.Default.Settings,
                onClick = { openNetworkSettings(context) },
                modifier = Modifier
                    .focusRequester(settingsFocus)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
            )
        }

        OfflineTipsPanel(blackSurfaces = blackSurfaces, modifier = Modifier.width(380.dp))
    }
}

@Composable
private fun BackOnlineContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandWordmark(modifier = Modifier.height(40.dp))
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = backOnlineTitle(highlight = NuvioTheme.colors.Success),
            style = pageTitleStyle(),
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

@Composable
private fun pageTitleStyle() = MaterialTheme.typography.displayLarge.copy(
    fontSize = 44.sp,
    lineHeight = 50.sp,
    fontWeight = FontWeight.SemiBold
)

/**
 * AMOLED Mode: only the app background is pure black. Detected from the resolved theme
 * colour so it always matches what the rest of the app draws.
 */
@Composable
private fun isAmoled(): Boolean = NuvioTheme.colors.Background == Color.Black

/** "Pure Black Surfaces" (on top of AMOLED): cards, panels and menus are pure black too. */
@Composable
private fun pureBlackSurfaces(): Boolean = NuvioTheme.colors.Panel == Color.Black

/** "You're offline" with the highlight word in the error colour, found in any translation. */
@Composable
private fun offlineTitle(highlight: Color): AnnotatedString = highlighted(
    stringResource(R.string.offline_page_title),
    stringResource(R.string.offline_page_title_highlight),
    highlight
)

/** "Back online" with "online" in the success colour, mirroring the offline title. */
@Composable
private fun backOnlineTitle(highlight: Color): AnnotatedString = highlighted(
    stringResource(R.string.offline_back_online),
    stringResource(R.string.offline_back_online_highlight),
    highlight
)

private fun highlighted(text: String, word: String, color: Color): AnnotatedString {
    val start = if (word.isBlank()) -1 else text.indexOf(word, ignoreCase = true)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(color = color), start, start + word.length)
    }
}

@Composable
private fun OfflineTipsPanel(blackSurfaces: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .background(if (blackSurfaces) Color.Black else GlassPane, shape)
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
        } else if (state == BannerState.Offline && offlinePagePhase.value != OfflinePagePhase.Hidden) {
            // The full-screen page plays its own "Back online"; do not repeat it.
            state = BannerState.Hidden
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
                offlinePagePhase.value == OfflinePagePhase.Hidden,
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
    // Menu is already pure black with Pure Black Surfaces and keeps its shade otherwise.
    val surface = NuvioTheme.colors.Menu
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
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
    ) {
        AnimatedContent(
            targetState = isOffline,
            transitionSpec = {
                fadeIn(tween(duration / 2, delayMillis = duration / 3)) togetherWith
                    fadeOut(tween(duration / 3)) using
                    SizeTransform(clip = false) { _, _ -> tween(duration, easing = FastOutSlowInEasing) }
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
private const val BACK_ONLINE_PAGE_HOLD_MS = 1_600L
private const val MORPH_MS = 800
private const val PAGE_FADE_OUT_MS = 700
