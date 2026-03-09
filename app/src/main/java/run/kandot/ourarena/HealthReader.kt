package run.kandot.ourarena

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class HealthReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun readTodayData(): JSONObject {
        val now = Instant.now()
        val zone = ZoneId.of("Asia/Tokyo")
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant()
        val startOfYesterday = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant()

        val json = JSONObject()
        json.put("timestamp", now.toString())
        json.put("day", today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        json.put("device", "xiaomi")

        // 睡眠データ
        try {
            val sleepResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfYesterday, now)
                )
            )
            if (sleepResponse.records.isNotEmpty()) {
                val session = sleepResponse.records.last()
                val totalSleep = Duration.between(session.startTime, session.endTime).seconds
                var deepSec = 0L
                var remSec = 0L
                var lightSec = 0L
                var awakeSec = 0L

                for (stage in session.stages) {
                    val dur = Duration.between(stage.startTime, stage.endTime).seconds
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deepSec += dur
                        SleepSessionRecord.STAGE_TYPE_REM -> remSec += dur
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> lightSec += dur
                        SleepSessionRecord.STAGE_TYPE_AWAKE -> awakeSec += dur
                    }
                }

                val actualSleep = totalSleep - awakeSec
                val efficiency = if (totalSleep > 0) ((actualSleep.toDouble() / totalSleep) * 100).toInt() else 0

                // 簡易睡眠スコア算出
                val durationScore = minOf((actualSleep / 3600.0 / 8.0 * 100).toInt(), 100)
                val efficiencyScore = efficiency
                val sleepScore = (durationScore * 0.5 + efficiencyScore * 0.5).toInt()

                val sleepJson = JSONObject()
                sleepJson.put("score", sleepScore)
                sleepJson.put("bedtime_start", session.startTime.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                sleepJson.put("bedtime_end", session.endTime.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                sleepJson.put("total_duration_seconds", totalSleep)
                sleepJson.put("deep_seconds", deepSec)
                sleepJson.put("rem_seconds", remSec)
                sleepJson.put("light_seconds", lightSec)
                sleepJson.put("awake_seconds", awakeSec)
                sleepJson.put("efficiency", efficiency)
                sleepJson.put("average_heart_rate", JSONObject.NULL)
                sleepJson.put("lowest_heart_rate", JSONObject.NULL)
                sleepJson.put("average_hrv", JSONObject.NULL)
                sleepJson.put("average_breath", JSONObject.NULL)
                json.put("sleep", sleepJson)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 歩数
        try {
            val stepsResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val totalSteps = stepsResponse.records.sumOf { it.count }

            // カロリー
            val calResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val activeCal = calResponse.records.sumOf { it.energy.inKilocalories }.toInt()

            val totalCalResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val totalCal = totalCalResponse.records.sumOf { it.energy.inKilocalories }.toInt()

            // 簡易活動スコア
            val stepsScore = minOf((totalSteps / 10000.0 * 100).toInt(), 100)

            val activityJson = JSONObject()
            activityJson.put("steps", totalSteps)
            activityJson.put("active_calories", activeCal)
            activityJson.put("total_calories", totalCal)
            activityJson.put("score", stepsScore)
            json.put("activity", activityJson)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 心拍
        try {
            val hrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val allSamples = hrResponse.records.flatMap { it.samples }
            if (allSamples.isNotEmpty()) {
                val avg = allSamples.map { it.beatsPerMinute }.average().toInt()
                val min = allSamples.minOf { it.beatsPerMinute }.toInt()
                val hrJson = JSONObject()
                hrJson.put("average", avg)
                hrJson.put("resting", min)
                json.put("heart_rate", hrJson)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // SpO2
        try {
            val spo2Response = client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfYesterday, now)
                )
            )
            if (spo2Response.records.isNotEmpty()) {
                val avg = spo2Response.records.map { it.percentage.value }.average()
                json.put("spo2", avg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return json
    }
}
