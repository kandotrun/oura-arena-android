package run.kandot.ourarena

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OuraArenaSyncWorker"
        private const val WORK_NAME = "oura_arena_sync"
        private const val CHANNEL_ID = "oura_arena_sync"
        private const val NOTIFICATION_ID = 1001

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "バックグラウンド同期をスケジュールしました（15分間隔）")
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "バックグラウンド同期をキャンセルしました")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "同期通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Oura Arenaのデータ同期通知"
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("oura_arena", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)
        val apiKey = prefs.getString("api_key", null)
        val notifyEnabled = prefs.getBoolean("notify_sync", true)

        if (serverUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            Log.w(TAG, "サーバーURLまたはAPIキーが未設定です")
            return Result.failure()
        }

        return try {
            val reader = HealthReader(applicationContext)
            val data = reader.readTodayData()

            val client = OkHttpClient()
            val body = data.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/submit")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                prefs.edit().putString("last_sync", now).apply()
                Log.d(TAG, "データ送信成功")

                // 同期内容のサマリーを作成
                val sleepScore = data.optJSONObject("sleep")?.optInt("score", 0) ?: 0
                val steps = data.optJSONObject("activity")?.optLong("steps", 0) ?: 0
                val summary = buildString {
                    if (sleepScore > 0) append("😴 睡眠 ${sleepScore}点  ")
                    if (steps > 0) append("🚶 ${steps}歩")
                    if (isEmpty()) append("データ送信完了")
                }

                if (notifyEnabled) {
                    showNotification("同期完了 ($now)", summary)
                }
                Result.success()
            } else {
                Log.w(TAG, "データ送信失敗: ${response.code}")
                if (notifyEnabled) {
                    showNotification("同期失敗", "サーバーエラー: ${response.code}")
                }
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "同期エラー", e)
            if (prefs.getBoolean("notify_sync", true)) {
                val msg = e.message ?: e.javaClass.simpleName
                showNotification("同期エラー", msg)
            }
            Result.retry()
        }
    }

    private fun showNotification(title: String, text: String) {
        createNotificationChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
