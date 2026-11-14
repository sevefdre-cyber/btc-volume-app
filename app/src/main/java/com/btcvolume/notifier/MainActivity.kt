package com.btcvolume.notifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        if (Prefs.load(this).liveOn) LiveFeed.acquire(this, "ui")
    }

    override fun onStop() {
        LiveFeed.release("ui")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.load(this)
        Positions.load(this)
        ThemeState.mode.value = Prefs.load(this).themeMode
        DebugLog.init(this)
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}
