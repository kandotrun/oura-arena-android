package run.kandot.ourarena

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
    )

    private var permissionStatus: ((String) -> Unit)? = null

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val total = permissions.size
        val count = granted.size
        if (count == total) {
            permissionStatus?.invoke("✅ 全${total}件の権限が許可されました")
        } else if (count > 0) {
            permissionStatus?.invoke("⚠️ ${count}/${total}件の権限が許可されました（一部不足）")
        } else {
            permissionStatus?.invoke("❌ 権限が許可されませんでした")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知チャンネル作成
        SyncWorker.createNotificationChannel(this)

        setContent {
            MaterialTheme {
                OuraArenaScreen(
                    onRequestPermissions = { onStatusUpdate ->
                        permissionStatus = onStatusUpdate
                        requestPermissions.launch(permissions)
                    }
                )
            }
        }
    }
}

data class DataPreview(
    val sleepScore: Int = 0,
    val sleepHours: String = "",
    val steps: Long = 0,
    val activeCal: Int = 0,
    val heartRate: Int = 0,
    val spo2: Double = 0.0,
    val distance: String = "",
    val weight: String = "",
    val workouts: Int = 0,
    val hasData: Boolean = false,
)

fun extractPreview(json: JSONObject): DataPreview {
    val sleep = json.optJSONObject("sleep")
    val activity = json.optJSONObject("activity")
    val hr = json.optJSONObject("heart_rate")

    val sleepSec = sleep?.optLong("total_duration_seconds", 0) ?: 0
    val sleepH = sleepSec / 3600
    val sleepM = (sleepSec % 3600) / 60

    return DataPreview(
        sleepScore = sleep?.optInt("score", 0) ?: 0,
        sleepHours = if (sleepSec > 0) "${sleepH}時間${sleepM}分" else "",
        steps = activity?.optLong("steps", 0) ?: 0,
        activeCal = activity?.optInt("active_calories", 0) ?: 0,
        heartRate = hr?.optInt("average", 0) ?: 0,
        spo2 = json.optDouble("spo2", 0.0),
        hasData = sleep != null || activity != null || hr != null,
    )
}

fun extractBulkPreview(json: JSONObject): String {
    val daysCount = json.optInt("days_count", 0)
    val syncFrom = json.optString("sync_from", "")
    val daily = json.optJSONArray("daily")
    val workouts = json.optJSONArray("workouts")

    val parts = mutableListOf<String>()
    parts.add("📅 ${daysCount}日分")
    if (syncFrom.isNotEmpty()) parts.add("($syncFrom〜)")

    // 日別データのサマリー
    if (daily != null && daily.length() > 0) {
        var sleepDays = 0; var stepDays = 0; var hrDays = 0
        for (i in 0 until daily.length()) {
            val day = daily.getJSONObject(i)
            if (day.has("sleep")) sleepDays++
            if (day.has("activity")) stepDays++
            if (day.has("heart_rate")) hrDays++
        }
        if (sleepDays > 0) parts.add("😴 睡眠${sleepDays}日")
        if (stepDays > 0) parts.add("🚶 活動${stepDays}日")
        if (hrDays > 0) parts.add("💓 心拍${hrDays}日")
    }
    if (workouts != null && workouts.length() > 0) {
        parts.add("🏋️ ワークアウト${workouts.length()}件")
    }

    return parts.joinToString("  ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OuraArenaScreen(onRequestPermissions: (onStatusUpdate: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("oura_arena", Context.MODE_PRIVATE)

    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "https://oura-arena.vercel.app") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var syncEnabled by remember { mutableStateOf(prefs.getBoolean("sync_enabled", false)) }
    var notifyEnabled by remember { mutableStateOf(prefs.getBoolean("notify_sync", true)) }
    var status by remember { mutableStateOf("待機中") }
    var lastSync by remember { mutableStateOf(prefs.getString("last_sync", "未同期") ?: "未同期") }
    var isSyncing by remember { mutableStateOf(false) }
    var isLoadingPreview by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<DataPreview?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Oura Arena Sync", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ステータスカード
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ステータス", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("最終同期: $lastSync", style = MaterialTheme.typography.bodySmall)
                }
            }

            // データプレビュー
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📊 今日のデータ", style = MaterialTheme.typography.titleSmall)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isLoadingPreview = true
                                    try {
                                        val reader = HealthReader(context)
                                        val data = reader.readTodayData()
                                        preview = extractPreview(data)
                                    } catch (e: Exception) {
                                        preview = DataPreview(hasData = false)
                                        status = "❌ プレビュー取得失敗: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                    isLoadingPreview = false
                                }
                            },
                            enabled = !isLoadingPreview
                        ) {
                            if (isLoadingPreview) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("更新")
                        }
                    }

                    if (preview == null) {
                        Text(
                            "「更新」を押してデータを確認",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (!preview!!.hasData) {
                        Text(
                            "データがありません（Health Connectの権限を確認してください）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        val p = preview!!
                        Spacer(modifier = Modifier.height(8.dp))

                        // メトリックグリッド
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (p.sleepScore > 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MetricChip("😴 睡眠", "${p.sleepScore}点")
                                    if (p.sleepHours.isNotEmpty()) MetricChip("🛏️ 時間", p.sleepHours)
                                }
                            }
                            if (p.steps > 0 || p.activeCal > 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (p.steps > 0) MetricChip("🚶 歩数", "${p.steps}歩")
                                    if (p.activeCal > 0) MetricChip("🔥 カロリー", "${p.activeCal}kcal")
                                }
                            }
                            if (p.heartRate > 0 || p.spo2 > 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (p.heartRate > 0) MetricChip("💓 心拍", "${p.heartRate}bpm")
                                    if (p.spo2 > 0) MetricChip("🫁 SpO2", "${String.format("%.1f", p.spo2)}%")
                                }
                            }
                        }
                    }
                }
            }

            // サーバー設定
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("サーバー設定", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            prefs.edit().putString("server_url", it).apply()
                        },
                        label = { Text("サーバーURL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            prefs.edit().putString("api_key", it).apply()
                        },
                        label = { Text("APIキー") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Health Connect パーミッション
            Button(
                onClick = { onRequestPermissions { msg -> status = msg } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Health Connect の権限を許可する")
            }

            // 今すぐ同期ボタン
            Button(
                onClick = {
                    scope.launch {
                        if (serverUrl.isBlank() || apiKey.isBlank()) {
                            status = "⚠️ サーバーURLとAPIキーを設定してください"
                            return@launch
                        }
                        isSyncing = true
                        status = "同期中..."
                        try {
                            val reader = HealthReader(context)
                            val data = reader.readTodayData()
                            // プレビュー更新
                            preview = extractPreview(data)

                            val client = OkHttpClient()
                            val body = data.toString()
                                .toRequestBody("application/json".toMediaType())
                            val request = Request.Builder()
                                .url("$serverUrl/api/submit")
                                .header("Authorization", "Bearer $apiKey")
                                .post(body)
                                .build()

                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val now = java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                                lastSync = now
                                prefs.edit().putString("last_sync", now).apply()
                                status = "✅ 送信成功！"
                            } else {
                                status = "❌ 送信失敗: ${response.code}"
                            }
                        } catch (e: Exception) {
                            val msg = e.message ?: e.javaClass.simpleName
                            status = when {
                                e is java.lang.SecurityException || msg.contains("permission", true) ->
                                    "❌ Health Connectの権限がありません。上のボタンから許可してください"
                                e is IllegalStateException && msg.contains("not available", true) ->
                                    "❌ Health Connectがインストールされていません"
                                e is java.net.UnknownHostException ->
                                    "❌ ネットワークエラー: インターネットに接続してください"
                                e is java.net.SocketTimeoutException ->
                                    "❌ タイムアウト: サーバーに接続できません"
                                else ->
                                    "❌ エラー: $msg (${e.javaClass.simpleName})"
                            }
                        }
                        isSyncing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("今すぐ同期（今日のみ）")
            }

            // 全期間同期ボタン
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (serverUrl.isBlank() || apiKey.isBlank()) {
                            status = "⚠️ サーバーURLとAPIキーを設定してください"
                            return@launch
                        }
                        isSyncing = true
                        val lastSync2 = prefs.getString("last_sync_day", null)
                        status = if (lastSync2 != null) "差分同期中（${lastSync2}以降）..." else "全期間同期中..."
                        try {
                            val reader = HealthReader(context)
                            val data = reader.readAllData(lastSync2)
                            val daysCount = data.optInt("days_count", 0)
                            val bulkSummary = extractBulkPreview(data)
                            status = "送信中... $bulkSummary"

                            val client = OkHttpClient.Builder()
                                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val body = data.toString()
                                .toRequestBody("application/json".toMediaType())
                            val request = Request.Builder()
                                .url("$serverUrl/api/submit")
                                .header("Authorization", "Bearer $apiKey")
                                .post(body)
                                .build()

                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val nowStr = java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                                val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                lastSync = nowStr
                                prefs.edit()
                                    .putString("last_sync", nowStr)
                                    .putString("last_sync_day", todayStr)
                                    .apply()
                                status = "✅ 全期間同期完了！  $bulkSummary"
                            } else {
                                status = "❌ 送信失敗: ${response.code}"
                            }
                        } catch (e: Exception) {
                            val msg = e.message ?: e.javaClass.simpleName
                            status = "❌ エラー: $msg"
                        }
                        isSyncing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            ) {
                Text("全期間データを同期")
            }

            // 設定カード
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 自動同期トグル
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("自動同期", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "15分ごとにバックグラウンドで送信",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = syncEnabled,
                            onCheckedChange = {
                                syncEnabled = it
                                prefs.edit().putBoolean("sync_enabled", it).apply()
                                if (it) {
                                    SyncWorker.schedule(context)
                                    Toast.makeText(context, "自動同期ON", Toast.LENGTH_SHORT).show()
                                } else {
                                    SyncWorker.cancelSync(context)
                                    Toast.makeText(context, "自動同期OFF", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // 通知トグル
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("同期通知", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "同期完了時に通知を表示",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = notifyEnabled,
                            onCheckedChange = {
                                notifyEnabled = it
                                prefs.edit().putBoolean("notify_sync", it).apply()
                            }
                        )
                    }
                }
            }

            // 説明
            Text(
                "このアプリはXiaomi Smart Band等のデータをHealth Connect経由で読み取り、Oura Arenaダッシュボードに送信します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MetricChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
