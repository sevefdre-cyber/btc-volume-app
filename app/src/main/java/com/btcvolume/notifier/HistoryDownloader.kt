package com.btcvolume.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

object HistoryDownloader {
    private val INTERVALS = listOf("1h", "15m", "5m")

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Les N paires USDT les plus échangées, hors stablecoins (Bitcoin toujours inclus). */
    private fun universe(count: Int): List<String> {
        val arr = JSONArray(Binance.publicGet("REAL", "/api/v3/ticker/24hr", mapOf("type" to "MINI")))
        class T(val base: String, val qv: Double)
        val l = ArrayList<T>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val sym = t.getString("symbol")
            if (!sym.endsWith("USDT")) continue
            val base = sym.removeSuffix("USDT")
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val qv = t.optString("quoteVolume").toDoubleOrNull() ?: continue
            if (price <= 0.0 || qv <= 0.0) continue
            val hi = t.optString("highPrice").toDoubleOrNull() ?: price
            val lo = t.optString("lowPrice").toDoubleOrNull() ?: price
            if (Stables.isStable(base, price, hi, lo)) continue
            l.add(T(base, qv))
        }
        val out = l.sortedByDescending { it.qv }.take(count).map { it.base }.toMutableList()
        if ("BTC" !in out) out.add("BTC")
        return out
    }

    fun run(c: Context, count: Int, wifiOnly: Boolean, months5m: Int, progress: (String) -> Unit) {
        HistState.running.value = true
        HistState.errors.value = 0
        HistState.done.value = 0
        HistState.status.value = "Liste des cryptos…"
        val bases = universe(count)
        HistoryStore.saveSymbols(c, bases)

        val tasks = ArrayList<Pair<String, String>>()
        for (b in bases) for (iv in INTERVALS) tasks.add(Pair(b, iv))
        HistState.total.value = tasks.size

        val idx = AtomicInteger(0)
        val done = AtomicInteger(0)
        val errs = AtomicInteger(0)
        val start = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 6).map {
                pool.submit(Callable<Unit> {
                    while (!HistState.cancel) {
                        val i = idx.getAndIncrement()
                        if (i >= tasks.size) break
                        val base = tasks[i].first
                        val iv = tasks[i].second
                        while (wifiOnly && !onWifi(c) && !HistState.cancel) {
                            HistState.status.value = "En pause : pas de Wi-Fi"
                            Thread.sleep(5_000)
                        }
                        if (HistState.cancel) break
                        try {
                            downloadOne(c, base, iv, if (iv == "5m") months5m else 12)
                        } catch (e: Exception) {
                            HistState.errors.value = errs.incrementAndGet()
                        }
                        val d = done.incrementAndGet()
                        HistState.done.value = d
                        if (d % 5 == 0) {
                            val elapsed = System.currentTimeMillis() - start
                            val remainMin = elapsed * (tasks.size - d) / d / 60_000
                            val txt = "Téléchargement : $d/${tasks.size} · reste environ $remainMin min"
                            HistState.status.value = txt
                            progress(txt)
                        }
                    }
                })
            }
            for (f in futures) f.get()
        } finally {
            pool.shutdownNow()
        }
        HistState.status.value = if (HistState.cancel) {
            "Interrompu : relancez pour reprendre."
        } else {
            "Terminé : ${done.get()} fichiers, ${errs.get()} erreur(s)."
        }
    }

    private fun downloadOne(c: Context, base: String, iv: String, months: Int) {
        val pair = base + "USDT"
        val ms = VolumeService.intervalMs(iv)
        val existing = HistoryStore.read(c, iv, base)
        val all = ArrayList<Candle>()
        val tailFrom: Long

        if (existing != null && existing.isNotEmpty()) {
            // mise à jour : on complète seulement la fin
            all.addAll(existing)
            tailFrom = existing.last().time + ms
        } else {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val monthStart = cal.timeInMillis
            var found = false
            for (m in months downTo 1) {
                if (HistState.cancel) return
                val c2 = cal.clone() as Calendar
                c2.add(Calendar.MONTH, -m)
                val got = fetchMonth(pair, iv, c2.get(Calendar.YEAR), c2.get(Calendar.MONTH) + 1)
                if (got != null && got.isNotEmpty()) {
                    all.addAll(got)
                    found = true
                }
            }
            // si les archives sont inaccessibles, on retombe sur l'API pour toute la période
            tailFrom = if (found) monthStart else System.currentTimeMillis() - (months * 30.4375 * 86_400_000L).toLong()
        }

        if (System.currentTimeMillis() - tailFrom > ms) all.addAll(fetchTail(pair, iv, tailFrom, ms))

        val sorted = all.sortedBy { it.time }
        val out = ArrayList<Candle>(sorted.size)
        var last = -1L
        for (x in sorted) {
            if (x.time != last) {
                out.add(x)
                last = x.time
            }
        }
        if (out.size >= 100) HistoryStore.write(c, iv, base, out)
    }

    private fun fetchMonth(pair: String, iv: String, y: Int, mo: Int): List<Candle>? {
        val mm = if (mo < 10) "0$mo" else "$mo"
        val url = "https://data.binance.vision/data/spot/monthly/klines/$pair/$iv/$pair-$iv-$y-$mm.zip"
        var lastErr: Exception? = null
        for (attempt in 0 until 3) {
            if (HistState.cancel) return null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                try {
                    val code = conn.responseCode
                    if (code == 404) return null
                    if (code != 200) throw IOException("HTTP $code")
                    val out = ArrayList<Candle>()
                    ZipInputStream(BufferedInputStream(conn.inputStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val br = BufferedReader(InputStreamReader(zis))
                            br.forEachLine { line ->
                                val p = line.split(',')
                                if (p.size >= 6) {
                                    var t = p[0].toLongOrNull()
                                    val o = p[1].toDoubleOrNull()
                                    val h = p[2].toDoubleOrNull()
                                    val l = p[3].toDoubleOrNull()
                                    val cl = p[4].toDoubleOrNull()
                                    val v = p[5].toDoubleOrNull()
                                    if (t != null && o != null && h != null && l != null && cl != null && v != null) {
                                        // depuis 2025, certaines archives utilisent des microsecondes
                                        if (t > 100_000_000_000_000L) t /= 1000
                                        out.add(Candle(t, o, h, l, cl, v))
                                    }
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                    return out
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(1_500L * (attempt + 1))
            }
        }
        throw lastErr ?: IOException("échec du téléchargement")
    }

    /** Bougies récentes via l'API, de [from] jusqu'à maintenant (bougie en cours exclue). */
    private fun fetchTail(pair: String, iv: String, from: Long, ms: Long): List<Candle> {
        var all = Binance.candles(pair, iv, 1000)
        while (all.isNotEmpty() && all.first().time > from && !HistState.cancel) {
            val older = Binance.candles(pair, iv, 1000, all.first().time - 1)
            if (older.isEmpty()) break
            all = older + all
        }
        val now = System.currentTimeMillis()
        return all.filter { it.time >= from && it.time + ms <= now }
    }
}

class HistoryService : Service() {

    companion object {
        const val EXTRA_COUNT = "count"
        const val EXTRA_WIFI = "wifi"
        const val EXTRA_M5 = "m5"

        fun start(c: Context, count: Int, wifiOnly: Boolean, months5m: Int) {
            val i = Intent(c, HistoryService::class.java)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_WIFI, wifiOnly)
                .putExtra(EXTRA_M5, months5m)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            HistState.cancel = true
            c.stopService(Intent(c, HistoryService::class.java))
        }
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String): Notification =
        Notification.Builder(this, VolumeService.CH_SERVICE)
            .setContentTitle("Téléchargement de l'historique")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(VolumeService.CH_SERVICE, "Service", NotificationManager.IMPORTANCE_LOW)
        )
        val n = notif("Préparation…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, n)
        }
        if (worker?.isAlive != true) {
            val count = intent?.getIntExtra(EXTRA_COUNT, 300) ?: 300
            val wifi = intent?.getBooleanExtra(EXTRA_WIFI, true) ?: true
            val m5 = intent?.getIntExtra(EXTRA_M5, 3) ?: 3
            HistState.cancel = false
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volumescanner:hist").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
            worker = Thread {
                try {
                    HistoryDownloader.run(applicationContext, count, wifi, m5) { text ->
                        nm.notify(2, notif(text))
                    }
                } catch (e: Exception) {
                    HistState.status.value = "Erreur : ${e.message}"
                } finally {
                    HistState.running.value = false
                    HistState.refresh(applicationContext)
                    stopSelf()
                }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        HistState.cancel = true
        HistState.running.value = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
