package com.btcvolume.notifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.load(this)
        Positions.load(this)
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}
