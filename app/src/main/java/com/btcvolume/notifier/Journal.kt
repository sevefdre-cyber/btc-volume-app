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

// ---------------------------------------------------------------- backtest et balayage

data class BtResult(
    val n: Int,
    val wins: Int,
    val totalPct: Double,
    val avgPct: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val profitFactor: Double,
    val maxDdPct: Double,
    val tpN: Int = 0,
    val slN: Int = 0,
    val trailN: Int = 0,
    val timeN: Int = 0
)

class BtExit(val pct: Double, val idx: Int, val reason: String)

class SweepRow(
    val interval: String,
    val minR: Double,
    val maxR: Double,
    val tp: Double,
    val sl: Double,
    val trend: Boolean,
    val btc: Boolean,
    val train: BtResult,
    val valid: BtResult
)

object Backtester {
    const val WINDOW = 30
    private const val BTC_MS = 900_000L

    class Feat(
        val ratio: DoubleArray,
        val change: DoubleArray,
        val wick: DoubleArray,
        val chg24: DoubleArray,
        val atr: DoubleArray,
        val rangeRatio: DoubleArray,
        val trend: DoubleArray,
        val btcHas: BooleanArray,
        val btcChg: DoubleArray,
        val btcTrend: DoubleArray,
        val btcShock: DoubleArray
    )

    class Series(val cs: List<Candle>, val f: Feat)

    class BtcGrid(
        val idx: HashMap<Long, Int>,
        val chg: DoubleArray,
        val trend: DoubleArray,
        val shock: DoubleArray
    )

    fun candidates(cfg: Config, maxN: Int): List<String> {
        val arr = JSONArray(Binance.publicGet("REAL", "/api/v3/ticker/24hr", mapOf("type" to "MINI")))
        class T(val base: String, val qv: Double)
        val l = ArrayList<T>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val sym = t.getString("symbol")
            if (!sym.endsWith("USDT")) continue
            val base = sym.removeSuffix("USDT")
            if (base in cfg.excluded) continue
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val qv = t.optString("quoteVolume").toDoubleOrNull() ?: continue
            if (price <= 0.0 || qv <= 0.0) continue
            val hi = t.optString("highPrice").toDoubleOrNull() ?: price
            val lo = t.optString("lowPrice").toDoubleOrNull() ?: price
            if (Stables.isStable(base, price, hi, lo)) continue
            if (base in cfg.favorites || (price >= cfg.priceMin && price <= cfg.priceMax && qv >= cfg.minVol24h)) {
                l.add(T(base, qv))
            }
        }
        return l.sortedByDescending { it.qv }.take(maxN).map { it.base }
    }

    private fun pct(entry: Double, exit: Double): Double = (exit / entry - 1) * 100 - 0.2   // 0,2 % de frais aller-retour

    /** Le SL est supposé touché avant le TP lorsque les deux le sont dans la même bougie. */
    fun exitTrade(
        cs: List<Candle>, from: Int, entry: Double, tc: TradeConfig,
        trail: Double, maxHold: Int, slip: Double
    ): BtExit {
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
                return BtExit(pct(entry, px), j, if (activated) "SUIVEUR" else "SL")
            }
            if (trail <= 0.0) {
                if (c.high >= tp) return BtExit(pct(entry, tp), j, "TP")
            } else {
                peak = max(peak, c.high)
                if (peak >= tp) activated = true
                if (activated) stop = max(stop, peak * (1 - trail / 100))
            }
            j++
        }
        return BtExit(pct(entry, cs[last].close * (1 - slip / 100)), last, "DUREE")
    }

    /** État de Bitcoin sur la grille de bougies de 15 min (mêmes définitions que le scan en direct). */
    private fun btcGrid(): BtcGrid? {
        return try {
            val cs = Binance.candles("BTCUSDT", "15m", 1000)
            val n = cs.size
            val chg = DoubleArray(n)
            val trend = DoubleArray(n)
            val shock = DoubleArray(n)
            val idx = HashMap<Long, Int>()
            for (b in 30 until n) {
                idx[cs[b].time] = b
                chg[b] = if (cs[b - 4].close > 0) (cs[b].close - cs[b - 4].close) / cs[b - 4].close * 100 else 0.0
                var s30 = 0.0
                var s10 = 0.0
                for (j in b - 29..b) {
                    s30 += cs[j].close
                    if (j > b - 10) s10 += cs[j].close
                }
                s30 /= 30.0
                s10 /= 10.0
                trend[b] = if (s30 > 0) (s10 - s30) / s30 * 100 else 0.0
                var rs = 0.0
                for (j in b - 20 until b) rs += (cs[j].high - cs[j].low) / cs[j].close * 100
                val avgR = rs / 20.0
                val r = (cs[b].high - cs[b].low) / cs[b].close * 100
                shock[b] = if (avgR > 0) r / avgR else 0.0
            }
            BtcGrid(idx, chg, trend, shock)
        } catch (e: Exception) {
            null
        }
    }

    fun features(cs: List<Candle>, ms: Long, btc: BtcGrid?): Feat {
        val n = cs.size
        val k = (86_400_000L / ms).toInt()
        val ratio = DoubleArray(n)
        val change = DoubleArray(n)
        val wick = DoubleArray(n)
        val chg24 = DoubleArray(n)
        val atr = DoubleArray(n)
        val rangeRatio = DoubleArray(n)
        val trend = DoubleArray(n)
        val btcHas = BooleanArray(n)
        val btcChg = DoubleArray(n)
        val btcTrend = DoubleArray(n)
        val btcShock = DoubleArray(n)
        for (i in WINDOW until n) {
            var avg = 0.0
            for (j in i - WINDOW until i) avg += cs[j].vol
            avg /= WINDOW
            val c = cs[i]
            ratio[i] = if (avg > 0) c.vol / avg else 0.0
            change[i] = if (c.open > 0) (c.close - c.open) / c.open * 100 else 0.0
            val rng = c.high - c.low
            wick[i] = if (rng > 0) (c.high - max(c.open, c.close)) / rng * 100 else 0.0
            chg24[i] = if (i >= k && cs[i - k].close > 0) (c.close - cs[i - k].close) / cs[i - k].close * 100 else 0.0

            // volatilité (ATR en %) et tendance (MA10 / MA30) sur les bougies qui précèdent le signal
            var atrSum = 0.0
            for (j in i - 14 until i) {
                if (cs[j].close > 0) atrSum += (cs[j].high - cs[j].low) / cs[j].close * 100
            }
            val a = atrSum / 14.0
            atr[i] = a
            val rangePct = if (c.close > 0) rng / c.close * 100 else 0.0
            rangeRatio[i] = if (a > 0) rangePct / a else 0.0
            var s30 = 0.0
            var s10 = 0.0
            for (j in i - 30 until i) {
                s30 += cs[j].close
                if (j >= i - 10) s10 += cs[j].close
            }
            s30 /= 30.0
            s10 /= 10.0
            trend[i] = if (s30 > 0) (s10 - s30) / s30 * 100 else 0.0

            if (btc != null) {
                val closeT = c.time + ms
                val b = btc.idx[(closeT / BTC_MS) * BTC_MS - BTC_MS]
                if (b != null) {
                    btcHas[i] = true
                    btcChg[i] = btc.chg[b]
                    btcTrend[i] = btc.trend[b]
                    btcShock[i] = btc.shock[b]
                }
            }
        }
        return Feat(ratio, change, wick, chg24, atr, rangeRatio, trend, btcHas, btcChg, btcTrend, btcShock)
    }

    /** Trades d'une crypto pour un jeu de paramètres. Retourne (heure d'entrée, sortie). */
    fun trades(
        s: Series, minR: Double, maxR: Double, tc: TradeConfig,
        trail: Double, hold: Int, cfg: Config, slip: Double
    ): List<Pair<Long, BtExit>> {
        val cs = s.cs
        val f = s.f
        val n = cs.size
        val cooldown = cfg.cooldownMin * 60_000L
        val out = ArrayList<Pair<Long, BtExit>>()
        var blockedUntil = -1
        var lastSigTime = 0L
        var i = WINDOW
        while (i < n - 1) {
            val ratio = f.ratio[i]
            if (ratio >= minR && (maxR <= 0.0 || ratio <= maxR) && i > blockedUntil &&
                (cfg.mode == "VOLUME" || abs(f.change[i]) >= cfg.priceThreshold) &&
                f.wick[i] <= cfg.maxWickPct &&
                (cfg.max24hPct <= 0.0 || f.chg24[i] <= cfg.max24hPct) &&
                (cfg.atrMin <= 0.0 || f.atr[i] >= cfg.atrMin) &&
                (cfg.atrMax <= 0.0 || f.atr[i] <= cfg.atrMax) &&
                (cfg.rangeMinRatio <= 0.0 || f.rangeRatio[i] >= cfg.rangeMinRatio) &&
                (!cfg.trendFilter || f.trend[i] >= -cfg.trendTolPct) &&
                (!cfg.upCandleOnly || f.change[i] >= 0) &&
                (!tc.buyOnlyUp || f.change[i] >= 0) &&
                (!cfg.btcFilter || !f.btcHas[i] || !BtcRules.blocked(cfg, f.btcChg[i], f.btcTrend[i], f.btcShock[i])) &&
                (cs[i].time - lastSigTime) >= cooldown
            ) {
                lastSigTime = cs[i].time
                val entry = cs[i + 1].open * (1 + slip / 100)
                val ex = exitTrade(cs, i + 1, entry, tc, trail, hold, slip)
                out.add(Pair(cs[i + 1].time, ex))
                blockedUntil = ex.idx
            }
            i++
        }
        return out
    }

    fun aggregate(items: List<Pair<Long, BtExit>>): BtResult {
        if (items.isEmpty()) return BtResult(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val s = items.sortedBy { it.first }
        val v = s.map { it.second.pct }
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
            maxDdPct = dd,
            tpN = s.count { it.second.reason == "TP" },
            slN = s.count { it.second.reason == "SL" },
            trailN = s.count { it.second.reason == "SUIVEUR" },
            timeN = s.count { it.second.reason == "DUREE" }
        )
    }

    private fun load(bases: List<String>, interval: String): List<Series> {
        val ms = VolumeService.intervalMs(interval)
        val btc = btcGrid()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = bases.map { b ->
                pool.submit(Callable<Series?> {
                    try {
                        val cs = Binance.candles(b + "USDT", interval, 1000)
                        if (cs.size > WINDOW + 5) Series(cs, features(cs, ms, btc)) else null
                    } catch (e: Exception) {
                        null
                    }
                })
            }
            return futures.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Compare deux règles de sortie : A = TP fixe, B = stop suiveur après le TP. */
    fun run(
        cfg: Config, tc: TradeConfig, maxSymbols: Int, trail: Double, maxHold: Int,
        progress: (String) -> Unit
    ): Pair<BtResult, BtResult> {
        progress("Chargement de la liste des cryptos…")
        val bases = candidates(cfg, maxSymbols)
        progress("Téléchargement des bougies…")
        val series = load(bases, cfg.interval)
        val thr = if (cfg.volThreshold < 1.05) 1.05 else cfg.volThreshold
        val minR = max(thr, tc.autoMinRatio)
        val slip = tc.simSlippagePct
        val hold = max(1, maxHold)
        val a = ArrayList<Pair<Long, BtExit>>()
        val b = ArrayList<Pair<Long, BtExit>>()
        for (s in series) {
            a.addAll(trades(s, minR, cfg.volMax, tc, 0.0, hold, cfg, slip))
            b.addAll(trades(s, minR, cfg.volMax, tc, trail, hold, cfg, slip))
        }
        return Pair(aggregate(a), aggregate(b))
    }

    /**
     * Balayage : bougies, intervalle de volume, TP/SL, avec et sans filtre de tendance, avec et sans
     * filtre BTC. Les 60 % les plus anciens de la période servent à choisir, les 40 % les plus récents
     * à valider hors échantillon.
     */
    fun sweep(
        cfg: Config, tc: TradeConfig, maxSymbols: Int, maxHold: Int,
        progress: (String) -> Unit
    ): List<SweepRow> {
        progress("Chargement de la liste des cryptos…")
        val bases = candidates(cfg, maxSymbols)
        val slip = tc.simSlippagePct
        val hold = max(1, maxHold)
        val intervals = listOf("5m", "15m")
        val minRs = listOf(3.0, 5.0, 8.0, 12.0)
        val maxRs = listOf(0.0, 15.0, 25.0)
        val tpsl = listOf(Pair(3.0, 1.5), Pair(4.0, 2.0), Pair(6.0, 3.0), Pair(8.0, 4.0))
        val flags = listOf(false, true)
        val rows = ArrayList<SweepRow>()
        for (iv in intervals) {
            progress("Téléchargement des bougies $iv…")
            val series = load(bases, iv)
            if (series.isEmpty()) continue
            val tMin = series.minOf { it.cs.first().time }
            val tMax = series.maxOf { it.cs.last().time }
            val cut = tMin + ((tMax - tMin) * 0.6).toLong()
            var done = 0
            for (tf in flags) {
                for (bf in flags) {
                    val cfg2 = cfg.copy(trendFilter = tf, btcFilter = bf)
                    for (minR in minRs) {
                        for (maxR in maxRs) {
                            if (maxR > 0.0 && maxR <= minR) continue
                            for (pr in tpsl) {
                                val tc2 = tc.copy(tpPct = pr.first, slPct = pr.second)
                                val all = ArrayList<Pair<Long, BtExit>>()
                                for (s in series) all.addAll(trades(s, minR, maxR, tc2, 0.0, hold, cfg2, slip))
                                rows.add(
                                    SweepRow(
                                        iv, minR, maxR, pr.first, pr.second, tf, bf,
                                        aggregate(all.filter { it.first < cut }),
                                        aggregate(all.filter { it.first >= cut })
                                    )
                                )
                                done++
                                if (done % 16 == 0) progress("Bougies $iv : $done combinaisons testées…")
                            }
                        }
                    }
                }
            }
        }
        return rows
    }
}
