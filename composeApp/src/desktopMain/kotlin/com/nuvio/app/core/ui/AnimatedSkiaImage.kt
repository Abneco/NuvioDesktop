package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import coil3.Extras
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Per-request switch for animated playback of GIF / animated WebP images.
 *
 * `true` decodes animated images into an [AnimatedSkiaImage], `false` forces a still first frame, and
 * unset leaves the choice to the caller (still frame for plain Coil composables).
 */
internal val NuvioAnimatedImageKey = Extras.Key<Boolean?>(default = null)

/** Animated images above this size fall back to a still frame so playback stays cheap. */
internal const val MaxAnimatedImageDimensionPx = 640

/**
 * Coil image holding an encoded animation. It draws its first frame when used as a plain Coil image and
 * exposes [painter] for animated playback.
 */
internal class AnimatedSkiaImage(
    val data: Data,
    override val width: Int,
    override val height: Int,
    val frameDelaysMs: IntArray,
    /** For each frame, the earlier frame that must already be on the canvas (-1 when independent). */
    val requiredFrames: IntArray,
    val firstFrame: SkiaImage,
) : coil3.Image {

    override val size: Long = data.size.toLong() + width.toLong() * height.toLong() * 4L

    override val shareable: Boolean = true

    override fun draw(canvas: Canvas) {
        canvas.drawImage(firstFrame, 0f, 0f)
    }

    /** One painter per decoded image, so every composable showing the same cached image shares one decoder. */
    val painter: Painter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AnimatedSkiaPainter(this) }
}

internal class AnimatedSkiaPainter(source: AnimatedSkiaImage) : Painter() {
    private val runner = FrameRunner(source) { frame = it }
    private var frame: SkiaImage by mutableStateOf(source.firstFrame)

    // One native paint per painter, reused for every draw.
    private val paint = SkiaPaint()

    override val intrinsicSize: Size = Size(source.width.toFloat(), source.height.toFloat())

    override fun DrawScope.onDraw() {
        // Drawing is the only signal we get that someone still sees this image; the ticker stops on its own
        // once draws stop, so playback never outlives the composable showing it.
        AnimationTicker.markDrawn(runner)
        val image = frame
        // The Skia image is drawn directly. Compose's ImageBitmap wrapper would allocate a native copy of every
        // frame that is only freed when the JVM garbage-collects, which grows native memory into gigabytes.
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(
                    size.width.roundToInt().coerceAtLeast(1).toFloat(),
                    size.height.roundToInt().coerceAtLeast(1).toFloat(),
                ),
                SamplingMode.LINEAR,
                paint,
                true,
            )
        }
    }

    override fun applyAlpha(alpha: Float): Boolean {
        paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return true
    }

    // Not handled here: returning false makes Compose draw this painter through a layer that applies the filter.
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean = colorFilter == null
}

private const val StaleAfterMs = 400L
private const val IdleCheckMs = 100L
private const val FrameRingSize = 3

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

/** Decodes the frames of one animation, one frame at a time, into a reused working bitmap. */
private class FrameRunner(
    private val source: AnimatedSkiaImage,
    private val publish: (SkiaImage) -> Unit,
) {
    @Volatile var lastDrawnAtMs: Long = 0L
    @Volatile var registered: Boolean = false
    @Volatile var failed: Boolean = false
    var nextDueAtMs: Long = 0L

    private var codec: Codec? = null
    private var work: Bitmap? = null
    private var frameIndex = 0
    private var lastDecoded = -1

    // Published frames are closed a few frames later, once the UI thread can no longer be drawing them.
    private val ring = arrayOfNulls<SkiaImage>(FrameRingSize)
    private var ringPos = 0
    private var lastPublished: SkiaImage? = null

    fun advance(now: Long) {
        try {
            val frames = source.frameDelaysMs.size
            val activeCodec = codec ?: Codec.makeFromData(source.data).also { codec = it }
            val bitmap = work ?: Bitmap().also {
                it.allocPixels(ImageInfo.makeN32Premul(source.width, source.height))
                work = it
            }

            val index = frameIndex
            // Playing in order lets Skia build each frame on top of the previous one instead of
            // re-decoding every frame it depends on.
            val required = source.requiredFrames[index]
            val prior = if (required >= 0 && required == lastDecoded) required else -1
            activeCodec.readPixels(bitmap, index, prior)
            lastDecoded = index

            val image = SkiaImage.makeFromBitmap(bitmap)
            publish(image)
            lastPublished = image
            ring[ringPos]?.takeIf { it !== image }?.close()
            ring[ringPos] = image
            ringPos = (ringPos + 1) % FrameRingSize

            val delayMs = source.frameDelaysMs[index]
            // Browsers treat very short GIF delays as 100 ms; matching that keeps timing familiar.
            nextDueAtMs = now + if (delayMs <= 10) 100L else delayMs.toLong()
            frameIndex = (index + 1) % frames
        } catch (_: Throwable) {
            failed = true
        }
    }

    /** Frees native memory while keeping the frame still on screen. */
    fun release() {
        runCatching { codec?.close() }
        runCatching { work?.close() }
        codec = null
        work = null
        lastDecoded = -1
        for (i in ring.indices) {
            val image = ring[i]
            if (image != null && image !== lastPublished) runCatching { image.close() }
            ring[i] = null
        }
        lastPublished?.let { ring[0] = it }
        ringPos = if (lastPublished != null) 1 else 0
    }
}

/**
 * One shared loop advances every animation that was drawn recently and exits when nothing is visible.
 */
private object AnimationTicker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = LinkedHashSet<FrameRunner>()
    private var job: Job? = null

    fun markDrawn(runner: FrameRunner) {
        runner.lastDrawnAtMs = nowMs()
        if (!runner.registered && !runner.failed) register(runner)
    }

    private fun register(runner: FrameRunner) {
        synchronized(this) {
            if (runner.registered) return
            runner.registered = true
            runner.nextDueAtMs = 0L
            active.add(runner)
            if (job?.isActive != true) {
                job = scope.launch { loop() }
            }
        }
    }

    private suspend fun loop() {
        while (true) {
            val snapshot = synchronized(this) {
                if (active.isEmpty()) {
                    job = null
                    return
                }
                active.toList()
            }

            val now = nowMs()
            var wakeAt = now + IdleCheckMs
            for (runner in snapshot) {
                if (runner.failed || now - runner.lastDrawnAtMs > StaleAfterMs) {
                    synchronized(this) {
                        active.remove(runner)
                        runner.registered = false
                    }
                    runner.release()
                    continue
                }
                if (now >= runner.nextDueAtMs) runner.advance(now)
                if (runner.nextDueAtMs < wakeAt) wakeAt = runner.nextDueAtMs
            }
            delay((wakeAt - nowMs()).coerceIn(4L, IdleCheckMs))
        }
    }
}
