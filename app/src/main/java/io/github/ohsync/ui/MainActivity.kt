package io.github.ohsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.github.ohsync.core.Settings
import io.github.ohsync.core.SyncEngine
import io.github.ohsync.core.SyncService
import io.github.ohsync.core.WakeReceiver
import io.github.ohsync.core.TokenStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化引擎与配对 token：Hook 侧会通过 ContentProvider 来取
        SyncEngine.get(this).restoreStatus(this)
        TokenStore.getOrCreate(this)
        // 常驻服务是后台同步的前提（见 SyncService 的说明）
        if (Settings.keepAlive(this)) {
            SyncService.start(this)
            WakeReceiver.scheduleAlarm(this)
        }
        setContent { MaterialTheme { OHSyncScreen() } }
    }
}
