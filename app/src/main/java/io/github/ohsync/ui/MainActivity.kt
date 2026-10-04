package io.github.ohsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.github.ohsync.core.SyncEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先初始化引擎，让 ContentProvider 之外的主进程侧也处于就绪状态
        SyncEngine.get(this)
        setContent { MaterialTheme { OHSyncScreen() } }
    }
}
