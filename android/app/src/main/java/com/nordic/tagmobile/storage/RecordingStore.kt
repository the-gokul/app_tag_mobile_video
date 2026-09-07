package com.nordic.tagmobile.storage

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val baseName: String,
    val dataFile: File,
    val logFile: File,
    val videoFile: File?,
    val packetCount: Int,
    val sampleCount: Int,
    val status: String,
    val savedAtMs: Long,
)

object RecordingStore {
    private const val META_SUFFIX = ".meta.json"

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").also { it.mkdirs() }

    private fun logsDir(context: Context): File =
        File(context.filesDir, "logs").also { it.mkdirs() }

    private fun videosDir(context: Context): File =
        File(context.filesDir, "videos").also { it.mkdirs() }

    fun makeBaseName(
        deviceName: String,
        atMs: Long = System.currentTimeMillis(),
        profilePrefix: String = "",
    ): String {
        val safe = deviceName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Tag" }
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val datePart = fmt.format(Date(atMs))
        return if (profilePrefix.isNotBlank()) "${profilePrefix}_${safe}_${datePart}"
        else "${safe}_${datePart}"
    }

    /** Prefer .xlsx; fall back to legacy .csv for older recordings. */
    fun dataFile(context: Context, baseName: String): File =
        File(dataDir(context), "$baseName.xlsx")

    fun findDataFile(context: Context, baseName: String): File? {
        val xlsx = File(dataDir(context), "$baseName.xlsx")
        if (xlsx.exists()) return xlsx
        val csv = File(dataDir(context), "$baseName.csv")
        if (csv.exists()) return csv
        return null
    }

    fun logFile(context: Context, baseName: String): File =
        File(logsDir(context), "$baseName.log")

    fun findVideoFile(context: Context, baseName: String): File? {
        val dir = videosDir(context)
        val mp4 = File(dir, "$baseName.mp4")
        if (mp4.exists()) return mp4
        val webm = File(dir, "$baseName.webm")
        if (webm.exists()) return webm
        return null
    }

    private fun metaFile(context: Context, baseName: String): File =
        File(dataDir(context), "$baseName$META_SUFFIX")

    fun saveRecording(
        context: Context,
        baseName: String,
        logContent: String,
        packetCount: Int,
        sampleCount: Int,
        status: String,
    ): HistoryEntry {
        val dataF = findDataFile(context, baseName) ?: dataFile(context, baseName)
        val logF = logFile(context, baseName)
        val vidF = findVideoFile(context, baseName)
        logF.writeText(logContent, Charsets.UTF_8)
        val savedAt = System.currentTimeMillis()
        metaFile(context, baseName).writeText(
            JSONObject()
                .put("baseName", baseName)
                .put("packetCount", packetCount)
                .put("sampleCount", sampleCount)
                .put("status", status)
                .put("savedAtMs", savedAt)
                .toString(),
            Charsets.UTF_8,
        )
        TagLogger.log(
            LogCategory.FILE,
            "AUTO_SAVE_OK",
            "data=${dataF.name} log=${logF.name} packets=$packetCount samples=$sampleCount",
        )
        return HistoryEntry(
            baseName = baseName,
            dataFile = dataF,
            logFile = logF,
            videoFile = vidF,
            packetCount = packetCount,
            sampleCount = sampleCount,
            status = status,
            savedAtMs = savedAt,
        )
    }

    fun listHistory(context: Context): List<HistoryEntry> {
        val dataRoot = dataDir(context)
        val logsRoot = logsDir(context)
        val bases = dataRoot.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".xlsx") || it.name.endsWith(".csv")) }
            ?.map {
                when {
                    it.name.endsWith(".xlsx") -> it.name.removeSuffix(".xlsx")
                    else -> it.name.removeSuffix(".csv")
                }
            }
            ?.distinct()
            ?: emptyList()

        return bases.mapNotNull { base ->
            val dataF = findDataFile(context, base) ?: return@mapNotNull null
            val logF = File(logsRoot, "$base.log")
            val meta = metaFile(context, base)
            val (packets, samples, status, savedAt) = if (meta.exists()) {
                try {
                    val o = JSONObject(meta.readText(Charsets.UTF_8))
                    Meta(
                        o.optInt("packetCount", 0),
                        o.optInt("sampleCount", 0),
                        o.optString("status", "Saved"),
                        o.optLong("savedAtMs", dataF.lastModified()),
                    )
                } catch (_: Exception) {
                    Meta(0, 0, "Saved", dataF.lastModified())
                }
            } else {
                Meta(0, 0, "Saved", dataF.lastModified())
            }
            HistoryEntry(
                baseName = base,
                dataFile = dataF,
                logFile = logF,
                videoFile = findVideoFile(context, base),
                packetCount = packets,
                sampleCount = samples,
                status = status,
                savedAtMs = savedAt,
            )
        }.sortedByDescending { it.savedAtMs }
    }

    private data class Meta(
        val packets: Int,
        val samples: Int,
        val status: String,
        val savedAt: Long,
    )

    /**
     * Permanently erase session from the device:
     * app-private data/log/video/meta + any MediaStore/gallery copies + common public folders.
     */
    fun deleteEntry(context: Context, entry: HistoryEntry) {
        val names = linkedSetOf<String>()
        entry.videoFile?.name?.let { names.add(it) }
        names.add("${entry.baseName}.mp4")
        names.add("${entry.baseName}.webm")
        names.add("${entry.baseName}_burn.mp4")

        entry.dataFile.delete()
        File(dataDir(context), "${entry.baseName}.xlsx").delete()
        File(dataDir(context), "${entry.baseName}.csv").delete()
        entry.logFile.delete()
        entry.videoFile?.delete()
        File(videosDir(context), "${entry.baseName}.mp4").delete()
        File(videosDir(context), "${entry.baseName}.webm").delete()
        File(videosDir(context), "${entry.baseName}_burn.mp4").delete()
        metaFile(context, entry.baseName).delete()

        names.forEach { name ->
            deletePublicVideoCopies(context, name)
            deleteMediaStoreVideos(context, name)
        }

        TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", entry.baseName)
    }

    private fun deletePublicVideoCopies(context: Context, fileName: String) {
        val dirs = mutableListOf<File>()
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"))
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { dirs.add(it) }
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "videos")) }
        dirs.forEach { dir ->
            try {
                File(dir, fileName).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun deleteMediaStoreVideos(context: Context, fileName: String) {
        try {
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
            resolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(fileName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    resolver.delete(uri, null, null)
                }
            }
            TagLogger.log(LogCategory.FILE, "MEDIASTORE_DELETE", fileName)
        } catch (e: Exception) {
            TagLogger.log(LogCategory.ERRORS, "MEDIASTORE_DELETE_FAIL", "${e.message} file=$fileName")
        }
    }
}
