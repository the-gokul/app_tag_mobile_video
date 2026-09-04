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
    val csvFile: File,
    val logFile: File,
    val packetCount: Int,
    val sampleCount: Int,
    val status: String,
    val savedAtMs: Long,
)

object RecordingStore {
    private const val META_SUFFIX = ".meta.json"

    private fun csvDir(context: Context): File =
        File(context.filesDir, "csv").also { it.mkdirs() }

    private fun logsDir(context: Context): File =
        File(context.filesDir, "logs").also { it.mkdirs() }

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

    fun csvFile(context: Context, baseName: String): File =
        File(csvDir(context), "$baseName.csv")

    fun logFile(context: Context, baseName: String): File =
        File(logsDir(context), "$baseName.log")

    private fun metaFile(context: Context, baseName: String): File =
        File(csvDir(context), "$baseName$META_SUFFIX")

    fun saveRecording(
        context: Context,
        baseName: String,
        csvContent: String,
        logContent: String,
        packetCount: Int,
        sampleCount: Int,
        status: String,
    ): HistoryEntry {
        val csv = csvFile(context, baseName)
        val log = logFile(context, baseName)
        csv.writeText(csvContent, Charsets.UTF_8)
        log.writeText(logContent, Charsets.UTF_8)
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
            "csv=${csv.name} log=${log.name} packets=$packetCount samples=$sampleCount",
        )
        return HistoryEntry(
            baseName = baseName,
            csvFile = csv,
            logFile = log,
            packetCount = packetCount,
            sampleCount = sampleCount,
            status = status,
            savedAtMs = savedAt,
        )
    }

    fun listHistory(context: Context): List<HistoryEntry> {
        val csvRoot = csvDir(context)
        val logsRoot = logsDir(context)
        val bases = csvRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".csv") }
            ?.map { it.name.removeSuffix(".csv") }
            ?.distinct()
            ?: emptyList()

        return bases.mapNotNull { base ->
            val csv = File(csvRoot, "$base.csv")
            val log = File(logsRoot, "$base.log")
            if (!csv.exists()) return@mapNotNull null
            val meta = metaFile(context, base)
            val (packets, samples, status, savedAt) = if (meta.exists()) {
                try {
                    val o = JSONObject(meta.readText(Charsets.UTF_8))
                    Meta(
                        o.optInt("packetCount", 0),
                        o.optInt("sampleCount", 0),
                        o.optString("status", "Saved"),
                        o.optLong("savedAtMs", csv.lastModified()),
                    )
                } catch (_: Exception) {
                    Meta(0, 0, "Saved", csv.lastModified())
                }
            } else {
                Meta(0, 0, "Saved", csv.lastModified())
            }
            HistoryEntry(
                baseName = base,
                csvFile = csv,
                logFile = log,
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
        entry.csvFile.delete()
        entry.logFile.delete()
        metaFile(context, entry.baseName).delete()
    }
}
