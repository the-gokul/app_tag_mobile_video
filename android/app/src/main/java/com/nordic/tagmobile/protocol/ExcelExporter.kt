package com.nordic.tagmobile.protocol

import com.nordic.tagmobile.model.UserProfile
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object ExcelExporter {

    private val HEADERS = arrayOf(
        "date", "time", "device_id", "packet_no", "sample_no", "flags", "timestamp_ms",
        "r_accel_x", "r_accel_y", "r_accel_z", "r_gyro_x", "r_gyro_y", "r_gyro_z",
        "r_humidity_x100", "r_env_temp_x100", "r_body_temp_x100",
        "accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z",
        "humidity_x100", "env_temp_x100", "body_temp_x100"
    )

    fun exportToExcel(
        file: File,
        rows: List<SensorCsvRow>,
        profile: UserProfile,
        packetCount: Int,
        sampleCount: Int,
        status: String
    ) {
        val workbook = XSSFWorkbook()

        // 1. Data Sheet
        val dataSheet = workbook.createSheet("Data")
        val headerRow = dataSheet.createRow(0)
        HEADERS.forEachIndexed { i, title -> headerRow.createCell(i).setCellValue(title) }

        rows.forEachIndexed { index, row ->
            val excelRow = dataSheet.createRow(index + 1)
            val f = row.flags
            val bmi = SensorPacketParser.statusStr(f, SensorPacketParser.BMI_SHIFT)
            val bme = SensorPacketParser.statusStr(f, SensorPacketParser.BME_SHIFT)
            val tmp = SensorPacketParser.statusStr(f, SensorPacketParser.TMP_SHIFT)
            val bmiOk = SensorPacketParser.bmiOk(f)
            val bmeOk = SensorPacketParser.bmeOk(f)
            val tmpOk = SensorPacketParser.tmpOk(f)

            fun rawI(ok: Boolean, v: Int, st: String) = if (ok) v.toString() else st
            fun si3(ok: Boolean, v: Int, st: String) = if (ok) String.format(Locale.US, "%.2f", v / 1000.0) else st
            fun siC(ok: Boolean, v: Int, st: String) = if (ok) String.format(Locale.US, "%.2f", v / 100.0) else st

            val values = arrayOf(
                row.date, row.time, row.deviceId, row.packetNo.toString(), row.sampleNumber.toString(),
                "0x%02X".format(row.flags), row.timestampMs.toString(),
                rawI(bmiOk, row.accelX, bmi), rawI(bmiOk, row.accelY, bmi), rawI(bmiOk, row.accelZ, bmi),
                rawI(bmiOk, row.gyroX, bmi), rawI(bmiOk, row.gyroY, bmi), rawI(bmiOk, row.gyroZ, bmi),
                rawI(bmeOk, row.humidityX100, bme), rawI(bmeOk, row.envTempX100, bme), rawI(tmpOk, row.bodyTempX100, tmp),
                si3(bmiOk, row.accelX, bmi), si3(bmiOk, row.accelY, bmi), si3(bmiOk, row.accelZ, bmi),
                si3(bmiOk, row.gyroX, bmi), si3(bmiOk, row.gyroY, bmi), si3(bmiOk, row.gyroZ, bmi),
                siC(bmeOk, row.humidityX100, bme), siC(bmeOk, row.envTempX100, bme), siC(tmpOk, row.bodyTempX100, tmp)
            )
            
            values.forEachIndexed { i, v -> excelRow.createCell(i).setCellValue(v) }
        }

        // 2. Summary Sheet
        val summarySheet = workbook.createSheet("Summary")
        val summaryData = listOf(
            "Property" to "Value",
            "User Name" to profile.name,
            "Dog Name" to profile.dogName,
            "Breed" to profile.breed,
            "Age" to profile.age,
            "Weight (kg)" to profile.weight,
            "Gender" to profile.gender,
            "" to "",
            "Session Packets" to packetCount.toString(),
            "Session Samples" to sampleCount.toString(),
            "Session Status" to status
        )

        summaryData.forEachIndexed { i, pair ->
            val r = summarySheet.createRow(i)
            r.createCell(0).setCellValue(pair.first)
            r.createCell(1).setCellValue(pair.second)
        }

        FileOutputStream(file).use { out ->
            workbook.write(out)
        }
        workbook.close()
    }
}
