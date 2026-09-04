package com.nordic.tagmobile.analysis

import com.nordic.tagmobile.protocol.SensorCsvRow

data class SessionReport(
    val packetCount: Int,
    val sampleCount: Int,
    val parseFailures: Int,
    val packetIds: List<Long>,
    val statusShort: String,
    val statusDetail: String,
    val hasPossibleLoss: Boolean,
) {
    val feedbackText: String
        get() = buildString {
            appendLine("Recording complete")
            appendLine("Packets: $packetCount · Samples: $sampleCount")
            if (parseFailures > 0) {
                appendLine("Parse failures: $parseFailures")
            }
            appendLine("Status: $statusShort")
            if (statusDetail.isNotBlank()) {
                appendLine(statusDetail)
            }
            append("Saved to History (CSV + log)")
        }
}

object SessionAnalyzer {
    fun analyze(
        packetCount: Int,
        rows: List<SensorCsvRow>,
        packetIds: List<Long>,
        parseFailures: Int,
    ): SessionReport {
        if (packetCount == 0 && rows.isEmpty()) {
            return SessionReport(
                packetCount = 0,
                sampleCount = 0,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "No data received",
                statusDetail = "Check connection / Start",
                hasPossibleLoss = false,
            )
        }

        val sampleGaps = findSampleGaps(rows)
        val packetGaps = findPacketIdGaps(packetIds)

        return when {
            sampleGaps.isNotEmpty() || packetGaps.isNotEmpty() -> {
                val detail = (packetGaps + sampleGaps).take(3).joinToString("; ")
                SessionReport(
                    packetCount = packetCount,
                    sampleCount = rows.size,
                    parseFailures = parseFailures,
                    packetIds = packetIds,
                    statusShort = "Possible packet loss",
                    statusDetail = detail,
                    hasPossibleLoss = true,
                )
            }
            else -> SessionReport(
                packetCount = packetCount,
                sampleCount = rows.size,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "No packet loss detected",
                statusDetail = "",
                hasPossibleLoss = false,
            )
        }
    }

    private fun findSampleGaps(rows: List<SensorCsvRow>): List<String> {
        if (rows.size < 2) return emptyList()
        val gaps = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val prev = rows[i - 1].sampleNumber
            val cur = rows[i].sampleNumber
            if (cur > prev + 1) {
                gaps.add("sample gap: expected ${prev + 1}, got $cur")
            }
        }
        return gaps
    }

    private fun findPacketIdGaps(ids: List<Long>): List<String> {
        if (ids.size < 2) return emptyList()
        val gaps = mutableListOf<String>()
        for (i in 1 until ids.size) {
            val prev = ids[i - 1]
            val cur = ids[i]
            if (cur > prev + 1) {
                gaps.add("packet_id gap: expected ${prev + 1}, got $cur (~${cur - prev - 1} missing)")
            }
        }
        return gaps
    }
}
