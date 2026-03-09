package run.kandot.ourarena

import android.content.Context
import android.util.Log
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
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("oura_arena", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)
        val apiKey = prefs.getString("api_key", null)

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
                Log.d(TAG, "データ送信成功: ${response.body?.string()}")
                Result.success()
            } else {
                Log.w(TAG, "データ送信失敗: ${response.code} ${response.body?.string()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "同期エラー", e)
            Result.retry()
        }
    }
}
