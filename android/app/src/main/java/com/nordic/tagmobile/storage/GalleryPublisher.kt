package com.nordic.tagmobile.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import java.io.File
import java.io.FileInputStream

/**
 * Makes recorded videos visible in the system Gallery / Photos app
 * via MediaStore (Movies/Tag/).
 */
object GalleryPublisher {

    fun publishVideo(context: Context, videoFile: File): Uri? {
        if (!videoFile.exists() || videoFile.length() <= 0L) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishMediaStore(context, videoFile)
            } else {
                publishLegacy(context, videoFile)
            }
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "GALLERY_PUBLISH_FAIL", e.message ?: "")
            null
        }
    }

    private fun publishMediaStore(context: Context, videoFile: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tag")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(videoFile).use { input -> input.copyTo(out) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        values.put(MediaStore.Video.Media.SIZE, videoFile.length())
        resolver.update(uri, values, null, null)
        TagLogger.log(LogCategory.FILE, "GALLERY_PUBLISH_OK", "${videoFile.name} → $uri")
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, videoFile: File): Uri? {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val tagDir = File(movies, "Tag").also { it.mkdirs() }
        val dest = File(tagDir, videoFile.name)
        videoFile.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf("video/mp4"),
            null,
        )
        TagLogger.log(LogCategory.FILE, "GALLERY_PUBLISH_OK", dest.absolutePath)
        return Uri.fromFile(dest)
    }
}
