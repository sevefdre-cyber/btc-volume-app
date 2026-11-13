package com.btcvolume.notifier

import android.content.Context
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun pctOf(entry: Double, exit: Double): Double = (exit / entry - 1) * 100 - 0.2   // 0,2 % de frais aller-retour

// ---------------------------------------------------------------- 1 : classement (bougies de 5 min, 90 jours)

class RankBtRow(val topN: Int, val gain: Int, val train: BtResult, val valid: BtResult)

object RankBacktest {
    fun run(
        c: Context, cfg: Config, tc: TradeConfig, sc: StratConfig, nSym: Int,
        progress: (String) -> Unit
    ): List<RankBtRow> {
        val syms = HistoryStore.symbols(c).take(nSym)
        if (syms.isEmpty()) throw IOException("Aucun historique local : téléchargez-le d'abord.")
        val ms = 300_000L
        val now = System.currentTimeMillis()
        val gridStart = (now - 90L * 86_400_000L) / ms * ms
        val steps = 90 * 288
        val back24 = 288
        val w = max(1, sc.rankWindowMin / 5)

        // 1) prix de clôture alignés sur une grille commune
        val closes = ArrayList<FloatArray>()
        val keep = ArrayList<String>()
        for ((k, b) in syms.withIndex()) {
            val cs = HistoryStore.read(c, "5m", b) ?: continue
            if (cs.size < 600) continue
            val arr = FloatArray(steps) { Float.NaN }
            for (x in cs) {
                val i = ((x.time - gridStart) / ms).toInt()
                if (i in 0 until steps) arr[i] = x.close.toFloat()
            }
            closes.add(arr)
            keep.add(b)
            if (k % 25 == 0) progress("Lecture des historiques $k/${syms.size}…")
        }
        val n = closes.size
        if (n < 20) throw IOException("Pas assez de cryptos avec un historique de 5 min : téléchargez-le d'abord.")

        // 2) classement par variation sur 24 h glissantes, à chaque bougie
        progress("Calcul des classements ($n cryptos)…")
        val ranks = Array(n) { ShortArray(steps) }
        val v = FloatArray(n)
        val idx = ArrayList<Int>(n)
        for (t in back24 until steps) {
            idx.clear()
            for (s in 0 until n) {
                val c0 = closes[s][t - back24]
                val c1 = closes[s][t]
                if (c0 > 0f && c1 > 0f) {
                    v[s] = (c1 - c0) / c0 * 100f
                    idx.add(s)
                }
            }
            idx.sortByDescending { v[it] }
            for ((pos, s) in idx.withIndex()) ranks[s][t] = (pos + 1).toShort()
        }

        // 3) simulation des entrées et des sorties
        val combos = ArrayList<Pair<Int, Int>>()
        for (tn in listOf(5, 10, 20)) for (g in listOf(3, 5, 10)) combos.add(Pair(tn, g))
        val results = Array(combos.size) { ArrayList<Pair<Long, BtExit>>() }
        val slip = tc.simSlippagePct
        val holdSteps = max(1, sc.rankMaxHoldMin / 5)
        val cdSteps = max(1, cfg.cooldownMin / 5)
        val op = FloatArray(steps)
        val hi = FloatArray(steps)
        val lo = FloatArray(steps)
        for ((s, b) in keep.withIndex()) {
            val cs = HistoryStore.read(c, "5m", b) ?: continue
            java.util.Arrays.fill(op, Float.NaN)
            java.util.Arrays.fill(hi, Float.NaN)
            java.util.Arrays.fill(lo, Float.NaN)
            for (x in cs) {
                val i = ((x.time - gridStart) / ms).toInt()
                if (i in 0 until steps) {
                    op[i] = x.open.toFloat()
                    hi[i] = x.high.toFloat()
                    lo[i] = x.low.toFloat()
                }
            }
            val rk = ranks[s]
            val cl = closes[s]
            for ((ci, cb) in combos.withIndex()) {
                var blocked = -1
                var t = back24 + w
                while (t < steps - 2) {
                    val r = rk[t].toInt()
                    if (r in 1..cb.first && t > blocked && !op[t + 1].isNaN()) {
                        val prev = rk[t - w].toInt()
                        val c0 = cl[t - back24]
                        val vv = if (c0 > 0f) (cl[t] - c0) / c0 * 100f else Float.NaN
                        if (prev > 0 && prev - r >= cb.second && vv >= sc.rankVarMin && vv <= sc.rankVarMax) {
                            val entry = op[t + 1].toDouble() * (1 + slip / 100)
                            val tp = entry * (1 + sc.rankTp / 100)
                            val stop = entry * (1 - sc.rankSl / 100)
                            val last = min(steps - 1, t + holdSteps)
                            var best = r
                            var ex: BtExit? = null
                            var j = t + 1
                            while (j <= last) {
                                if (lo[j].isNaN()) {
                                    j++
                                    continue
                                }
                                if (lo[j] <= stop) {
                                    val px = (if (op[j] < stop) op[j].toDouble() else stop) * (1 - slip / 100)
                                    ex = BtExit(pctOf(entry, px), j, "SL")
                                    break
                                }
                                if (hi[j] >= tp) {
                                    ex = BtExit(pctOf(entry, tp), j, "TP")
                                    break
                                }
                                val rj = rk[j].toInt()
                                if (rj == 0) {
                                    j++
                                    continue
                                }
                                best = min(best, rj)
                                if (rj > sc.rankExitBelow || rj - best >= sc.rankExitDrop) {
                                    ex = BtExit(pctOf(entry, cl[j].toDouble() * (1 - slip / 100)), j, "RANG")
                                    break
                                }
                                j++
                            }
                            if (ex == null && !cl[last].isNaN()) {
                                ex = BtExit(pctOf(entry, cl[last].toDouble() * (1 - slip / 100)), last, "DUREE")
                            }
                            if (ex != null) {
                                results[ci].add(Pair(gridStart + (t + 1) * ms, ex))
                                blocked = ex.idx + cdSteps
                            }
                        }
                    }
                    t++
                }
            }
            if (s % 25 == 0) progress("Simulation $s/$n…")
        }
        val cut = gridStart + (0.6 * steps * ms).toLong()
        val rows = ArrayList<RankBtRow>()
        for ((ci, cb) in combos.withIndex()) {
            rows.add(
                RankBtRow(
                    cb.first, cb.second,
                    Backtester.aggregate(results[ci].filter { it.first < cut }),
                    Backtester.aggregate(results[ci].filter { it.first >= cut })
                )
            )
        }
        return rows.sortedByDescending { it.train.avgPct }
    }
}

// ---------------------------------------------------------------- 2 : RSI + MACD

class IndRow(val tf: String, val all: BtResult, val valid: BtResult)

object IndicatorBacktest {
    private fun simulate(cs: List<Candle>, sc: StratConfig, slip: Double): List<Pair<Long, BtExit>> {
        val n = cs.size
        val close = DoubleArray(n) { cs[it].close }
        val rsi = Ind.rsi(close, 14)
        val hist = Ind.macdHist(close)
        val out = ArrayList<Pair<Long, BtExit>>()
        var blocked = -1
        var i = 40
        while (i < n - 2) {
            if (i > blocked && !rsi[i].isNaN() && !rsi[i - 1].isNaN() &&
                hist[i - 1] <= 0 && hist[i] > 0 &&
                rsi[i] >= sc.rsiMin && rsi[i] <= sc.rsiMax && rsi[i] > rsi[i - 1]
            ) {
                val entry = cs[i + 1].open * (1 + slip / 100)
                val tp = entry * (1 + sc.rsiTp / 100)
                val stop = entry * (1 - sc.rsiSl / 100)
                val last = min(n - 1, i + 48)
                var ex: BtExit? = null
                var j = i + 1
                while (j <= last) {
                    val k = cs[j]
                    if (k.low <= stop) {
                        ex = BtExit(pctOf(entry, (if (k.open < stop) k.open else stop) * (1 - slip / 100)), j, "SL")
                        break
                    }
                    if (k.high >= tp) {
                        ex = BtExit(pctOf(entry, tp), j, "TP")
                        break
                    }
                    if (!rsi[j].isNaN() && (rsi[j] >= sc.rsiExit || (hist[j - 1] > 0 && hist[j] <= 0))) {
                        ex = BtExit(pctOf(entry, k.close * (1 - slip / 100)), j, "INDIC")
                        break
                    }
                    j++
                }
                if (ex == null) ex = BtExit(pctOf(entry, cs[last].close * (1 - slip / 100)), last, "DUREE")
                out.add(Pair(cs[i + 1].time, ex))
                blocked = ex.idx
            }
            i++
        }
        return out
    }

    fun run(
        c: Context, tc: TradeConfig, sc: StratConfig, nSym: Int,
        progress: (String) -> Unit
    ): List<IndRow> {
        val syms = HistoryStore.symbols(c).take(nSym)
        if (syms.isEmpty()) throw IOException("Aucun historique local : téléchargez-le d'abord.")
        val slip = tc.simSlippagePct
        val out = ArrayList<IndRow>()
        for (tf in listOf("5m", "15m", "1h")) {
            progress("RSI + MACD en bougies de $tf…")
            val threads = 4
            val chunks = (0 until threads).map { t -> syms.filterIndexed { i, _ -> i % threads == t } }
            val pool = Executors.newFixedThreadPool(threads)
            val parts = try {
                chunks.map { chunk ->
                    pool.submit(Callable<List<Pair<Long, BtExit>>> {
                        val res = ArrayList<Pair<Long, BtExit>>()
                        for (b in chunk) {
                            val cs = HistoryStore.read(c, tf, b) ?: continue
                            if (cs.size < 120) continue
                            res.addAll(simulate(cs, sc, slip))
                        }
                        res
                    })
                }.map { it.get() }
            } finally {
                pool.shutdownNow()
            }
            val all = parts.flatten()
            if (all.isEmpty()) {
                out.add(IndRow(tf, Backtester.aggregate(all), Backtester.aggregate(all)))
            } else {
                val tMin = all.minOf { it.first }
                val tMax = all.maxOf { it.first }
                val cut = tMin + ((tMax - tMin) * 0.6).toLong()
                out.add(IndRow(tf, Backtester.aggregate(all), Backtester.aggregate(all.filter { it.first >= cut })))
            }
        }
        return out
    }
}

// ---------------------------------------------------------------- 3 : ouverture de Wall Street

class WallSlot(val label: String, val avgAbs: Double, val avgRatio: Double)

class WallResult(
    val slots: List<WallSlot>,
    val chosenRank: Int,
    val inWin: BtResult,
    val outWin: BtResult,
    val symbols: Int
)

object WallStudy {
    fun run(
        c: Context, cfg: Config, tc: TradeConfig, sc: StratConfig, nSym: Int,
        progress: (String) -> Unit
    ): WallResult {
        val syms = HistoryStore.symbols(c).take(nSym)
        if (syms.isEmpty()) throw IOException("Aucun historique local : téléchargez-le d'abord.")
        val tz = TimeZone.getDefault()
        val ms = 300_000L
        val sumAbs = DoubleArray(48)
        val sumRatio = DoubleArray(48)
        val cnt = LongArray(48)
        val inT = ArrayList<Pair<Long, BtExit>>()
        val outT = ArrayList<Pair<Long, BtExit>>()
        val cfgW = cfg.copy(
            explosionMin = sc.wsMinMovePct, trendFilter = false, btcFilter = false,
            upCandleOnly = true, maxWickPct = 100.0, max24hPct = 0.0
        )
        val tcW = tc.copy(tpPct = sc.wsTp, slPct = sc.wsSl)
        val hold = max(1, sc.wsHoldMin / 5)
        val start = sc.wsHour * 60 + sc.wsMinute
        var used = 0
        for ((k, b) in syms.withIndex()) {
            val cs = HistoryStore.read(c, "5m", b) ?: continue
            if (cs.size < 600) continue
            used++
            val f = Backtester.features(cs, ms, null)
            for (i in Backtester.WINDOW until cs.size) {
                val t = cs[i].time + tz.getOffset(cs[i].time)          // heure locale
                val dow = (((t / 86_400_000L) + 4) % 7).toInt()        // 0 = dimanche
                if (dow in 1..5) {
                    val slot = (((t / 60_000L) % 1440) / 30).toInt()
                    sumAbs[slot] += abs(f.change[i])
                    sumRatio[slot] += f.ratio[i]
                    cnt[slot]++
                }
            }
            val series = Backtester.Series(cs, f)
            for (tr in Backtester.trades(series, 0.0, 0.0, tcW, 0.0, hold, cfgW, tc.simSlippagePct)) {
                val lt = tr.first + tz.getOffset(tr.first)
                val dow = (((lt / 86_400_000L) + 4) % 7).toInt()
                val mod = ((lt / 60_000L) % 1440).toInt()
                val inWin = (!sc.wsWeekdays || dow in 1..5) && mod >= start && mod < start + sc.wsDurationMin
                if (inWin) inT.add(tr) else outT.add(tr)
            }
            if (k % 25 == 0) progress("Analyse $k/${syms.size}…")
        }
        val all = (0 until 48).map { s ->
            val h = s / 2
            val m = if (s % 2 == 0) 0 else 30
            WallSlot(
                String.format(java.util.Locale.US, "%02d:%02d", h, m),
                if (cnt[s] > 0) sumAbs[s] / cnt[s] else 0.0,
                if (cnt[s] > 0) sumRatio[s] / cnt[s] else 0.0
            )
        }
        val sorted = all.sortedByDescending { it.avgAbs }
        val chosenSlot = all[(start / 30).coerceIn(0, 47)]
        val chosenRank = sorted.indexOf(chosenSlot) + 1
        return WallResult(sorted.take(6), chosenRank, Backtester.aggregate(inT), Backtester.aggregate(outT), used)
    }
}
