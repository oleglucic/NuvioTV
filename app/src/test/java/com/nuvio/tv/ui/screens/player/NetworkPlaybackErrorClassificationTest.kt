package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPlaybackErrorClassificationTest {

    @Test
    fun `connection failed and timeout codes are network errors`() {
        assertTrue(isNetworkPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertTrue(isNetworkPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
    }

    @Test
    fun `http status is a stream error even with a socket cause`() {
        assertFalse(
            isNetworkPlaybackError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                IOException(ConnectException("refused"))
            )
        )
    }

    @Test
    fun `unspecified io error with dns failure in the chain is a network error`() {
        val cause = IOException("Source error", UnknownHostException("example.com"))
        assertTrue(isNetworkPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, cause))
    }

    @Test
    fun `decoder and parsing errors are not network errors`() {
        assertFalse(isNetworkPlaybackError(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
        assertFalse(
            isNetworkPlaybackError(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                IllegalStateException("bad box")
            )
        )
    }
}
