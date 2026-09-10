package com.nordic.tagmobile.storage

import android.content.Context
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class HistoryEntry(
    /** Session id (SESSION-…) for new recordings, or legacy flat base name. */
    val baseName: String,
    val dataFile: File,
    val logFile: File,
    val videoFile: File?,
    val packetCount: Int,
    val sampleCount: Int,
    val status: String,
    val savedAtMs: Long,
    val galleryUri: String? = null,
    /** Non-null for new session-folder packages. */
    val sessionDir: File? = null,
)

/**
 * Local recording storage.
 *
 * New sessions (cloud-friendly):
 * ```
 * files/sessions/SESSION-yyyyMMdd-HHmmss-xxxx/
 *   ├── data.xlsx
 *   ├── video.mp4
 *   ├── session.log
 *   └── manifest.json   (stub in Step 1; full fields in Step 2)
 * ```
 *
 * Legacy flat layout (`data/`, `logs/`, `videos/`) is still listed for old History.
 */
object RecordingStore {
    const val FILE_DATA = "data.xlsx"
    const val FILE_VIDEO = "video.mp4"
    const val FILE_LOG = "session.log"
    const val FILE_MANIFEST = "manifest.json"

    private const val META_SUFFIX = ".meta.json"

    fun sessionsRoot(context: Context): File =
        File(context.filesDir, "sessions").also { it.mkdirs() }

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").also { it.mkdirs() }

    private fun logsDir(context: Context): File =
        File(context.filesDir, "logs").also { it.mkdirs() }

    private fun videosDir(context: Context): File =
        File(context.filesDir, "videos").also { it.mkdirs() }

    /** New cloud-style session id: SESSION-20260910-174530-a1b2 */
    fun makeSessionId(atMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val short = UUID.randomUUID().toString().replace("-", "").take(4)
        return "SESSION-${fmt.format(Date(atMs))}-$short"
    }

    /** @deprecated Prefer [makeSessionId] for new recordings. Kept for legacy callers. */
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

    fun sessionDir(context: Context, sessionId: String): File =
        File(sessionsRoot(context), sessionId)

    fun createSessionDir(context: Context, sessionId: String): File =
        sessionDir(context, sessionId).also { it.mkdirs() }

    fun sessionDataFile(sessionDir: File): File = File(sessionDir, FILE_DATA)
    fun sessionVideoFile(sessionDir: File): File = File(sessionDir, FILE_VIDEO)
    fun sessionLogFile(sessionDir: File): File = File(sessionDir, FILE_LOG)
    fun sessionManifestFile(sessionDir: File): File = File(sessionDir, FILE_MANIFEST)

    /** Prefer session package path; fall back to legacy flat `data/`. */
    fun dataFile(context: Context, sessionIdOrBase: String): File {
        val session = sessionDir(context, sessionIdOrBase)
        if (session.isDirectory || sessionIdOrBase.startsWith("SESSION-")) {
            return sessionDataFile(createSessionDir(context, sessionIdOrBase))
        }
        return File(dataDir(context), "$sessionIdOrBase.xlsx")
    }

    fun findDataFile(context: Context, baseName: String): File? {
        val inSession = File(sessionDir(context, baseName), FILE_DATA)
        if (inSession.exists()) return inSession
        val xlsx = File(dataDir(context), "$baseName.xlsx")
        if (xlsx.exists()) return xlsx
        val csv = File(dataDir(context), "$baseName.csv")
        if (csv.exists()) return csv
        return null
    }

    fun logFile(context: Context, baseName: String): File {
        val session = sessionDir(context, baseName)
        if (session.isDirectory || baseName.startsWith("SESSION-")) {
            return sessionLogFile(createSessionDir(context, baseName))
        }
        return File(logsDir(context), "$baseName.log")
    }

    fun findVideoFile(context: Context, baseName: String): File? {
        val inSession = File(sessionDir(context, baseName), FILE_VIDEO)
        if (inSession.exists()) return inSession
        val dir = videosDir(context)
        val mp4 = File(dir, "$baseName.mp4")
        if (mp4.exists()) return mp4
        val webm = File(dir, "$baseName.webm")
        if (webm.exists()) return webm
        return null
    }

    /** Newest recorded video across session packages and legacy `videos/`. */
    fun latestVideoFile(context: Context): File? {
        val sessionVideos = sessionsRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, FILE_VIDEO) }
            ?.filter { it.isFile && it.length() > 0L }
            .orEmpty()
        val legacyVideos = videosDir(context).listFiles()
            ?.filter {
                it.isFile &&
                    (it.extension.equals("mp4", true) || it.extension.equals("webm", true)) &&
                    it.length() > 0L
            }
            .orEmpty()
        return (sessionVideos + legacyVideos).maxByOrNull { it.lastModified() }
    }

    private fun legacyMetaFile(context: Context, baseName: String): File =
        File(dataDir(context), "$baseName$META_SUFFIX")

    /**
     * Finalize a session package: write log + full [manifest.json].
     * Caller must already have written [FILE_DATA] / [FILE_VIDEO] into the session dir.
     */
    fun saveRecording(
        context: Context,
        baseName: String,
        logContent: String,
        packetCount: Int,
        sampleCount: Int,
        status: String,
        galleryUri: String? = null,
        manifest: SessionManifestData? = null,
    ): HistoryEntry {
        val savedAt = System.currentTimeMillis()
        val session = sessionDir(context, baseName)
        val isSessionPackage = baseName.startsWith("SESSION-") || session.isDirectory

        if (isSessionPackage) {
            session.mkdirs()
            val dataF = sessionDataFile(session)
            val logF = sessionLogFile(session)
            val vidF = sessionVideoFile(session).takeIf { it.exists() }
            logF.writeText(logContent, Charsets.UTF_8)
            val full = (manifest ?: SessionManifestData(
                sessionId = baseName,
                packetCount = packetCount,
                sampleCount = sampleCount,
                parseFailures = 0,
                statusDetail = status,
                hasPossibleLoss = false,
                localUserId = "",
                userName = "",
                localPetId = "",
                petName = "",
                animalType = "",
                breed = "",
                sex = "",
                age = "",
                weightKg = "",
                deviceId = "",
                deviceAddress = "",
                startTimeMs = savedAt,
                endTimeMs = savedAt,
                videoWidth = null,
                videoHeight = null,
                videoFps = null,
                orientationHint = null,
                videoOrientationLabel = null,
                sensorSamplePeriodMs = null,
                sensorSamplesPerPacket = null,
                phoneStartTimestampMs = savedAt,
                collarUptimeAtSyncMs = null,
                hasData = dataF.exists(),
                hasVideo = vidF != null,
                galleryUri = galleryUri,
                appVersionName = "unknown",
                appVersionCode = 0,
                savedAtMs = savedAt,
            )).copy(
                sessionId = baseName,
                hasData = dataF.exists(),
                hasVideo = vidF != null,
                galleryUri = galleryUri ?: manifest?.galleryUri,
                savedAtMs = savedAt,
            )
            full.writeTo(session)
            TagLogger.log(
                LogCategory.FILE,
                "AUTO_SAVE_OK",
                "session=$baseName data=${dataF.name} video=${vidF?.name ?: "none"} quality=${full.qualityStatus()}",
            )
            return HistoryEntry(
                baseName = baseName,
                dataFile = dataF,
                logFile = logF,
                videoFile = vidF,
                packetCount = packetCount,
                sampleCount = sampleCount,
                status = full.qualityStatus(),
                savedAtMs = savedAt,
                galleryUri = galleryUri,
                sessionDir = session,
            )
        }

        // Legacy flat layout
        val dataF = findDataFile(context, baseName) ?: dataFile(context, baseName)
        val logF = File(logsDir(context), "$baseName.log")
        val vidF = findVideoFile(context, baseName)
        logF.writeText(logContent, Charsets.UTF_8)
        legacyMetaFile(context, baseName).writeText(
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
            sessionDir = null,
        )
    }

    fun updateGalleryUri(context: Context, baseName: String, galleryUri: String?) {
        val session = sessionDir(context, baseName)
        val manifest = sessionManifestFile(session)
        if (manifest.exists()) {
            try {
                val o = JSONObject(manifest.readText(Charsets.UTF_8))
                o.put("gallery_uri", galleryUri ?: JSONObject.NULL)
                o.put("galleryUri", galleryUri ?: JSONObject.NULL)
                manifest.writeText(o.toString(2), Charsets.UTF_8)
            } catch (_: Exception) {
            }
            return
        }
        val meta = legacyMetaFile(context, baseName)
        if (!meta.exists()) return
        try {
            val o = JSONObject(meta.readText(Charsets.UTF_8))
            o.put("galleryUri", galleryUri ?: JSONObject.NULL)
            meta.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun listHistory(context: Context): List<HistoryEntry> {
        val sessions = listSessionEntries(context)
        val legacy = listLegacyEntries(context)
        val sessionIds = sessions.map { it.baseName }.toSet()
        // Avoid duplicates if a legacy file shares a name (unlikely with SESSION- prefix)
        val merged = sessions + legacy.filter { it.baseName !in sessionIds }
        return merged.sortedByDescending { it.savedAtMs }
    }

    private fun listSessionEntries(context: Context): List<HistoryEntry> {
        val root = sessionsRoot(context)
        val dirs = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("SESSION-") }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val dataF = sessionDataFile(dir)
            if (!dataF.exists() && !sessionVideoFile(dir).exists()) return@mapNotNull null
            val logF = sessionLogFile(dir)
            val vidF = sessionVideoFile(dir).takeIf { it.exists() }
            val manifest = sessionManifestFile(dir)
            var galleryUri: String? = null
            val meta = if (manifest.exists()) {
                try {
                    val o = JSONObject(manifest.readText(Charsets.UTF_8))
                    galleryUri = o.optString("gallery_uri")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("galleryUri").takeIf { it.isNotBlank() && it != "null" }
                    val quality = o.optJSONObject("quality")
                    val packets = quality?.optInt("packet_count")
                        ?: o.optInt("packetCount", 0)
                    val samples = quality?.optInt("sample_count")
                        ?: o.optInt("sampleCount", 0)
                    val status = quality?.optString("status")
                        ?.takeIf { it.isNotBlank() }
                        ?: o.optString("status", "Saved")
                    Meta(
                        packets,
                        samples,
                        status,
                        o.optLong("saved_at_ms", o.optLong("savedAtMs", dataF.lastModified().takeIf { dataF.exists() } ?: dir.lastModified())),
                    )
                } catch (_: Exception) {
                    Meta(0, 0, "Saved", dir.lastModified())
                }
            } else {
                Meta(0, 0, "Saved", dir.lastModified())
            }
            HistoryEntry(
                baseName = dir.name,
                dataFile = dataF,
                logFile = logF,
                videoFile = vidF,
                packetCount = meta.packets,
                sampleCount = meta.samples,
                status = meta.status,
                savedAtMs = meta.savedAt,
                galleryUri = galleryUri,
                sessionDir = dir,
            )
        }
    }

    private fun listLegacyEntries(context: Context): List<HistoryEntry> {
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
            // Skip if this path is actually inside sessions (findDataFile prefers session)
            if (dataF.parentFile?.name?.startsWith("SESSION-") == true) return@mapNotNull null
            val logF = File(logsRoot, "$base.log")
            val metaFile = legacyMetaFile(context, base)
            val vidF = run {
                val dir = videosDir(context)
                val mp4 = File(dir, "$base.mp4")
                when {
                    mp4.exists() -> mp4
                    File(dir, "$base.webm").exists() -> File(dir, "$base.webm")
                    else -> null
                }
            }
            var galleryUri: String? = null
            val (packets, samples, status, savedAt) = if (metaFile.exists()) {
                try {
                    val o = JSONObject(metaFile.readText(Charsets.UTF_8))
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
                sessionDir = null,
            )
        }
    }

    private data class Meta(
        val packets: Int,
        val samples: Int,
        val status: String,
        val savedAt: Long,
    )

    fun deleteEntry(context: Context, entry: HistoryEntry) {
        val galleryNames = linkedSetOf(
            entry.videoFile?.name,
            "${entry.baseName}.mp4",
            FILE_VIDEO,
            "${entry.baseName}.webm",
            "${entry.baseName}_burn.mp4",
        ).filterNotNull().toMutableSet()

        GalleryPublisher.deletePublished(
            context = context,
            displayName = null,
            galleryUri = entry.galleryUri,
        )
        galleryNames.forEach { GalleryPublisher.deleteByDisplayName(context, it) }

        val session = entry.sessionDir ?: sessionDir(context, entry.baseName).takeIf { it.isDirectory }
        if (session != null && session.isDirectory) {
            session.listFiles()?.forEach { it.delete() }
            session.delete()
            TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", "session=${entry.baseName}")
            return
        }

        entry.dataFile.delete()
        File(dataDir(context), "${entry.baseName}.xlsx").delete()
        File(dataDir(context), "${entry.baseName}.csv").delete()
        entry.logFile.delete()
        entry.videoFile?.delete()
        File(videosDir(context), "${entry.baseName}.mp4").delete()
        File(videosDir(context), "${entry.baseName}.webm").delete()
        File(videosDir(context), "${entry.baseName}_burn.mp4").delete()
        legacyMetaFile(context, entry.baseName).delete()
        TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", entry.baseName)
    }
}
