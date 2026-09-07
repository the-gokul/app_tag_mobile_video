package com.nordic.tagmobile.storage

import android.content.Context
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
    val galleryUri: String? = null,
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
        galleryUri: String? = null,
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
                .put("galleryUri", galleryUri ?: JSONObject.NULL)
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
            galleryUri = galleryUri,
        )
    }

    fun updateGalleryUri(context: Context, baseName: String, galleryUri: String?) {
        val meta = metaFile(context, baseName)
        if (!meta.exists()) return
        try {
            val o = JSONObject(meta.readText(Charsets.UTF_8))
            o.put("galleryUri", galleryUri ?: JSONObject.NULL)
            meta.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
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
            val vidF = findVideoFile(context, base)
            var galleryUri: String? = null
            val (packets, samples, status, savedAt) = if (meta.exists()) {
                try {
                    val o = JSONObject(meta.readText(Charsets.UTF_8))
                    galleryUri = o.optString("galleryUri").takeIf { it.isNotBlank() && it != "null" }
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
                videoFile = vidF,
                packetCount = packets,
                sampleCount = samples,
                status = status,
                savedAtMs = savedAt,
                galleryUri = galleryUri,
            )
        }.sortedByDescending { it.savedAtMs }
    }

    private data class Meta(
        val packets: Int,
        val samples: Int,
        val status: String,
        val savedAt: Long,
    )

    fun deleteEntry(context: Context, entry: HistoryEntry) {
        // Permanent erase: app-private files AND Gallery / MediaStore copy
        val names = linkedSetOf(
            entry.videoFile?.name ?: "${entry.baseName}.mp4",
            "${entry.baseName}.mp4",
            "${entry.baseName}.webm",
            "${entry.baseName}_burn.mp4",
        )
        GalleryPublisher.deletePublished(
            context = context,
            displayName = null,
            galleryUri = entry.galleryUri,
        )
        names.forEach { GalleryPublisher.deleteByDisplayName(context, it) }

        entry.dataFile.delete()
        File(dataDir(context), "${entry.baseName}.xlsx").delete()
        File(dataDir(context), "${entry.baseName}.csv").delete()
        entry.logFile.delete()
        entry.videoFile?.delete()
        File(videosDir(context), "${entry.baseName}.mp4").delete()
        File(videosDir(context), "${entry.baseName}.webm").delete()
        File(videosDir(context), "${entry.baseName}_burn.mp4").delete()
        metaFile(context, entry.baseName).delete()
        TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", entry.baseName)
    }
}
