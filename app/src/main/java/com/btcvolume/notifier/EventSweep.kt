package com.btcvolume.notifier

import android.content.Context
import java.io.IOException
import kotlin.math.ln
import kotlin.math.sqrt

class EvRow(
    val interval: String,
    val minChg: Double,
    val minRatio: Double,
    val maxWick: Double,
    val max24: Double,
    val trend: Boolean,
    val btc: Int,          // 0 = aucun, 1 = doux, 2 = strict
    val tp: Double,
    val sl: Double,
    val hold: Int,         // durée maximale en bougies
    val all: Agg,
    val t: Double          // statistique t de la moyenne (mesure de confiance)
)

class EvFold(val row: EvRow?, val train: Agg?, val valid: Agg?)

class EvResult(
    val top: List<EvRow>,
    val folds: List<EvFold>,
    val pooled: Agg,
    val total: Int,
    val threshold: Double,   // seuil de hasard : t attendu pour le meilleur de N essais sans aucun avantage réel
    val above: Int,
    val events: Int
)

/**
 * Balayage par événements : on repère une seule fois les hausses brutales de chaque crypto, on calcule leurs
 * sorties pour toutes les paires TP / SL / durée, puis on évalue des dizaines de milliers de combinaisons de
 * filtres sur ces événements. Beaucoup plus rapide que de rejouer tout l'historique à chaque combinaison.
 */
object EventSweep {
    private const val B = 12
    private const val MIN_TRAIN = 60
    private val MIN_CHG = doubleArrayOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
    private val MIN_RATIO = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 5.0)
    private val MAX_WICK = doubleArrayOf(25.0, 50.0, 100.0)
    private val MAX_24 = doubleArrayOf(25.0, 0.0)
    private val TPSL = arrayOf(
        doubleArrayOf(2.0, 1.0), doubleArrayOf(3.0, 1.5), doubleArrayOf(4.0, 2.0), doubleArrayOf(6.0, 3.0),
        doubleArrayOf(8.0, 4.0), doubleArrayOf(10.0, 5.0), doubleArrayOf(12.0, 4.0), doubleArrayOf(15.0, 5.0)
    )
    private val HOLDS = intArrayOf(12, 36, 72)

    private class Grid(
        val n: Int,
        val ratio: DoubleArray,
        val wick: DoubleArray,
        val max24: DoubleArray,
        val trend: IntArray,
        val btc: IntArray,
        val exit: IntArray,
        val chgIdx: IntArray,
        val groupEnd: IntArray
    )

    private fun buildGrid(): Grid {
        val c = MIN_CHG.size * MIN_RATIO.size * MAX_WICK.size * MAX_24.size * 2 * 3 * TPSL.size * HOLDS.size
        val ratio = DoubleArray(c)
        val wick = DoubleArray(c)
        val m24 = DoubleArray(c)
        val trend = IntArray(c)
        val btc = IntArray(c)
        val exit = IntArray(c)
        val chgIdx = IntArray(c)
        val groupEnd = IntArray(MIN_CHG.size)
        var k = 0
        for (g in MIN_CHG.indices) {
            for (r in MIN_RATIO) {
                for (w in MAX_WICK) {
                    for (m in MAX_24) {
                        for (t in 0..1) {
                            for (b in 0..2) {
                                for (e in 0 until TPSL.size * HOLDS.size) {
                                    ratio[k] = r
                                    wick[k] = w
                                    m24[k] = m
                                    trend[k] = t
                                    btc[k] = b
                                    exit[k] = e
                                    chgIdx[k] = g
                                    k++
                                }
                            }
                        }
                    }
                }
            }
            groupEnd[g] = k
        }
        return Grid(c, ratio, wick, m24, trend, btc, exit, chgIdx, groupEnd)
    }

    private class Rng(val n: Int, val wins: Int, val sum: Double, val sumSq: Double, val sumWin: Double, val sumLoss: Double) {
        val avg: Double get() = if (n == 0) 0.0 else sum / n
        fun agg(): Agg = Agg(n, wins, sum, sumWin, sumLoss, 0, 0, 0)
        fun t(): Double {
            if (n < 2) return 0.0
            val m = sum / n
            val v = (sumSq - n * m * m) / (n - 1)
            return if (v <= 0.0) 0.0 else m / sqrt(v / n)
        }
    }

    private class Acc2(c: Int) {
        val n = IntArray(c * B)
        val wins = IntArray(c * B)
        val sum = DoubleArray(c * B)
        val sumSq = DoubleArray(c * B)
        val sumWin = DoubleArray(c * B)
        val sumLoss = DoubleArray(c * B)

        fun rng(k: Int, from: Int, to: Int): Rng {
            var nn = 0
            var w = 0
            var s = 0.0
            var q = 0.0
            var sw = 0.0
            var sl = 0.0
            for (b in from until to) {
                val i = k * B + b
                nn += n[i]
                w += wins[i]
                s += sum[i]
                q += sumSq[i]
                sw += sumWin[i]
                sl += sumLoss[i]
            }
            return Rng(nn, w, s, q, sw, sl)
        }
    }

    private fun rowOf(iv: String, g: Grid, k: Int, acc: Acc2): EvRow {
        val r = acc.rng(k, 0, B)
        val p = g.exit[k] / HOLDS.size
        val h = g.exit[k] % HOLDS.size
        return EvRow(
            iv, MIN_CHG[g.chgIdx[k]], g.ratio[k], g.wick[k], g.max24[k], g.trend[k] == 1, g.btc[k],
            TPSL[p][0], TPSL[p][1], HOLDS[h], r.agg(), r.t()
        )
    }

    fun run(c: Context, cfg: Config, tc: TradeConfig, nSym: Int, progress: (String) -> Unit): EvResult {
        val syms = HistoryStore.symbols(c).take(nSym)
        if (syms.isEmpty()) throw IOException("Aucun historique local : téléchargez-le d'abord.")
        progress("Chargement de Bitcoin…")
        val btc15 = HistoryStore.read(c, "15m", "BTC")
        val btcGrid = if (btc15 != null) Backtester.btcGrid(btc15) else null
        val g = buildGrid()
        val now = System.currentTimeMillis()
        val cfgStrict = cfg.copy(btcFilter = true, btcTrendTol = 0.0)
        val cfgSoft = cfg.copy(btcFilter = true, btcTrendTol = 0.5)
        val tcs = TPSL.map { tc.copy(tpPct = it[0], slPct = it[1]) }
        val cooldownMs = cfg.cooldownMin * 60_000L
        val trendTol = cfg.trendTolPct
        val nExit = TPSL.size * HOLDS.size
        val totalCombos = 3 * g.n
        val threshold = sqrt(2.0 * ln(totalCombos.toDouble()))
        val trainEnds = intArrayOf(6, 8, 10)
        val validEnds = intArrayOf(8, 10, 12)

        val tops = ArrayList<EvRow>()
        val foldBest = arrayOfNulls<EvFold>(3)
        val foldBestAvg = DoubleArray(3) { -1e9 }
        var above = 0
        var events = 0

        val exitPct = DoubleArray(nExit)
        val exitIdx = IntArray(nExit)

        for (iv in listOf("5m", "15m", "1h")) {
            val ms = VolumeService.intervalMs(iv)
            val spanLen = (if (iv == "5m") 90L else 365L) * 86_400_000L
            val spanStart = now - spanLen
            val acc = Acc2(g.n)
            for ((si, base) in syms.withIndex()) {
                val cs = HistoryStore.read(c, iv, base) ?: continue
                if (cs.size < 200) continue
                val f = Backtester.features(cs, ms, btcGrid)
                val slip = if (tc.costMode == "TIER") CostModel.forSeries(cs, ms) else tc.simSlippagePct
                val blocked = IntArray(g.n) { -1 }
                val lastSig = LongArray(g.n)
                val n = cs.size
                for (i in Backtester.WINDOW until n - 1) {
                    val ch = f.change[i]
                    if (ch < MIN_CHG[0] || f.ratio[i] < MIN_RATIO[0] || cs[i].time < spanStart) continue
                    events++
                    val entry = cs[i + 1].open * (1 + slip / 100)
                    for (p in tcs.indices) {
                        for (h in HOLDS.indices) {
                            val ex = Backtester.exitTrade(cs, i + 1, entry, tcs[p], 0.0, HOLDS[h], slip)
                            val k = p * HOLDS.size + h
                            exitPct[k] = ex.pct
                            exitIdx[k] = ex.idx
                        }
                    }
                    var gi = 0
                    while (gi + 1 < MIN_CHG.size && MIN_CHG[gi + 1] <= ch) gi++
                    val end = g.groupEnd[gi]
                    val hasB = f.btcHas[i]
                    val softOk = !hasB || !BtcRules.blocked(cfgSoft, f.btcChg[i], f.btcTrend[i], f.btcShock[i])
                    val strictOk = !hasB || !BtcRules.blocked(cfgStrict, f.btcChg[i], f.btcTrend[i], f.btcShock[i])
                    val trendOk = f.trend[i] >= -trendTol
                    val ratio = f.ratio[i]
                    val wick = f.wick[i]
                    val c24 = f.chg24[i]
                    val time = cs[i].time
                    val bi = (((cs[i + 1].time - spanStart).toDouble() / spanLen) * B).toInt().coerceIn(0, B - 1)
                    for (k in 0 until end) {
                        if (ratio < g.ratio[k]) continue
                        if (wick > g.wick[k]) continue
                        if (g.max24[k] > 0.0 && c24 > g.max24[k]) continue
                        if (g.trend[k] == 1 && !trendOk) continue
                        val bk = g.btc[k]
                        if (bk == 1 && !softOk) continue
                        if (bk == 2 && !strictOk) continue
                        if (i <= blocked[k]) continue
                        if (time - lastSig[k] < cooldownMs) continue
                        lastSig[k] = time
                        val e = g.exit[k]
                        blocked[k] = exitIdx[e]
                        val pct = exitPct[e]
                        val idx = k * B + bi
                        acc.n[idx]++
                        acc.sum[idx] += pct
                        acc.sumSq[idx] += pct * pct
                        if (pct > 0) {
                            acc.wins[idx]++
                            acc.sumWin[idx] += pct
                        } else {
                            acc.sumLoss[idx] += pct
                        }
                    }
                }
                if (si % 10 == 0) progress("Bougies $iv : ${si + 1}/${syms.size} cryptos, $events événements…")
            }

            progress("Bougies $iv : sélection des meilleures combinaisons…")
            // walk-forward : meilleure combinaison sur le passé, jugée sur la période suivante
            for (fi in 0 until 3) {
                var best = -1
                var bestAvg = -1e9
                for (k in 0 until g.n) {
                    val r = acc.rng(k, 0, trainEnds[fi])
                    if (r.n >= MIN_TRAIN && r.avg > bestAvg) {
                        bestAvg = r.avg
                        best = k
                    }
                }
                if (best >= 0 && bestAvg > foldBestAvg[fi]) {
                    foldBestAvg[fi] = bestAvg
                    foldBest[fi] = EvFold(
                        rowOf(iv, g, best, acc),
                        acc.rng(best, 0, trainEnds[fi]).agg(),
                        acc.rng(best, trainEnds[fi], validEnds[fi]).agg()
                    )
                }
            }
            // meilleures combinaisons sur toute la période, mesurées par la statistique t
            val scored = ArrayList<Pair<Int, Double>>()
            for (k in 0 until g.n) {
                val r = acc.rng(k, 0, B)
                if (r.n >= 100) scored.add(Pair(k, r.t()))
            }
            scored.sortByDescending { it.second }
            above += scored.count { it.second >= threshold }
            for (p in scored.take(10)) tops.add(rowOf(iv, g, p.first, acc))
        }

        val folds = foldBest.map { it ?: EvFold(null, null, null) }
        var pooled = Agg(0, 0, 0.0, 0.0, 0.0, 0, 0, 0)
        for (fo in folds) {
            val v = fo.valid
            if (v != null) pooled = pooled.plus(v)
        }
        return EvResult(tops.sortedByDescending { it.t }.take(10), folds, pooled, totalCombos, threshold, above, events)
    }
}
