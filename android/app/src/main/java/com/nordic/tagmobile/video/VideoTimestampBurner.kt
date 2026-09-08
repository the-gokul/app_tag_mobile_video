package com.nordic.tagmobile.video

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Burns a wall-clock timestamp watermark into an MP4 (same style as the camera overlay).
 * Runs after MediaRecorder finishes; replaces [videoFile] in place on success.
 */
@UnstableApi
object VideoTimestampBurner {

    // Same format as DeviceActivity / activity_device.xml timestampText
    private val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss:SSS", Locale.US)

    /**
     * @param syncBaseUnixMs wall clock at recording Start (same as TagSession.syncBaseUnixMs)
     * @return true if burned file replaced the original
     */
    fun burnInPlace(
        context: Context,
        videoFile: File,
        syncBaseUnixMs: Long,
        timeoutSec: Long = 180L,
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() < 100L) return false
        val baseMs = if (syncBaseUnixMs > 0L) syncBaseUnixMs else System.currentTimeMillis()
        val outFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}_burn.mp4")
        if (outFile.exists()) outFile.delete()

        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val appContext = context.applicationContext
        val transformerRef = AtomicReference<Transformer?>(null)
        val main = Handler(Looper.getMainLooper())

        val textOverlay = object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val wallMs = baseMs + presentationTimeUs / 1000L
                val label = fmt.format(Date(wallMs))
                // Match activity_device.xml timestampText: white, monospace 14sp, #8C000000 bg
                return SpannableString(label).apply {
                    val flags = SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    setSpan(ForegroundColorSpan(Color.WHITE), 0, length, flags)
                    setSpan(AbsoluteSizeSpan(14, /* dip= */ true), 0, length, flags)
                    setSpan(TypefaceSpan("monospace"), 0, length, flags)
                    setSpan(BackgroundColorSpan(0x8C000000.toInt()), 0, length, flags)
                }
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                // Media3 NDC: (-1,-1)=bottom-left, (1,1)=top-right.
                // Camera UI places timestamp bottom-center above the Start button.
                OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, -0.42f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .setScale(2.4f, 2.4f)
                    .build()
        }

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
            .setEffects(
                Effects(
                    /* audioProcessors= */ emptyList(),
                    /* videoEffects= */ listOf(OverlayEffect(ImmutableList.of(textOverlay))),
                ),
            )
            .build()

        val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()

        return try {
            // Media3 Transformer must be created/started on a Looper thread
            main.post {
                try {
                    val transformer = Transformer.Builder(appContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    ok.set(true)
                                    done.countDown()
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    TagLogger.log(
                                        LogCategory.ERRORS,
                                        "TIMESTAMP_BURN_FAIL",
                                        exportException.message ?: "transform error",
                                    )
                                    done.countDown()
                                }
                            },
                        )
                        .build()
                    transformerRef.set(transformer)
                    transformer.start(composition, outFile.absolutePath)
                } catch (e: Exception) {
                    TagLogger.log(LogCategory.ERRORS, "TIMESTAMP_BURN_FAIL", e.message ?: "")
                    done.countDown()
                }
            }

            val finished = done.await(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                main.post { transformerRef.get()?.cancel() }
                TagLogger.log(LogCategory.ERRORS, "TIMESTAMP_BURN_TIMEOUT", videoFile.name)
                outFile.delete()
                return false
            }
            if (!ok.get() || !outFile.exists() || outFile.length() < 100L) {
                outFile.delete()
                return false
            }
            if (!videoFile.delete()) {
                TagLogger.log(LogCategory.ERRORS, "TIMESTAMP_BURN_REPLACE", "could not delete original")
            }
            val renamed = outFile.renameTo(videoFile)
            if (!renamed) {
                outFile.copyTo(videoFile, overwrite = true)
                outFile.delete()
            }
            TagLogger.log(LogCategory.FILE, "TIMESTAMP_BURN_OK", videoFile.name)
            true
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "TIMESTAMP_BURN_FAIL", e.message ?: "")
            outFile.delete()
            false
        }
    }
}
