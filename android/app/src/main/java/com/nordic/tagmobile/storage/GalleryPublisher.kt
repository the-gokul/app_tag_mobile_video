package com.nordic.tagmobile.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import java.io.File

/**
 * Copies an app-private recording into the public Movies/Tag folder so
 * system Gallery / Photos apps can see it. Keeps the original file for History.
 * [deletePublishedVideo] removes that Gallery copy for permanent erase.
 */
object GalleryPublisher {

    private const val TAG_RELATIVE_PATH = "Movies/Tag"

    fun publishVideo(context: Context, videoFile: File): Uri? {
        if (!videoFile.exists() || videoFile.length() <= 0L) return null
        return try {
            val resolver = context.contentResolver
            val name = videoFile.name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tag")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = videoCollection()
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            TagLogger.log(LogCategory.FILE, "GALLERY_PUBLISH_OK", name)
            uri
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "GALLERY_PUBLISH_FAIL", e.message ?: "")
            null
        }
    }

    /** Delete all Movies/Tag Gallery copies that match [displayName] (e.g. session.mp4). */
    fun deletePublishedVideo(context: Context, displayName: String?) {
        if (displayName.isNullOrBlank()) return
        try {
            val resolver = context.contentResolver
            val collection = videoCollection()
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Video.Media.DISPLAY_NAME}=? AND " +
                    "(${MediaStore.Video.Media.RELATIVE_PATH}=? OR ${MediaStore.Video.Media.RELATIVE_PATH}=?)"
                args = arrayOf(displayName, "$TAG_RELATIVE_PATH/", TAG_RELATIVE_PATH)
            } else {
                selection = "${MediaStore.Video.Media.DISPLAY_NAME}=?"
                args = arrayOf(displayName)
            }
            var removed = 0
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    removed += resolver.delete(uri, null, null)
                }
            }
            // Fallback: match by name only if path filter found nothing (OEM path variants)
            if (removed == 0) {
                resolver.query(
                    collection,
                    projection,
                    "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                    arrayOf(displayName),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        removed += resolver.delete(uri, null, null)
                    }
                }
            }
            TagLogger.log(LogCategory.FILE, "GALLERY_DELETE", "$displayName removed=$removed")
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "GALLERY_DELETE_FAIL", e.message ?: "")
        }
    }

    private fun videoCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }
}
