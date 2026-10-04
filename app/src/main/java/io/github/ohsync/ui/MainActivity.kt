package io.github.ohsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.github.ohsync.core.SyncEngine
import io.github.ohsync.core.TokenStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncEngine.get(this)
        val token = TokenStore.getOrCreate(this)
        setContent { MaterialTheme { OHSyncScreen(token) } }
    }
}
