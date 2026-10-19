package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

private const val MAX_FRAME_DIMENSION = 512

/**
 * Decodes GIFs (and, on request, animated WebPs) with Skia.
 *
 * By default only the first frame is decoded, downscaled to keep memory low. When the request sets
 * [NuvioAnimatedImageKey] the result is an [AnimatedSkiaImage] that [NuvioAsyncImage] plays back.
 */
class SkiaGifDecoder(
    private val source: ImageSource,
    private val animate: Boolean,
    private val isGif: Boolean,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        // Peek so a decoder that declines (static WebP) leaves the source intact for the next decoder.
        val bytes = withContext(Dispatchers.IO) {
            source.source().peek().readByteArray()
        }
        if (bytes.isEmpty()) return null

        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        try {
            val count = codec.frameCount
            if (count <= 0) return null

            val w = codec.width
            val h = codec.height
            if (w <= 0 || h <= 0) return null

            val canAnimate = animate && count > 1 &&
                w <= MaxAnimatedImageDimensionPx && h <= MaxAnimatedImageDimensionPx
            // Only GIFs are handled when not animating; other formats belong to Coil's default decoder.
            if (!isGif && !canAnimate) return null

            if (canAnimate) return decodeAnimated(codec, data, count, w, h)
            return decodeStill(codec, w, h)
        } finally {
            codec.close()
        }
    }

    private fun decodeAnimated(codec: Codec, data: Data, count: Int, w: Int, h: Int): DecodeResult {
        // framesInfo reads every frame in one native call; per-frame getFrameInfo(i) is unreliable in Skiko.
        val frames = codec.framesInfo
        val delays = IntArray(count) { frames[it].duration }
        val required = IntArray(count) { frames[it].requiredFrame }
        val firstFrame = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
        try {
            codec.readPixels(firstFrame, 0)
            val image = AnimatedSkiaImage(
                data = data,
                width = w,
                height = h,
                frameDelaysMs = delays,
                requiredFrames = required,
                firstFrame = Image.makeFromBitmap(firstFrame),
            )
            return DecodeResult(image = image, isSampled = false)
        } finally {
            firstFrame.close()
        }
    }

    private fun decodeStill(codec: Codec, w: Int, h: Int): DecodeResult {
        val scale = if (w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION) {
            minOf(MAX_FRAME_DIMENSION.toFloat() / w, MAX_FRAME_DIMENSION.toFloat() / h)
        } else 1f

        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val needsScale = tw != w || th != h

        val bitmap: Bitmap
        if (needsScale) {
            val fullBitmap = Bitmap().apply {
                allocPixels(ImageInfo.makeN32Premul(w, h))
            }
            try {
                codec.readPixels(fullBitmap, 0)
                bitmap = Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(tw, th))
                }
                val skiaImg = Image.makeFromBitmap(fullBitmap)
                try {
                    Canvas(bitmap).drawImageRect(
                        skiaImg,
                        Rect.makeWH(w.toFloat(), h.toFloat()),
                        Rect.makeWH(tw.toFloat(), th.toFloat()),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                } finally {
                    skiaImg.close()
                }
            } finally {
                fullBitmap.close()
            }
        } else {
            bitmap = Bitmap().apply {
                allocPixels(ImageInfo.makeN32Premul(tw, th))
            }
            codec.readPixels(bitmap, 0)
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = needsScale,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val mimeType = result.mimeType
            val fileName = result.source.fileOrNull()?.name
            val isGif = mimeType?.equals("image/gif", ignoreCase = true) == true ||
                fileName?.endsWith(".gif", ignoreCase = true) == true
            val animate = options.extras[NuvioAnimatedImageKey] == true
            val isWebp = mimeType?.equals("image/webp", ignoreCase = true) == true ||
                fileName?.endsWith(".webp", ignoreCase = true) == true
            if (!isGif && !(animate && isWebp)) return null
            return SkiaGifDecoder(result.source, animate = animate, isGif = isGif)
        }
    }
}
