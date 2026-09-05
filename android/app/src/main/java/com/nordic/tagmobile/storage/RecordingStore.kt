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

    fun dataFile(context: Context, baseName: String): File =
        File(dataDir(context), "$baseName.xlsx")

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
        val dataF = dataFile(context, baseName)
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
            ?.filter { it.isFile && it.name.endsWith(".xlsx") }
            ?.map { it.name.removeSuffix(".xlsx") }
            ?.distinct()
            ?: emptyList()

        return bases.mapNotNull { base ->
            val dataF = File(dataRoot, "$base.xlsx")
            val logF = File(logsRoot, "$base.log")
            if (!dataF.exists()) return@mapNotNull null
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

    fun deleteEntry(context: Context, entry: HistoryEntry) {
        entry.dataFile.delete()
        entry.logFile.delete()
        entry.videoFile?.delete()
        metaFile(context, entry.baseName).delete()
    }
}
