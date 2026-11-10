package com.btcvolume.notifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val cfg = Prefs.load(context)
            if (cfg.autoStart && Prefs.isRunning(context)) {
                try {
                    VolumeService.start(context)
                } catch (e: Exception) {
                    // Android peut refuser le démarrage en arrière-plan
                }
            }
        }
    }
}
