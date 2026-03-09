package run.kandot.ourarena

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class HealthReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone = ZoneId.of("Asia/Tokyo")
    private val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * 全期間のデータを取得して送信用JSONを返す
     * lastSyncDay: 前回同期した最後の日付（nullなら全期間）
     */
    suspend fun readAllData(lastSyncDay: String? = null): JSONObject {
        val now = Instant.now()
        val today = LocalDate.now(zone)

        // 取得開始: 前回同期日の翌日 or 全期間（最大2年前）
        val startDate = if (lastSyncDay != null) {
            LocalDate.parse(lastSyncDay).plusDays(1)
        } else {
            today.minusYears(2)
        }
        val startInstant = startDate.atStartOfDay(zone).toInstant()

        val json = JSONObject()
        json.put("timestamp", now.toString())
        json.put("day", today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        json.put("device", "xiaomi")
        json.put("sync_from", startDate.format(DateTimeFormatter.ISO_LOCAL_DATE))

        // 日別データを格納
        val dailyData = JSONArray()

        // === 睡眠データ（全期間） ===
        try {
            val sleepResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            for (session in sleepResponse.records) {
                val sessionDay = session.endTime.atZone(zone).toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                val totalSleep = Duration.between(session.startTime, session.endTime).seconds
                var deepSec = 0L; var remSec = 0L; var lightSec = 0L; var awakeSec = 0L

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
                val durationScore = minOf((actualSleep / 3600.0 / 8.0 * 100).toInt(), 100)
                val sleepScore = (durationScore * 0.5 + efficiency * 0.5).toInt()

                val sleepJson = JSONObject()
                sleepJson.put("day", sessionDay)
                sleepJson.put("score", sleepScore)
                sleepJson.put("bedtime_start", session.startTime.atZone(zone).format(isoFmt))
                sleepJson.put("bedtime_end", session.endTime.atZone(zone).format(isoFmt))
                sleepJson.put("total_duration_seconds", totalSleep)
                sleepJson.put("deep_seconds", deepSec)
                sleepJson.put("rem_seconds", remSec)
                sleepJson.put("light_seconds", lightSec)
                sleepJson.put("awake_seconds", awakeSec)
                sleepJson.put("efficiency", efficiency)

                val dayObj = getOrCreateDay(dailyData, sessionDay)
                dayObj.put("sleep", sleepJson)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 歩数（日別集計） ===
        try {
            val stepsResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val stepsByDay = mutableMapOf<String, Long>()
            for (rec in stepsResponse.records) {
                val day = rec.startTime.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                stepsByDay[day] = (stepsByDay[day] ?: 0L) + rec.count
            }
            for ((day, steps) in stepsByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                val act = if (dayObj.has("activity")) dayObj.getJSONObject("activity") else JSONObject()
                act.put("steps", steps)
                act.put("score", minOf((steps / 10000.0 * 100).toInt(), 100))
                dayObj.put("activity", act)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === アクティブカロリー（日別集計） ===
        try {
            val calResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val calsByDay = mutableMapOf<String, Int>()
            for (rec in calResponse.records) {
                val day = rec.startTime.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                calsByDay[day] = (calsByDay[day] ?: 0) + rec.energy.inKilocalories.toInt()
            }
            for ((day, cals) in calsByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                val act = if (dayObj.has("activity")) dayObj.getJSONObject("activity") else JSONObject()
                act.put("active_calories", cals)
                dayObj.put("activity", act)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 合計カロリー（日別集計） ===
        try {
            val totalCalResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val totalCalsByDay = mutableMapOf<String, Int>()
            for (rec in totalCalResponse.records) {
                val day = rec.startTime.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                totalCalsByDay[day] = (totalCalsByDay[day] ?: 0) + rec.energy.inKilocalories.toInt()
            }
            for ((day, cals) in totalCalsByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                val act = if (dayObj.has("activity")) dayObj.getJSONObject("activity") else JSONObject()
                act.put("total_calories", cals)
                dayObj.put("activity", act)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 心拍数（日別集計） ===
        try {
            val hrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val hrByDay = mutableMapOf<String, MutableList<Long>>()
            for (rec in hrResponse.records) {
                for (sample in rec.samples) {
                    val day = sample.time.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    hrByDay.getOrPut(day) { mutableListOf() }.add(sample.beatsPerMinute)
                }
            }
            for ((day, bpms) in hrByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                val hrJson = JSONObject()
                hrJson.put("average", bpms.average().toInt())
                hrJson.put("resting", bpms.min().toInt())
                hrJson.put("max", bpms.max().toInt())
                hrJson.put("samples", bpms.size)
                dayObj.put("heart_rate", hrJson)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 安静時心拍（日別） ===
        try {
            val rhrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            for (rec in rhrResponse.records) {
                val day = rec.time.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val dayObj = getOrCreateDay(dailyData, day)
                val hrJson = if (dayObj.has("heart_rate")) dayObj.getJSONObject("heart_rate") else JSONObject()
                hrJson.put("resting", rec.beatsPerMinute)
                dayObj.put("heart_rate", hrJson)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === SpO2（日別） ===
        try {
            val spo2Response = client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val spo2ByDay = mutableMapOf<String, MutableList<Double>>()
            for (rec in spo2Response.records) {
                val day = rec.time.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                spo2ByDay.getOrPut(day) { mutableListOf() }.add(rec.percentage.value)
            }
            for ((day, values) in spo2ByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                dayObj.put("spo2", values.average())
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 距離（日別集計） ===
        try {
            val distResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val distByDay = mutableMapOf<String, Double>()
            for (rec in distResponse.records) {
                val day = rec.startTime.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                distByDay[day] = (distByDay[day] ?: 0.0) + rec.distance.inMeters
            }
            for ((day, meters) in distByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                val act = if (dayObj.has("activity")) dayObj.getJSONObject("activity") else JSONObject()
                act.put("distance_meters", meters)
                dayObj.put("activity", act)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 体重（日別） ===
        try {
            val weightResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            for (rec in weightResponse.records) {
                val day = rec.time.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val dayObj = getOrCreateDay(dailyData, day)
                dayObj.put("weight_kg", rec.weight.inKilograms)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === 身長（最新のみ） ===
        try {
            val heightResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            if (heightResponse.records.isNotEmpty()) {
                json.put("height_cm", heightResponse.records.last().height.inMeters * 100)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // === エクササイズセッション ===
        try {
            val exerciseResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val workouts = JSONArray()
            for (rec in exerciseResponse.records) {
                val w = JSONObject()
                w.put("day", rec.startTime.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
                w.put("start", rec.startTime.atZone(zone).format(isoFmt))
                w.put("end", rec.endTime.atZone(zone).format(isoFmt))
                w.put("duration_seconds", Duration.between(rec.startTime, rec.endTime).seconds)
                w.put("type", rec.exerciseType)
                w.put("title", rec.title ?: "")
                workouts.put(w)
            }
            if (workouts.length() > 0) json.put("workouts", workouts)
        } catch (e: Exception) { e.printStackTrace() }

        // === 呼吸数（日別） ===
        try {
            val rrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = RespiratoryRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startInstant, now)
                )
            )
            val rrByDay = mutableMapOf<String, MutableList<Double>>()
            for (rec in rrResponse.records) {
                val day = rec.time.atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                rrByDay.getOrPut(day) { mutableListOf() }.add(rec.rate)
            }
            for ((day, rates) in rrByDay) {
                val dayObj = getOrCreateDay(dailyData, day)
                dayObj.put("respiratory_rate", rates.average())
            }
        } catch (e: Exception) { e.printStackTrace() }

        json.put("daily", dailyData)
        json.put("days_count", dailyData.length())

        return json
    }

    /**
     * 今日のデータのみ取得（後方互換）
     */
    suspend fun readTodayData(): JSONObject {
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant()
        val startOfYesterday = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant()

        val json = JSONObject()
        json.put("timestamp", now.toString())
        json.put("day", today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        json.put("device", "xiaomi")

        // 睡眠
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
                var deepSec = 0L; var remSec = 0L; var lightSec = 0L; var awakeSec = 0L
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
                val durationScore = minOf((actualSleep / 3600.0 / 8.0 * 100).toInt(), 100)
                val sleepScore = (durationScore * 0.5 + efficiency * 0.5).toInt()

                val sleepJson = JSONObject()
                sleepJson.put("score", sleepScore)
                sleepJson.put("bedtime_start", session.startTime.atZone(zone).format(isoFmt))
                sleepJson.put("bedtime_end", session.endTime.atZone(zone).format(isoFmt))
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
        } catch (e: Exception) { e.printStackTrace() }

        // 歩数+カロリー
        try {
            val stepsResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )
            val totalSteps = stepsResponse.records.sumOf { it.count }
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
            val stepsScore = minOf((totalSteps / 10000.0 * 100).toInt(), 100)

            val activityJson = JSONObject()
            activityJson.put("steps", totalSteps)
            activityJson.put("active_calories", activeCal)
            activityJson.put("total_calories", totalCal)
            activityJson.put("score", stepsScore)
            json.put("activity", activityJson)
        } catch (e: Exception) { e.printStackTrace() }

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
                val hrJson = JSONObject()
                hrJson.put("average", allSamples.map { it.beatsPerMinute }.average().toInt())
                hrJson.put("resting", allSamples.minOf { it.beatsPerMinute }.toInt())
                json.put("heart_rate", hrJson)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // SpO2
        try {
            val spo2Response = client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfYesterday, now)
                )
            )
            if (spo2Response.records.isNotEmpty()) {
                json.put("spo2", spo2Response.records.map { it.percentage.value }.average())
            }
        } catch (e: Exception) { e.printStackTrace() }

        return json
    }

    private fun getOrCreateDay(array: JSONArray, day: String): JSONObject {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("day") == day) return obj
        }
        val newObj = JSONObject()
        newObj.put("day", day)
        array.put(newObj)
        return newObj
    }
}
