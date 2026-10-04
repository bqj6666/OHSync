package io.github.ohsync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import io.github.ohsync.core.HcClient
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.SyncEngine
import io.github.ohsync.core.TokenStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OHSyncScreen(token: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SyncEngine.status.collectAsState()
    val logs by SyncEngine.logs.collectAsState()

    // Health Connect 需要用户显式授权；没授权的话写不进去任何记录
    val hc = remember { HcClient(ctx) }
    val required = remember(hc) { hc.requiredPermissions }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        SyncEngine.notePermission(granted.containsAll(required))
    }
    LaunchedEffect(Unit) {
        SyncEngine.notePermission(hc.isWriteGranted())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("OHSync") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("状态", style = MaterialTheme.typography.titleMedium)
                        Text("Hook 连接：${if (status.hookConnected) "已连接" else "未连接"}")
                        Text("HC 写权限：${if (status.hcPermissionGranted) "已授权" else "未授权"}")
                        Text(
                            "上次同步：" +
                                (status.lastSyncAtMillis?.let { TIME_FMT.format(Instant.ofEpochMilli(it)) }
                                    ?: "—")
                        )
                        Text("已写入 ${status.syncedCount} / 跳过 ${status.skippedCount} / 失败 ${status.failedCount}")
                        if (status.pendingBackfill) {
                            Text("历史回填：等待中", color = MaterialTheme.colorScheme.primary)
                        }
                        status.lastError?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("操作", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { permissionLauncher.launch(required) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("授予 Health Connect 权限")
                        }
                        Button(onClick = { SyncEngine.requestBackfill() }, modifier = Modifier.fillMaxWidth()) {
                            Text("同步历史数据")
                        }
                        OutlinedButton(onClick = { copyToken(ctx, token) }, modifier = Modifier.fillMaxWidth()) {
                            Text("复制配对口令")
                        }
                        OutlinedButton(onClick = { SyncEngine.clearAll() }, modifier = Modifier.fillMaxWidth()) {
                            Text("清除本应用写入的数据")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("配对口令（粘贴到 LSPosed 日志中的 OHSync Hook 设置）", style = MaterialTheme.typography.titleMedium)
                        Text(token, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("已废弃类型（Health Connect 无对应记录类型）", style = MaterialTheme.typography.titleMedium)
            }
            items(RecordType.discarded) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(t.label, Modifier.weight(1f))
                    Text("不写入", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (logs.isNotEmpty()) {
                item {
                    Text("日志", style = MaterialTheme.typography.titleMedium)
                }
                items(logs.reversed()) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun copyToken(ctx: Context, token: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OHSync token", token))
}
