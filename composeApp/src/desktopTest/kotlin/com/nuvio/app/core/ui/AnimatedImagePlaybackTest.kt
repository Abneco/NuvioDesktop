package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

class AnimatedImagePlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun animatedGifPlaysThroughNuvioAsyncImage() = withGifServer { url ->
        compose.setContent {
            NuvioAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
                desktopAnimatedImages = true,
            )
        }

        val image = compose.onNodeWithTag("image")
        fun waitForColor(isExpected: (red: Float, green: Float, blue: Float) -> Boolean) {
            compose.waitUntil(timeoutMillis = 5_000) {
                val color = image.captureToImage().toPixelMap()[32, 32]
                isExpected(color.red, color.green, color.blue)
            }
        }
        // Frames are red, green, then blue, 200 ms each.
        waitForColor { r, g, _ -> r > 0.8f && g < 0.1f }
        waitForColor { r, g, _ -> g > 0.8f && r < 0.1f }
        waitForColor { r, _, b -> b > 0.8f && r < 0.1f }
    }

    @Test
    fun gifStaysOnItsFirstFrameUnlessAnimationIsRequested() = withGifServer { url ->
        compose.setContent {
            NuvioAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("image"),
                imageLoader = gifLoader(),
            )
        }

        val image = compose.onNodeWithTag("image")
        compose.waitUntil(timeoutMillis = 5_000) {
            val color = image.captureToImage().toPixelMap()[32, 32]
            color.red > 0.8f && color.green < 0.1f
        }
        // Several frame durations later it must still show the first frame.
        Thread.sleep(900)
        val color = image.captureToImage().toPixelMap()[32, 32]
        assertTrue(color.red > 0.8f && color.green < 0.1f && color.blue < 0.1f, "Expected a still red frame")
        assertEquals(1f, color.alpha)
    }

    private fun gifLoader(): ImageLoader =
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(SkiaGifDecoder.Factory()) }
            .build()

    private fun withGifServer(block: (url: String) -> Unit) {
        val gif = Base64.getDecoder().decode(GIF)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/gif")
            exchange.sendResponseHeaders(200, gif.size.toLong())
            exchange.responseBody.use { it.write(gif) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/badge.gif?v=2")
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        // 8x8 solid-colour frames (red, green, blue), 200 ms each, looping forever.
        const val GIF =
            "R0lGODlhCAAIAPEAAP8AAAD/AAAA////ACH/C05FVFNDQVBFMi4wAwEAAAAh+QQEFAAAACwAAAAACAAIAAACBoSPqcvtXQAh+QQEFAAAACwAAAAACAAIAAACBoyPqcvtXQAh+QQEFAAAACwAAAAACAAIAAACBpSPqcvtXQA7"
    }
}
