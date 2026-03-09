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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OuraArenaScreen(onRequestPermissions: (onStatusUpdate: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("oura_arena", Context.MODE_PRIVATE)

    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "https://oura-arena.vercel.app") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var syncEnabled by remember { mutableStateOf(prefs.getBoolean("sync_enabled", false)) }
    var status by remember { mutableStateOf("待機中") }
    var lastSync by remember { mutableStateOf(prefs.getString("last_sync", "未同期") ?: "未同期") }
    var isSyncing by remember { mutableStateOf(false) }

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
                                status = "✅ 全期間同期完了！（${daysCount}日分）"
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

            // 手動同期ボタン（今日のみ）
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
                Text("今すぐ同期")
            }

            // 自動同期トグル
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("自動同期", style = MaterialTheme.typography.titleSmall)
                        Text("15分ごとにバックグラウンドで送信",
                            style = MaterialTheme.typography.bodySmall)
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
