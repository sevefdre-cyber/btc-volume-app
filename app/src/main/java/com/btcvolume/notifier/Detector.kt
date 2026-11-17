package com.btcvolume.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max

class ActiveRow(
    val symbol: String,
    val move: Double,   // variation du prix sur la fenêtre, en %
    val price: Double,
    val rank: Int,      // rang de variation sur 24 h
    val d5: Int,        // places gagnées sur 5 min
    val qv: Double
)

/**
 * Détecteur de mouvements : repère les jetons qui bougent brusquement sur quelques minutes,
 * les classe du plus actif au moins actif et envoie une notification.
 */
object Detector {
    val active = MutableStateFlow<List<ActiveRow>>(emptyList())
    private var cfgCache: Config? = null
    private var cfgAt = 0L
    private var lastNotif = 0L
    private val lastTrig = HashMap<String, Long>()
    private var nid = 9000

    /** Appelé chaque seconde par le flux en direct. */
    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        val cached = cfgCache
        val cfg = if (cached != null && now - cfgAt <= 5_000L) cached else Prefs.load(c).also {
            cfgCache = it
            cfgAt = now
        }
        val w = cfg.detWindowMin.coerceIn(1, 30)
        val vars = RankEngine.varsOver(w)
        if (vars.isEmpty()) {
            active.value = emptyList()
            return
        }
        val byBase = HashMap<String, RankRow>()
        for (r in RankEngine.latest) byBase[r.symbol] = r
        val list = ArrayList<ActiveRow>()
        for ((base, v) in vars) {
            val r = byBase[base] ?: continue
            val score = if (cfg.detUpOnly) v else abs(v)
            if (score <= 0.0) continue
            list.add(ActiveRow(base, v, r.price, r.rank, r.d5, r.qv))
        }
        list.sortByDescending { if (cfg.detUpOnly) it.move else abs(it.move) }
        active.value = list.take(20)
        if (!cfg.detOn) return

        val trig = list.filter {
            (if (cfg.detUpOnly) it.move else abs(it.move)) >= cfg.detMovePct &&
                now - (lastTrig[it.symbol] ?: 0L) >= cfg.detCooldownMin * 60_000L
        }
        if (trig.isEmpty() || now - lastNotif < cfg.detGapSec * 1_000L) return
        for (t in trig) lastTrig[t.symbol] = now
        lastNotif = now
        notify(c, trig.take(max(1, cfg.detTop)), w, trig.size)
    }

    private fun notify(c: Context, rows: List<ActiveRow>, w: Int, nTrig: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(VolumeService.CH_ALERT, "Alertes de volume", NotificationManager.IMPORTANCE_HIGH)
        )
        val style = Notification.InboxStyle()
        for ((i, r) in rows.withIndex()) {
            val d = if (r.d5 >= 0) "+" + r.d5 else r.d5.toString()
            style.addLine("${i + 1}. ${r.symbol}  ${fmtPct(r.move)} en $w min · rang ${r.rank} ($d) · ${fmtPrice(r.price)}")
        }
        if (nTrig > rows.size) style.setSummaryText("+${nTrig - rows.size} autres jetons actifs")
        val top = rows.first()
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, VolumeService.CH_ALERT)
            .setContentTitle("${top.symbol} est le plus actif")
            .setContentText("${fmtPct(top.move)} en $w min · $nTrig jeton(s) en mouvement")
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(nid++, n)
        DebugLog.log("DETECT", "notification : " + rows.joinToString(", ") { it.symbol + " " + DebugLog.f(it.move) + "%" })
    }
}
