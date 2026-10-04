package io.github.ohsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ohsync.core.HcClient
import io.github.ohsync.core.RecordType
import io.github.ohsync.core.Settings
import io.github.ohsync.core.SyncEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OHSyncScreen() {
    val ctx = LocalContext.current
    val status by SyncEngine.status.collectAsState()
    val logs by SyncEngine.logs.collectAsState()

    val hc = remember { HcClient(ctx) }
    val required = remember(hc) { hc.requiredPermissions }
    /**
     * 权限请求用**标准**的多权限契约，不用 Health Connect 客户端库的
     * PermissionController.createRequestPermissionResultContract()。
     *
     * 原因（反编译 connect-client 1.1.0 确认）：
     *   客户端库有两套契约 ——
     *     permission.HealthPermissionsRequestAppContract      -> 自定义 action，给 HC 独立 APK 用
     *     permission.platform.HealthPermissionsRequestModuleContract -> 直接复用
     *                                              ActivityResultContracts.RequestMultiplePermissions
     *   本机的 Health Connect 是系统模块，走的是后者，也就是标准权限请求。
     *   用前者的话，Intent 会被 ColorOS 的权限界面接走后立刻返回、什么都不做（实测）。
     */
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        SyncEngine.notePermission(result.isNotEmpty() && result.values.all { it })
    }

    /**
     * 弹出系统授权对话框，逐条勾选要授予的健康数据类型。
     *
     * 注意这个按钮是必需的：ColorOS 的应用详情页只在「至少已授权一项」时才显示
     * 健康权限那一行 —— 用户一旦把权限全部关掉，设置里就再也没有入口了。
     * 所以应用必须自己能重新发起授权。
     */
    fun openHcAuth() = permissionLauncher.launch(required.toTypedArray())

    var interval by remember { mutableIntStateOf(Settings.intervalMinutes(ctx)) }
    var window by remember { mutableIntStateOf(Settings.windowDays(ctx)) }
    var showLogs by remember { mutableStateOf(false) }

    // 每次进入页面都重新核对一次权限（用户可能在 Health Connect 里改过）
    LaunchedEffect(Unit) { SyncEngine.notePermission(hc.isWriteGranted()) }

    Scaffold(topBar = { TopAppBar(title = { Text("OHSync") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 未授权时置顶提醒：没权限什么都写不进去 ──
            if (!status.hcPermissionGranted) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有 Health Connect 写入权限", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "没有授权的话，数据读出来也写不进 Health Connect。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "提示：在 Health Connect 的「应用权限」里，本应用可能显示在" +
                                    "「非活跃应用」下。那是因为应用每次更新，Health Connect " +
                                    "都会撤销它的授权记录；点下面的按钮重新授权即可恢复正常。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { openHcAuth() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("去授权") }
                        }
                    }
                }
            }

            item { StatusCard(status, onSync = { SyncEngine.requestBackfill() }) }
            item { IntervalCard(interval, window, onInterval = {
                interval = it; Settings.setIntervalMinutes(ctx, it)
            }, onWindow = {
                window = it; Settings.setWindowDays(ctx, it)
            }) }
            item { DataCard() }
            item { DiscardedCard() }
            item { LogCard(showLogs, logs) { showLogs = !showLogs } }
        }
    }
}

/** 状态 + 唯一的主动作。 */
@Composable
private fun StatusCard(status: io.github.ohsync.core.SyncStatus, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("同步状态", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot("读取端", status.hookConnected)
                StatusDot("写入权限", status.hcPermissionGranted)
            }

            Text(
                "上次同步：${
                    status.lastSyncAtMillis
                        ?.let { TIME_FMT.format(Instant.ofEpochMilli(it)) }
                        ?: "还没同步过"
                }",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "累计写入 ${status.syncedCount} 条" +
                    if (status.failedCount > 0) "，失败 ${status.failedCount} 条" else "",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!status.hookConnected) {
                Text(
                    "还没连上 OPPO 健康。请在 LSPosed 里启用本模块、作用域勾选「OPPO 健康」，" +
                        "然后打开一次 OPPO 健康。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSync,
                enabled = !status.pendingBackfill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.pendingBackfill) "正在同步…" else "立即同步")
            }
            if (status.pendingBackfill) {
                Text(
                    "已通知读取端，正在读取并写入，通常几秒内完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 一个小小的状态点 + 文字。 */
@Composable
private fun StatusDot(label: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("●", color = color)
        Text("$label${if (ok) "正常" else "未就绪"}", style = MaterialTheme.typography.bodySmall)
    }
}

/** 同步间隔与时间窗口，两个都让用户直接选。 */
@Composable
private fun IntervalCard(
    interval: Int,
    window: Int,
    onInterval: (Int) -> Unit,
    onWindow: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("同步设置", style = MaterialTheme.typography.titleMedium)

            Text("自动同步间隔", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = Settings.INTERVAL_OPTIONS,
                selected = interval,
                onSelect = onInterval,
            )
            Text(
                if (interval == 0) "只在点「立即同步」时才推送。"
                else "每 $interval 分钟自动检查一次新数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Text("回填时间范围", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = Settings.WINDOW_OPTIONS,
                selected = window,
                onSelect = onWindow,
            )
            Text(
                "只读取这段时间内的数据，避免一次灌入几年的记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 一行可换行的选项小胶囊。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** 会同步哪些数据；以及需要时清除本应用写入的数据。 */
@Composable
private fun DataCard() {
    val synced = RecordType.syncable
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同步的数据（共 ${synced.size} 类）", style = MaterialTheme.typography.titleMedium)
            Text(
                synced.joinToString("、") { it.label },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "数据来源：OPPO 健康数据库。读取在 OPPO 健康进程内完成，只做查询，不修改它的任何数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(
                onClick = { SyncEngine.clearAll() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除本应用已写入的数据") }
            Text(
                "只删除 OHSync 自己写进 Health Connect 的记录，不动其他应用的数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Health Connect 没有对应记录类型的数据，明确列出来，不静默丢弃。 */
@Composable
private fun DiscardedCard() {
    val discarded = RecordType.discarded
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("不同步的数据（共 ${discarded.size} 类）", style = MaterialTheme.typography.titleMedium)
            Text(
                "下面这些在 Health Connect 里没有对应的记录类型，因此不写入：" +
                    discarded.joinToString("、") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 折叠的日志：平时不占地方，出问题时才展开。 */
@Composable
private fun LogCard(expanded: Boolean, logs: List<String>, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                if (logs.isEmpty()) {
                    Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                } else {
                    logs.takeLast(40).reversed().forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else {
                Text(logs.lastOrNull() ?: "暂无日志", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}