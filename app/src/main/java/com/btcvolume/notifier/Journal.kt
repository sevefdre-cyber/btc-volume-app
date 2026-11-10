package com.btcvolume.notifier

import org.json.JSONArray
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class JStats(
    val n: Int,
    val wins: Int,
    val total: Double,
    val avgWin: Double,
    val avgLoss: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val maxDd: Double,
    val avgSlip: Double
)

data class Bucket(val label: String, val n: Int, val winPct: Int, val avgPnl: Double)

object Journal {
    fun valid(list: List<Position>): List<Position> =
        list.filter { it.status == "CLOSED" && it.closeReason != "ANNULÉ" }

    fun stats(closed: List<Position>): JStats? {
        val l = valid(closed).sortedBy { it.closeTime }
        if (l.isEmpty()) return null
        val wins = l.filter { it.pnl > 0 }
        val losses = l.filter { it.pnl <= 0 }
        val gw = wins.sumOf { it.pnl }
        val gl = -losses.sumOf { it.pnl }
        var cum = 0.0
        var peak = 0.0
        var dd = 0.0
        for (p in l) {
            cum += p.pnl
            peak = max(peak, cum)
            dd = max(dd, peak - cum)
        }
        val slips = l.filter { it.sigRatio > 0 }.map { it.slippagePct }
        val avgSlip = if (slips.isEmpty()) 0.0 else slips.average()
        return JStats(
            n = l.size,
            wins = wins.size,
            total = l.sumOf { it.pnl },
            avgWin = if (wins.isEmpty()) 0.0 else gw / wins.size,
            avgLoss = if (losses.isEmpty()) 0.0 else -gl / losses.size,
            profitFactor = if (gl > 0) gw / gl else Double.POSITIVE_INFINITY,
            expectancy = l.sumOf { it.pnl } / l.size,
            maxDd = dd,
            avgSlip = avgSlip
        )
    }

    private fun bucketOf(label: String, g: List<Position>): Bucket =
        Bucket(label, g.size, g.count { it.pnl > 0 } * 100 / g.size, g.sumOf { it.pnl } / g.size)

    /** Regroupe les trades issus d'une alerte par tranche d'une caractéristique du signal. */
    fun buckets(closed: List<Position>, value: (Position) -> Double, edges: List<Double>, unit: String): List<Bucket> {
        val l = valid(closed).filter { it.sigRatio > 0 }
        val groups = l.groupBy { p -> edges.count { value(p) >= it } }
        val out = ArrayList<Bucket>()
        for (i in 0..edges.size) {
            val g = groups[i] ?: continue
            val label = when (i) {
                0 -> "< ${fmtNum(edges[0])}$unit"
                edges.size -> "≥ ${fmtNum(edges[i - 1])}$unit"
                else -> "${fmtNum(edges[i - 1])}–${fmtNum(edges[i])}$unit"
            }
            out.add(bucketOf(label, g))
        }
        return out
    }

    fun byReason(closed: List<Position>): List<Bucket> =
        valid(closed).groupBy { it.closeReason.ifEmpty { "?" } }.map { bucketOf(it.key, it.value) }
}

// ---------------------------------------------------------------- backtest

data class BtResult(
    val n: Int,
    val wins: Int,
    val totalPct: Double,
    val avgPct: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val profitFactor: Double,
    val maxDdPct: Double
)

private class BtTrade(val time: Long, val pnlA: Double, val pnlB: Double)

object Backtester {
    private const val WINDOW = 30
    private val STABLE = setOf(
        "USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "USDE", "USD1",
        "AEUR", "EUR", "EURI", "XUSD", "UST", "USDS", "PYUSD", "RLUSD"
    )

    private fun candidates(cfg: Config, maxN: Int): List<String> {
        val arr = JSONArray(Binance.publicGet("REAL", "/api/v3/ticker/24hr", mapOf("type" to "MINI")))
        class T(val base: String, val qv: Double)
        val l = ArrayList<T>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val sym = t.getString("symbol")
            if (!sym.endsWith("USDT")) continue
            val base = sym.removeSuffix("USDT")
            if (base in STABLE || base in cfg.excluded) continue
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val qv = t.optString("quoteVolume").toDoubleOrNull() ?: continue
            if (price <= 0.0 || qv <= 0.0) continue
            if (base in cfg.favorites || (price >= cfg.priceMin && price <= cfg.priceMax && qv >= cfg.minVol24h)) {
                l.add(T(base, qv))
            }
        }
        return l.sortedByDescending { it.qv }.take(maxN).map { it.base }
    }

    private fun pct(entry: Double, exit: Double): Double = (exit / entry - 1) * 100 - 0.2   // 0,2 % de frais aller-retour

    /** Retourne (résultat en %, index de la bougie de sortie). Le SL est supposé touché avant le TP dans une même bougie. */
    private fun exitTrade(
        cs: List<Candle>, from: Int, entry: Double, tc: TradeConfig,
        trail: Double, maxHold: Int, slip: Double
    ): Pair<Double, Int> {
        val tp = entry * (1 + tc.tpPct / 100)
        var stop = entry * (1 - tc.slPct / 100)
        var peak = entry
        var activated = false
        val last = min(cs.size - 1, from + maxHold - 1)
        var j = from
        while (j <= last) {
            val c = cs[j]
            if (c.low <= stop) {
                val px = (if (c.open < stop) c.open else stop) * (1 - slip / 100)
                return Pair(pct(entry, px), j)
            }
            if (trail <= 0.0) {
                if (c.high >= tp) return Pair(pct(entry, tp), j)
            } else {
                peak = max(peak, c.high)
                if (peak >= tp) activated = true
                if (activated) stop = max(stop, peak * (1 - trail / 100))
            }
            j++
        }
        return Pair(pct(entry, cs[last].close * (1 - slip / 100)), last)
    }

    private fun simulate(
        base: String, cfg: Config, tc: TradeConfig, trail: Double, maxHold: Int, slip: Double
    ): List<BtTrade> {
        val cs = Binance.candles(base + "USDT", cfg.interval, 1000)
        val n = cs.size
        val ms = VolumeService.intervalMs(cfg.interval)
        val k = (86_400_000L / ms).toInt()
        val thr = if (cfg.volThreshold < 1.05) 1.05 else cfg.volThreshold
        val minRatio = max(thr, tc.autoMinRatio)
        val out = ArrayList<BtTrade>()
        var blockedUntil = -1
        var lastSigTime = 0L
        var i = WINDOW
        while (i < n - 1) {
            val c = cs[i]
            var avg = 0.0
            for (j in i - WINDOW until i) avg += cs[j].vol
            avg /= WINDOW
            if (avg > 0 && c.open > 0) {
                val ratio = c.vol / avg
                val change = (c.close - c.open) / c.open * 100
                val rng = c.high - c.low
                val wick = if (rng > 0) (c.high - max(c.open, c.close)) / rng * 100 else 0.0
                val chg24 = if (i >= k && cs[i - k].close > 0) (c.close - cs[i - k].close) / cs[i - k].close * 100 else 0.0
                val ok = ratio >= minRatio &&
                    (cfg.mode == "VOLUME" || abs(change) >= cfg.priceThreshold) &&
                    wick <= cfg.maxWickPct &&
                    (cfg.max24hPct <= 0 || chg24 <= cfg.max24hPct) &&
                    (!tc.buyOnlyUp || change >= 0) &&
                    i > blockedUntil &&
                    (c.time - lastSigTime) >= cfg.cooldownMin * 60_000L
                if (ok) {
                    lastSigTime = c.time
                    val entry = cs[i + 1].open * (1 + slip / 100)
                    val a = exitTrade(cs, i + 1, entry, tc, 0.0, maxHold, slip)
                    val b = exitTrade(cs, i + 1, entry, tc, trail, maxHold, slip)
                    out.add(BtTrade(cs[i + 1].time, a.first, b.first))
                    blockedUntil = max(a.second, b.second)
                }
            }
            i++
        }
        return out
    }

    private fun agg(trades: List<BtTrade>, pick: (BtTrade) -> Double): BtResult {
        val s = trades.sortedBy { it.time }
        if (s.isEmpty()) return BtResult(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val v = s.map(pick)
        val wins = v.filter { it > 0 }
        val losses = v.filter { it <= 0 }
        val gw = wins.sum()
        val gl = -losses.sum()
        var cum = 0.0
        var peak = 0.0
        var dd = 0.0
        for (x in v) {
            cum += x
            peak = max(peak, cum)
            dd = max(dd, peak - cum)
        }
        return BtResult(
            n = v.size,
            wins = wins.size,
            totalPct = v.sum(),
            avgPct = v.sum() / v.size,
            avgWinPct = if (wins.isEmpty()) 0.0 else gw / wins.size,
            avgLossPct = if (losses.isEmpty()) 0.0 else -gl / losses.size,
            profitFactor = if (gl > 0) gw / gl else Double.POSITIVE_INFINITY,
            maxDdPct = dd
        )
    }

    /** Compare deux règles de sortie (A : TP fixe, B : stop suiveur après le TP) sur les mêmes signaux. */
    fun run(
        cfg: Config, tc: TradeConfig, maxSymbols: Int, trail: Double, maxHold: Int,
        progress: (String) -> Unit
    ): Pair<BtResult, BtResult> {
        progress("Chargement de la liste des cryptos…")
        val bases = candidates(cfg, maxSymbols)
        val slip = tc.simSlippagePct
        val hold = if (maxHold < 1) 1 else maxHold
        val pool = Executors.newFixedThreadPool(6)
        val all = ArrayList<BtTrade>()
        try {
            val futures = bases.map { b ->
                pool.submit(Callable<List<BtTrade>> {
                    try {
                        simulate(b, cfg, tc, trail, hold, slip)
                    } catch (e: Exception) {
                        emptyList()
                    }
                })
            }
            var done = 0
            for (f in futures) {
                all.addAll(f.get())
                done++
                if (done % 10 == 0) progress("Analyse $done/${bases.size}…")
            }
        } finally {
            pool.shutdownNow()
        }
        return Pair(agg(all) { it.pnlA }, agg(all) { it.pnlB })
    }
}
