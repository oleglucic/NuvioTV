package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.R
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Whether [error] means the stream host could not be reached at all. An HTTP status means the
 * host answered, so it is a stream problem and is left to the normal recovery ladder.
 */
internal fun isNetworkPlaybackError(errorCode: Int, cause: Throwable?): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
    if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    ) {
        return true
    }
    var current = cause
    var depth = 0
    while (current != null && depth < 8) {
        if (current is UnknownHostException ||
            current is ConnectException ||
            current is NoRouteToHostException ||
            current is SocketTimeoutException ||
            current is SocketException
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/**
 * Gate for a playback error that looks like a network failure. Confirms whether the TV still
 * has internet; if it does, [redeliver] hands the error back to the normal recovery ladder.
 * If it does not, playback waits on the loading overlay and resumes at the same position when
 * the connection returns, without spending auto-retries or switching source.
 *
 * Returns `true` when it has taken over the error.
 */
internal fun PlayerRuntimeController.handleNetworkErrorConnectivity(
    error: PlaybackException,
    redeliver: () -> Unit,
): Boolean {
    val monitor = connectivityMonitor ?: return false
    if (!isNetworkPlaybackError(error.errorCode, error.cause)) return false

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val online = !monitor.isOfflineNow && monitor.recheck()
        if (online) {
            connectivityCheckedError = error
            redeliver()
            return@launch
        }

        Log.w(PlayerRuntimeController.TAG, "Network lost during playback; waiting for connection at ${savedPosition}ms")
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = true,
                waitingForConnection = true,
                loadingMessage = context.getString(R.string.player_waiting_for_connection),
                showPauseOverlay = false
            )
        }
        // Free the decoder while waiting; the overlay covers the frozen frame.
        releasePlayer(flushPlaybackState = false)

        monitor.awaitOnline()

        Log.i(PlayerRuntimeController.TAG, "Connection restored; resuming at ${savedPosition}ms")
        startupRetryCount = 0
        errorRetryCount = 0
        _uiState.update {
            it.copy(
                waitingForConnection = false,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                pendingSeekPosition = savedPosition.takeIf { pos -> pos > 0L } ?: it.pendingSeekPosition
            )
        }
        reinitializeCurrentStream(startPaused = paused)
    }
    return true
}
