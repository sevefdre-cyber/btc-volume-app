package com.btcvolume.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

private class Cand(val symbol: String, val base: String, val price: Double, val qv24: Double, val chg24: Double)

class VolumeService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALERT = "alerts"
        const val WINDOW = 30

        @Volatile
        var forceScan = false

        private val STABLE = setOf(
            "USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "USDE", "USD1",
            "AEUR", "EUR", "EURI", "XUSD", "UST", "USDS", "PYUSD", "RLUSD"
        )

        fun start(c: Context) {
            Prefs.setRunning(c, true)
            ContextCompat.startForegroundService(c, Intent(c, VolumeService::class.java))
        }

        fun stop(c: Context) {
            Prefs.setRunning(c, false)
            c.stopService(Intent(c, VolumeService::class.java))
        }

        fun intervalMs(i: String): Long = when (i) {
            "1m" -> 60_000L
            "15m" -> 900_000L
            "1h" -> 3_600_000L
            else -> 300_000L
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertId = 100
    private val lastCandle = HashMap<String, Long>()
    private val lastAlertAt = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        History.load(this)
        Positions.load(this)
        DebugLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val notif = fgNotification("Démarrage…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }

        if (worker?.isAlive != true) {
            running = true
            ScanState.running.value = true
            forceScan = true
            if (Prefs.load(this).wakeLock) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volumescanner:scan").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        LiveFeed.release("strategies")
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        ScanState.running.value = false
        ScanState.status.value = "Arrêté"
        super.onDestroy()
    }

    // ---------- Notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Service", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alertes de volume", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun fgNotification(text: String): Notification =
        Notification.Builder(this, CH_SERVICE)
            .setContentTitle("Scanner de volume Binance")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()

    private fun updateForeground(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, fgNotification(text))
    }

    private fun notifyHits(hits: List<ScanRow>, cfg: Config) {
        val nm = getSystemService(NotificationManager::class.java)
        val tc = TradePrefs.load(this)
        val semi = tc.enabled && tc.trigger == "SEMI"
        if (cfg.notifMode == "SUMMARY" && !semi) {
            val style = Notification.InboxStyle()
            for (h in hits.take(5)) {
                style.addLine("${h.symbol}  ${fmtRatio(h.ratio)}  ${fmtPrice(h.price)}  ${fmtPct(h.changePct)}")
            }
            if (hits.size > 5) style.setSummaryText("+${hits.size - 5} autres")
            val top = hits.first()
            val n = Notification.Builder(this, CH_ALERT)
                .setContentTitle("${hits.size} pic(s) de volume")
                .setContentText("${top.symbol} ${fmtRatio(top.ratio)} en tête")
                .setStyle(style)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
            nm.notify(alertId++, n)
        } else {
            // En mode semi-automatique : une notification par crypto, avec bouton « Acheter ».
            for (h in hits.take(if (semi) 5 else 10)) {
                val arrow = if (h.changePct >= 0) "▲" else "▼"
                val text = "Prix ${fmtPrice(h.price)} $arrow ${fmtPct(h.changePct)} · Vol ${fmtVol(h.volUsdt)} USDT"
                val id = alertId++
                val b = Notification.Builder(this, CH_ALERT)
                    .setContentTitle("${h.symbol} ${fmtRatio(h.ratio)} volume")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_stat_scan)
                    .setContentIntent(contentIntent())
                    .setAutoCancel(true)
                if (semi) {
                    val buy = Intent(this, TradeActionReceiver::class.java)
                        .setAction("com.btcvolume.notifier.BUY")
                        .putExtra("symbol", h.symbol)
                        .putExtra("nid", id)
                        .putExtra("price", h.price)
                        .putExtra("ratio", h.ratio)
                        .putExtra("change", h.changePct)
                        .putExtra("wick", h.wickPct)
                        .putExtra("chg24", h.chg24)
                    val pi = PendingIntent.getBroadcast(
                        this, id, buy, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    b.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.ic_stat_scan),
                            "Acheter ${fmtNum(tc.amountUsdt)} USDT", pi
                        ).build()
                    )
                }
                nm.notify(id, b.build())
            }
        }
    }

    /** Achat automatique des alertes qui respectent la stratégie et les protections. */
    private fun autoTrade(hits: List<ScanRow>) {
        val tc = TradePrefs.load(this)
        if (!tc.enabled || tc.trigger != "AUTO") return
        if (tc.env == "REAL" && !tc.realAutoOk) {
            Notifier.post(this, "Trading auto bloqué", "Mode réel : autorisez l'automatique dans Trade > Stratégie.")
            return
        }
        Positions.load(this)
        for (h in hits) {
            if (h.ratio < tc.autoMinRatio) continue
            if (tc.buyOnlyUp && h.changePct < 0) continue
            if (Positions.items.value.any { it.symbol == h.symbol && it.mode == tc.env && it.status == "OPEN" }) continue
            val r = Trader.buy(this, h.symbol, "AUTO", Signal(h.price, h.ratio, h.changePct, h.wickPct, h.chg24))
            if (r.blocked) break
        }
    }

    // ---------- Boucle principale ----------

    private fun loop() {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var lastKey = ""
        var lastSync = 0L
        while (running) {
            var pause = 5_000L
            try {
                val cfg = Prefs.load(this)
                val ms = intervalMs(cfg.interval)
                val now = System.currentTimeMillis()
                val closedOpen = (now / ms) * ms - ms          // ouverture de la dernière bougie clôturée
                val key = "${cfg.interval}:$closedOpen"
                val forced = forceScan
                if (forced || (key != lastKey && now >= closedOpen + ms + 3_000)) {
                    forceScan = false
                    scan(cfg, clock)
                    lastKey = key
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                ScanState.status.value = "Erreur : ${e.message}"
                DebugLog.log("ERREUR", "boucle du scan : ${e.message}")
                pause = 30_000L
            }
            try {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSync >= 20_000L && Positions.items.value.any { it.status == "OPEN" }) {
                    lastSync = nowMs
                    Trader.syncAll(this)
                }
            } catch (e: Exception) {
                // la synchronisation des positions réessaiera au prochain cycle
            }
            try {
                StrategyRunner.manage(this)
            } catch (e: Exception) {
                // les stratégies réessaieront au prochain cycle
            }
            try {
                Thread.sleep(pause)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun scan(cfg: Config, clock: SimpleDateFormat) {
        ScanState.status.value = "Analyse en cours…"
        val (cands, total) = candidates(cfg)
        val errors = AtomicInteger(0)
        val rows = analyzeAll(cands, cfg.interval, errors)
        if (cands.isNotEmpty() && errors.get() > cands.size / 2) {
            throw IOException("trop d'erreurs (limite Binance ou réseau), nouvelle tentative…")
        }

        val now = System.currentTimeMillis()
        val sorted = rows.sortedByDescending { it.ratio }
        val threshold = if (cfg.volThreshold < 1.05) 1.05 else cfg.volThreshold
        var btcReason: String? = null
        if (cfg.btcFilter) {
            try {
                val info = btcInfo()
                btcReason = BtcRules.reason(cfg, info)
                ScanState.btc.value = "BTC · 1 h " + fmtPct(info.chg1h) + " · tendance " + fmtPct(info.trend) +
                    " · volatilité x" + String.format(Locale.US, "%.1f", info.shock) + " → " +
                    (if (btcReason == null) "favorable" else "défavorable : " + btcReason)
            } catch (e: Exception) {
                ScanState.btc.value = "BTC indisponible : filtre ignoré"
            }
        } else {
            ScanState.btc.value = "Filtre Bitcoin désactivé"
        }
        val btcOk = btcReason == null
        ScanState.btcBlocked.value = !btcOk
        val hits = if (!btcOk) emptyList() else sorted.filter { r ->
            r.ratio >= threshold &&
                (cfg.volMax <= 0.0 || r.ratio <= cfg.volMax) &&
                (cfg.mode == "VOLUME" || abs(r.changePct) >= cfg.priceThreshold) &&
                (cfg.explosionMin <= 0.0 || r.changePct >= cfg.explosionMin) &&
                r.wickPct <= cfg.maxWickPct &&
                (cfg.max24hPct <= 0.0 || r.chg24 <= cfg.max24hPct) &&
                (cfg.atrMin <= 0.0 || r.atrPct >= cfg.atrMin) &&
                (cfg.atrMax <= 0.0 || r.atrPct <= cfg.atrMax) &&
                (cfg.rangeMinRatio <= 0.0 || r.rangeRatio >= cfg.rangeMinRatio) &&
                (!cfg.trendFilter || r.trendPct >= -cfg.trendTolPct) &&
                (!cfg.upCandleOnly || r.changePct >= 0) &&
                lastCandle[r.symbol] != r.candle &&
                (now - (lastAlertAt[r.symbol] ?: 0L)) >= cfg.cooldownMin * 60_000L
        }
        if (DebugLog.on) debugScan(cfg, sorted, hits, threshold, now, btcReason, cands.size, total, errors.get())
        for (h in hits) {
            lastCandle[h.symbol] = h.candle
            lastAlertAt[h.symbol] = now
        }

        ScanState.rows.value = sorted
        ScanState.monitored.value = cands.size
        ScanState.totalPairs.value = total
        ScanState.lastScan.value = now
        val stamp = clock.format(Date(now))
        ScanState.status.value = "Dernier scan à $stamp · ${hits.size} alerte(s)" +
            (if (!btcOk) " · bloqué : $btcReason" else "")
        updateForeground("${cands.size} cryptos surveillées · scan $stamp")

        if (hits.isNotEmpty()) {
            History.add(this, hits.map { AlertItem(now, it.symbol, it.price, it.changePct, it.ratio, it.volUsdt) })
            notifyHits(hits, cfg)
            autoTrade(hits)
        }
    }

    /** Raison pour laquelle un jeton n'est pas retenu (null = retenu). */
    private fun rejectReason(r: ScanRow, cfg: Config, threshold: Double, now: Long): String? {
        if (r.ratio < threshold) return "ratio<min"
        if (cfg.volMax > 0.0 && r.ratio > cfg.volMax) return "ratio>max"
        if (cfg.mode != "VOLUME" && abs(r.changePct) < cfg.priceThreshold) return "variation<seuil"
        if (cfg.explosionMin > 0.0 && r.changePct < cfg.explosionMin) return "explosion<min"
        if (r.wickPct > cfg.maxWickPct) return "meche"
        if (cfg.max24hPct > 0.0 && r.chg24 > cfg.max24hPct) return "hausse24h"
        if (cfg.atrMin > 0.0 && r.atrPct < cfg.atrMin) return "atr<min"
        if (cfg.atrMax > 0.0 && r.atrPct > cfg.atrMax) return "atr>max"
        if (cfg.rangeMinRatio > 0.0 && r.rangeRatio < cfg.rangeMinRatio) return "expansion"
        if (cfg.trendFilter && r.trendPct < -cfg.trendTolPct) return "tendance"
        if (cfg.upCandleOnly && r.changePct < 0) return "bougie_baissiere"
        if (lastCandle[r.symbol] == r.candle) return "deja_alerte_bougie"
        if (now - (lastAlertAt[r.symbol] ?: 0L) < cfg.cooldownMin * 60_000L) return "repos"
        return null
    }

    /** Journal : résumé du scan, compteurs de rejets, et détail des 40 jetons au plus fort ratio de volume. */
    private fun debugScan(
        cfg: Config, sorted: List<ScanRow>, hits: List<ScanRow>, threshold: Double,
        now: Long, btcReason: String?, nCands: Int, total: Int, errors: Int
    ) {
        val counts = HashMap<String, Int>()
        for (r in sorted) {
            val why = if (btcReason != null) "btc" else (rejectReason(r, cfg, threshold, now) ?: "retenu")
            counts[why] = (counts[why] ?: 0) + 1
        }
        val summary = counts.entries.sortedByDescending { it.value }.joinToString(" ") { it.key + "=" + it.value }
        DebugLog.log(
            "SCAN",
            "bougies=${cfg.interval} paires=$total candidats=$nCands analysés=${sorted.size} erreurs=$errors alertes=${hits.size} | rejets: $summary | ${ScanState.btc.value}"
        )
        for (r in sorted.take(40)) {
            val why = if (btcReason != null) "btc" else (rejectReason(r, cfg, threshold, now) ?: "retenu")
            DebugLog.csv(
                "scan", "ts_ms,bougies,symbole,prix,variation_bougie,ratio,volume_usdt,meche,atr,expansion,tendance,hausse24h,statut",
                "$now,${cfg.interval},${r.symbol},${r.price},${DebugLog.f(r.changePct, 3)},${DebugLog.f(r.ratio)}," +
                    "${DebugLog.f(r.volUsdt, 0)},${DebugLog.f(r.wickPct, 1)},${DebugLog.f(r.atrPct, 3)},${DebugLog.f(r.rangeRatio)}," +
                    "${DebugLog.f(r.trendPct, 3)},${DebugLog.f(r.chg24, 2)},$why"
            )
        }
    }

    // ---------- API Binance (publique, sans clé) ----------

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            val code = conn.responseCode
            if (code == 429 || code == 418) throw IOException("limite Binance atteinte (HTTP $code)")
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** État de Bitcoin (bougies de 15 min clôturées) : variation 1 h, tendance MA10/MA30, choc de volatilité. */
    private fun btcInfo(): BtcInfo {
        val arr = JSONArray(http("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=15m&limit=42"))
        val n = arr.length() - 1                    // bougies clôturées : indices 0 .. n-1
        if (n < 31) throw IOException("historique BTC insuffisant")
        val close = DoubleArray(n)
        val range = DoubleArray(n)
        for (i in 0 until n) {
            val k = arr.getJSONArray(i)
            close[i] = k.getString(4).toDouble()
            range[i] = (k.getString(2).toDouble() - k.getString(3).toDouble()) / close[i] * 100.0
        }
        val b = n - 1
        val chg = (close[b] - close[b - 4]) / close[b - 4] * 100.0
        var s30 = 0.0
        var s10 = 0.0
        for (j in b - 29..b) {
            s30 += close[j]
            if (j > b - 10) s10 += close[j]
        }
        s30 /= 30.0
        s10 /= 10.0
        val trend = (s10 - s30) / s30 * 100.0
        var rs = 0.0
        for (j in b - 20 until b) rs += range[j]
        val avgR = rs / 20.0
        val shock = if (avgR > 0.0) range[b] / avgR else 0.0
        return BtcInfo(chg, trend, shock)
    }

    /** Une seule requête pour toutes les paires, puis filtres prix / volume / listes. */
    private fun candidates(cfg: Config): Pair<List<Cand>, Int> {
        val arr = JSONArray(http("https://api.binance.com/api/v3/ticker/24hr?type=MINI"))
        val favs = ArrayList<Cand>()
        val others = ArrayList<Cand>()
        var total = 0
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val sym = t.getString("symbol")
            if (!sym.endsWith("USDT")) continue
            val base = sym.removeSuffix("USDT")
            total++
            if (base in cfg.excluded) continue
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val qv = t.optString("quoteVolume").toDoubleOrNull() ?: continue
            if (price <= 0.0 || qv <= 0.0) continue
            val hi = t.optString("highPrice").toDoubleOrNull() ?: price
            val lo = t.optString("lowPrice").toDoubleOrNull() ?: price
            if (Stables.isStable(base, price, hi, lo)) continue
            val op = t.optString("openPrice").toDoubleOrNull() ?: price
            val chg = if (op > 0.0) (price - op) / op * 100.0 else 0.0
            if (base in cfg.favorites) {
                favs.add(Cand(sym, base, price, qv, chg))
            } else if (price >= cfg.priceMin && price <= cfg.priceMax && qv >= cfg.minVol24h) {
                others.add(Cand(sym, base, price, qv, chg))
            }
        }
        others.sortByDescending { it.qv24 }
        val room = (cfg.maxSymbols - favs.size).coerceAtLeast(0)
        return Pair(favs + others.take(room), total)
    }

    private fun analyzeAll(cands: List<Cand>, interval: String, errors: AtomicInteger): List<ScanRow> {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = cands.map { c ->
                pool.submit(Callable<ScanRow?> {
                    try {
                        analyze(c, interval)
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        null
                    }
                })
            }
            return futures.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun analyze(c: Cand, interval: String): ScanRow? {
        val url = "https://api.binance.com/api/v3/klines?symbol=${c.symbol}&interval=$interval&limit=${WINDOW + 2}"
        val arr = JSONArray(http(url))
        val n = arr.length() - 2                  // la dernière bougie (en cours) est ignorée
        if (n < 5) return null
        val last = arr.getJSONArray(n)            // dernière bougie clôturée
        val open = last.getString(1).toDouble()
        val close = last.getString(4).toDouble()
        val qv = last.getString(7).toDouble()
        var sum = 0.0
        for (i in 0 until n) sum += arr.getJSONArray(i).getString(7).toDouble()
        val avg = sum / n
        if (avg <= 0.0 || open <= 0.0) return null
        val high = last.getString(2).toDouble()
        val low = last.getString(3).toDouble()
        val wick = if (high > low) (high - maxOf(open, close)) / (high - low) * 100.0 else 0.0
        // volatilité (ATR en %) et tendance (MA10 vs MA30) sur l'historique qui précède le signal
        val kAtr = minOf(14, n)
        var atrSum = 0.0
        for (i in n - kAtr until n) {
            val h = arr.getJSONArray(i)
            val cl = h.getString(4).toDouble()
            if (cl > 0.0) atrSum += (h.getString(2).toDouble() - h.getString(3).toDouble()) / cl * 100.0
        }
        val atr = atrSum / kAtr
        val lastRange = if (close > 0.0) (high - low) / close * 100.0 else 0.0
        val rangeRatio = if (atr > 0.0) lastRange / atr else 0.0
        val kShort = minOf(10, n)
        var sLong = 0.0
        var sShort = 0.0
        for (i in 0 until n) {
            val cl = arr.getJSONArray(i).getString(4).toDouble()
            sLong += cl
            if (i >= n - kShort) sShort += cl
        }
        val maLong = sLong / n
        val maShort = sShort / kShort
        val trend = if (maLong > 0.0) (maShort - maLong) / maLong * 100.0 else 0.0
        return ScanRow(
            c.base, c.price, (close - open) / open * 100.0, qv / avg, qv,
            last.getLong(0), wick, c.chg24, atr, rangeRatio, trend,
            curOpen = arr.getJSONArray(arr.length() - 1).getString(1).toDouble()
        )
    }
}
