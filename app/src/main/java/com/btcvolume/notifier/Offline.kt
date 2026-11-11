package com.btcvolume.notifier

import android.content.Context
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class Combo(
    val interval: String,
    val minR: Double,
    val maxR: Double,
    val tp: Double,
    val sl: Double,
    val trend: Boolean,
    val btc: Boolean,
    val minChg: Double
)

class Agg(
    val n: Int,
    val wins: Int,
    val sum: Double,
    val sumWin: Double,
    val sumLoss: Double,
    val tp: Int,
    val sl: Int,
    val dur: Int
) {
    val avg: Double get() = if (n == 0) 0.0 else sum / n
    val pf: Double get() = if (sumLoss < 0) sumWin / -sumLoss else if (sumWin > 0) Double.POSITIVE_INFINITY else 0.0
    val winPct: Int get() = if (n == 0) 0 else wins * 100 / n

    fun plus(o: Agg) = Agg(n + o.n, wins + o.wins, sum + o.sum, sumWin + o.sumWin, sumLoss + o.sumLoss, tp + o.tp, sl + o.sl, dur + o.dur)
}

/** Résultats d'une combinaison, répartis en 12 tranches de temps égales. */
class Acc {
    private val b = OfflineBacktest.B
    val n = IntArray(b)
    val wins = IntArray(b)
    val sum = DoubleArray(b)
    val sumWin = DoubleArray(b)
    val sumLoss = DoubleArray(b)
    val tp = IntArray(b)
    val sl = IntArray(b)
    val dur = IntArray(b)

    fun add(i: Int, ex: BtExit) {
        n[i]++
        sum[i] += ex.pct
        if (ex.pct > 0) {
            wins[i]++
            sumWin[i] += ex.pct
        } else {
            sumLoss[i] += ex.pct
        }
        when (ex.reason) {
            "TP" -> tp[i]++
            "SL", "SUIVEUR" -> sl[i]++
            else -> dur[i]++
        }
    }

    fun merge(o: Acc) {
        for (i in 0 until b) {
            n[i] += o.n[i]
            wins[i] += o.wins[i]
            sum[i] += o.sum[i]
            sumWin[i] += o.sumWin[i]
            sumLoss[i] += o.sumLoss[i]
            tp[i] += o.tp[i]
            sl[i] += o.sl[i]
            dur[i] += o.dur[i]
        }
    }

    fun range(from: Int, to: Int): Agg {
        var nn = 0
        var w = 0
        var s = 0.0
        var sw = 0.0
        var sl2 = 0.0
        var t = 0
        var l = 0
        var d = 0
        for (i in from until to) {
            nn += n[i]
            w += wins[i]
            s += sum[i]
            sw += sumWin[i]
            sl2 += sumLoss[i]
            t += tp[i]
            l += sl[i]
            d += dur[i]
        }
        return Agg(nn, w, s, sw, sl2, t, l, d)
    }
}

class OffFold(val chosen: Combo?, val train: Agg?, val valid: Agg?)
class OffTop(val combo: Combo, val all: Agg, val posBuckets: Int, val usedBuckets: Int)
class OffResult(
    val folds: List<OffFold>,
    val pooled: Agg,
    val tops: List<OffTop>,
    val effect: DoubleArray,      // tendance avec / sans, BTC avec / sans (rendement moyen par trade)
    val combos: Int,
    val symbols: Int,
    val note: String
)

object OfflineBacktest {
    const val B = 12
    private const val MIN_TRAIN = 100
    private val TPSL = listOf(Pair(3.0, 1.5), Pair(4.0, 2.0), Pair(6.0, 3.0), Pair(8.0, 4.0))

    fun volumeCombos(): List<Combo> {
        val out = ArrayList<Combo>()
        for (iv in listOf("5m", "15m", "1h")) {
            for (tf in listOf(false, true)) {
                for (bf in listOf(false, true)) {
                    for (minR in listOf(3.0, 5.0, 8.0, 12.0)) {
                        for (maxR in listOf(0.0, 15.0, 25.0)) {
                            if (maxR > 0.0 && maxR <= minR) continue
                            for (pr in TPSL) out.add(Combo(iv, minR, maxR, pr.first, pr.second, tf, bf, 0.0))
                        }
                    }
                }
            }
        }
        return out
    }

    fun explosionCombos(): List<Combo> {
        val chgs = mapOf(
            "5m" to listOf(3.0, 5.0, 8.0),
            "15m" to listOf(3.0, 5.0, 8.0),
            "1h" to listOf(4.0, 6.0, 10.0)
        )
        val out = ArrayList<Combo>()
        for (iv in listOf("5m", "15m", "1h")) {
            for (tf in listOf(false, true)) {
                for (bf in listOf(false, true)) {
                    for (chg in chgs[iv] ?: emptyList()) {
                        for (minR in listOf(1.5, 3.0, 6.0)) {
                            for (pr in TPSL) out.add(Combo(iv, minR, 0.0, pr.first, pr.second, tf, bf, chg))
                        }
                    }
                }
            }
        }
        return out
    }

    fun run(
        c: Context, cfg: Config, tc: TradeConfig, combos: List<Combo>, maxSymbols: Int,
        hold: Int, depth5mDays: Int, progress: (String) -> Unit
    ): OffResult {
        val symbols = HistoryStore.symbols(c).take(maxSymbols)
        if (symbols.isEmpty()) throw IOException("Aucun historique local : téléchargez-le d'abord.")
        progress("Chargement de Bitcoin…")
        val btc15 = HistoryStore.read(c, "15m", "BTC")
        val grid: Backtester.BtcGrid? = if (btc15 != null) Backtester.btcGrid(btc15) else null
        val note = if (grid == null) " · BTC 15 min absent : filtre BTC ignoré" else ""

        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val spanDays = mapOf("5m" to depth5mDays, "15m" to 365, "1h" to 365)
        val slip = tc.simSlippagePct
        val hold2 = max(1, hold)
        val cfgs = combos.map { cfg.copy(trendFilter = it.trend, btcFilter = it.btc, explosionMin = it.minChg) }
        val tcs = combos.map { tc.copy(tpPct = it.tp, slPct = it.sl) }
        val ivs = combos.map { it.interval }.distinct()
        val byIv = ivs.associateWith { iv -> combos.indices.filter { combos[it].interval == iv } }

        val threads = 4
        val chunks = ArrayList<List<String>>()
        for (t in 0 until threads) chunks.add(symbols.filterIndexed { i, _ -> i % threads == t })
        val doneCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val accs = try {
            val futures = chunks.map { chunk ->
                pool.submit(Callable<Array<Acc>> {
                    val acc = Array(combos.size) { Acc() }
                    for (base in chunk) {
                        for (iv in ivs) {
                            val cs = HistoryStore.read(c, iv, base) ?: continue
                            if (cs.size < 100) continue
                            val ms = VolumeService.intervalMs(iv)
                            val series = Backtester.Series(cs, Backtester.features(cs, ms, grid))
                            val spanLen = (spanDays[iv] ?: 365) * dayMs
                            val spanStart = now - spanLen
                            for (k in byIv[iv] ?: emptyList()) {
                                val cb = combos[k]
                                val list = Backtester.trades(series, cb.minR, cb.maxR, tcs[k], 0.0, hold2, cfgs[k], slip)
                                for (tr in list) {
                                    if (tr.first < spanStart) continue
                                    val bi = (((tr.first - spanStart).toDouble() / spanLen) * B).toInt().coerceIn(0, B - 1)
                                    acc[k].add(bi, tr.second)
                                }
                            }
                        }
                        val d = doneCount.incrementAndGet()
                        if (d % 10 == 0) progress("Analyse : $d/${symbols.size} cryptos…")
                    }
                    acc
                })
            }
            futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val total = Array(combos.size) { Acc() }
        for (a in accs) for (k in total.indices) total[k].merge(a[k])

        // walk-forward : on choisit la meilleure combinaison sur le passé, puis on la juge sur la période suivante
        val trainEnds = listOf(6, 8, 10)
        val validEnds = listOf(8, 10, 12)
        val folds = ArrayList<OffFold>()
        var pooled = Agg(0, 0, 0.0, 0.0, 0.0, 0, 0, 0)
        for (f in 0 until 3) {
            var best = -1
            var bestAvg = -1e9
            for (k in combos.indices) {
                val tr = total[k].range(0, trainEnds[f])
                if (tr.n >= MIN_TRAIN && tr.avg > bestAvg) {
                    bestAvg = tr.avg
                    best = k
                }
            }
            if (best < 0) {
                folds.add(OffFold(null, null, null))
            } else {
                val tr = total[best].range(0, trainEnds[f])
                val va = total[best].range(trainEnds[f], validEnds[f])
                folds.add(OffFold(combos[best], tr, va))
                pooled = pooled.plus(va)
            }
        }

        val tops = combos.indices.map { k ->
            val all = total[k].range(0, B)
            var pos = 0
            var used = 0
            for (b in 0 until B) {
                val x = total[k].range(b, b + 1)
                if (x.n >= 10) {
                    used++
                    if (x.avg > 0) pos++
                }
            }
            OffTop(combos[k], all, pos, used)
        }.filter { it.all.n >= 200 }.sortedByDescending { it.all.avg }.take(5)

        fun meanAvg(sel: (Combo) -> Boolean): Double {
            val l = combos.indices.filter { sel(combos[it]) && total[it].range(0, B).n >= 100 }
            return if (l.isEmpty()) Double.NaN else l.map { total[it].range(0, B).avg }.average()
        }
        val effect = doubleArrayOf(
            meanAvg { it.trend }, meanAvg { !it.trend },
            meanAvg { it.btc }, meanAvg { !it.btc }
        )
        return OffResult(folds, pooled, tops, effect, combos.size, symbols.size, note)
    }
}
