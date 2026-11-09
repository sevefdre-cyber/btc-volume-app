package com.btcvolume.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VolumeService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALERT = "alerts"
        const val SYMBOL = "BTCUSDT"
        const val WINDOW = 30
        const val POLL_MS = 30_000L
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var alertId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CH_SERVICE)
            .setContentTitle("Surveillance du volume $SYMBOL")
            .setContentText("Actif en arrière-plan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }

        if (worker?.isAlive != true) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        setStatus("Arrêté")
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Service", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alertes de volume", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun setStatus(text: String) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("status", text).apply()
    }

    private fun fetchKlines(interval: String): JSONArray {
        val url = URL(
            "https://api.binance.com/api/v3/klines?symbol=$SYMBOL&interval=$interval&limit=${WINDOW + 2}"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun loop() {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        var lastNotified = 0L
        val hour = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        while (running) {
            try {
                val interval = prefs.getString("interval", "5m") ?: "5m"
                val threshold = prefs.getFloat("threshold", 2.5f).toDouble()
                val notifyDrop = prefs.getBoolean("drop", false)

                val arr = fetchKlines(interval)
                val last = arr.getJSONArray(arr.length() - 2) // dernière bougie clôturée
                val openTime = last.getLong(0)
                val open = last.getString(1).toDouble()
                val close = last.getString(4).toDouble()
                val volBtc = last.getString(5).toDouble()
                val volUsdt = last.getString(7).toDouble()

                var sum = 0.0
                val n = arr.length() - 2
                for (i in 0 until n) sum += arr.getJSONArray(i).getString(5).toDouble()
                val avg = sum / n
                val ratio = if (avg > 0) volBtc / avg else 0.0
                val change = (close - open) / open * 100

                setStatus(
                    "Dernière vérification : ${hour.format(Date())}\n" +
                    "Ratio volume : x%.2f (seuil x%.1f)\nPrix : %,.2f USDT".format(ratio, threshold, close)
                )

                if (openTime != lastNotified) {
                    val arrow = if (change >= 0) "▲" else "▼"
                    val details = "Volume : %.2f BTC (%,.0f USDT) | x%.2f de la moyenne | Prix : %,.2f %s %+.2f%%"
                        .format(volBtc, volUsdt, ratio, close, arrow, change)

                    if (ratio >= threshold) {
                        alert("📈 Pic de volume $SYMBOL", details)
                        lastNotified = openTime
                    } else if (notifyDrop && ratio <= 1 / threshold) {
                        alert("📉 Chute de volume $SYMBOL", details)
                        lastNotified = openTime
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                setStatus("Erreur : ${e.message}")
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun alert(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(alertId++, n)
    }
}
