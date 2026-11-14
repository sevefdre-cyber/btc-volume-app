package com.btcvolume.notifier

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class StratConfig(
    // 1 : montée dans le classement
    val rankOn: Boolean = false,
    val rankTopN: Int = 10,
    val rankGain: Int = 5,
    val rankWindowMin: Int = 15,
    val rankVarMin: Double = 2.0,
    val rankVarMax: Double = 25.0,
    val rankExitBelow: Int = 30,
    val rankExitDrop: Int = 8,
    val rankMaxHoldMin: Int = 360,
    val rankTp: Double = 15.0,
    val rankSl: Double = 3.0,
    // 2 : RSI + MACD
    val rsiOn: Boolean = false,
    val rsiTf: String = "15m",
    val rsiMin: Double = 40.0,
    val rsiMax: Double = 65.0,
    val rsiExit: Double = 75.0,
    val rsiUniverse: Int = 60,
    val rsiTp: Double = 4.0,
    val rsiSl: Double = 2.0,
    // 3 : ouverture de Wall Street (heure locale du téléphone)
    val wsOn: Boolean = false,
    val wsHour: Int = 15,
    val wsMinute: Int = 0,
    val wsDurationMin: Int = 30,
    val wsLookbackMin: Int = 5,
    val wsMinMovePct: Double = 1.5,
    val wsMaxEntries: Int = 3,
    val wsHoldMin: Int = 120,
    val wsWeekdays: Boolean = true,
    val wsTp: Double = 3.0,
    val wsSl: Double = 1.5
)

object StratPrefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("strat", Context.MODE_PRIVATE)
    private fun SharedPreferences.d(k: String, def: Double): Double = getString(k, null)?.toDoubleOrNull() ?: def

    fun load(c: Context): StratConfig {
        val p = sp(c)
        val x = StratConfig()
        return StratConfig(
            rankOn = p.getBoolean("rankOn", x.rankOn),
            rankTopN = p.getInt("rankTopN", x.rankTopN),
            rankGain = p.getInt("rankGain", x.rankGain),
            rankWindowMin = p.getInt("rankWindowMin", x.rankWindowMin),
            rankVarMin = p.d("rankVarMin", x.rankVarMin),
            rankVarMax = p.d("rankVarMax", x.rankVarMax),
            rankExitBelow = p.getInt("rankExitBelow", x.rankExitBelow),
            rankExitDrop = p.getInt("rankExitDrop", x.rankExitDrop),
            rankMaxHoldMin = p.getInt("rankMaxHoldMin", x.rankMaxHoldMin),
            rankTp = p.d("rankTp", x.rankTp),
            rankSl = p.d("rankSl", x.rankSl),
            rsiOn = p.getBoolean("rsiOn", x.rsiOn),
            rsiTf = p.getString("rsiTf", x.rsiTf) ?: x.rsiTf,
            rsiMin = p.d("rsiMin", x.rsiMin),
            rsiMax = p.d("rsiMax", x.rsiMax),
            rsiExit = p.d("rsiExit", x.rsiExit),
            rsiUniverse = p.getInt("rsiUniverse", x.rsiUniverse),
            rsiTp = p.d("rsiTp", x.rsiTp),
            rsiSl = p.d("rsiSl", x.rsiSl),
            wsOn = p.getBoolean("wsOn", x.wsOn),
            wsHour = p.getInt("wsHour", x.wsHour),
            wsMinute = p.getInt("wsMinute", x.wsMinute),
            wsDurationMin = p.getInt("wsDurationMin", x.wsDurationMin),
            wsLookbackMin = p.getInt("wsLookbackMin", x.wsLookbackMin),
            wsMinMovePct = p.d("wsMinMovePct", x.wsMinMovePct),
            wsMaxEntries = p.getInt("wsMaxEntries", x.wsMaxEntries),
            wsHoldMin = p.getInt("wsHoldMin", x.wsHoldMin),
            wsWeekdays = p.getBoolean("wsWeekdays", x.wsWeekdays),
            wsTp = p.d("wsTp", x.wsTp),
            wsSl = p.d("wsSl", x.wsSl)
        )
    }

    fun save(c: Context, s: StratConfig) {
        sp(c).edit()
            .putBoolean("rankOn", s.rankOn).putInt("rankTopN", s.rankTopN).putInt("rankGain", s.rankGain)
            .putInt("rankWindowMin", s.rankWindowMin).putString("rankVarMin", s.rankVarMin.toString())
            .putString("rankVarMax", s.rankVarMax.toString()).putInt("rankExitBelow", s.rankExitBelow)
            .putInt("rankExitDrop", s.rankExitDrop).putInt("rankMaxHoldMin", s.rankMaxHoldMin)
            .putString("rankTp", s.rankTp.toString()).putString("rankSl", s.rankSl.toString())
            .putBoolean("rsiOn", s.rsiOn).putString("rsiTf", s.rsiTf).putString("rsiMin", s.rsiMin.toString())
            .putString("rsiMax", s.rsiMax.toString()).putString("rsiExit", s.rsiExit.toString())
            .putInt("rsiUniverse", s.rsiUniverse).putString("rsiTp", s.rsiTp.toString())
            .putString("rsiSl", s.rsiSl.toString())
            .putBoolean("wsOn", s.wsOn).putInt("wsHour", s.wsHour).putInt("wsMinute", s.wsMinute)
            .putInt("wsDurationMin", s.wsDurationMin).putInt("wsLookbackMin", s.wsLookbackMin)
            .putString("wsMinMovePct", s.wsMinMovePct.toString()).putInt("wsMaxEntries", s.wsMaxEntries)
            .putInt("wsHoldMin", s.wsHoldMin).putBoolean("wsWeekdays", s.wsWeekdays)
            .putString("wsTp", s.wsTp.toString()).putString("wsSl", s.wsSl.toString())
            .apply()
    }
}

/** Indicateurs techniques classiques. */
object Ind {
    fun ema(x: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        val k = 2.0 / (p + 1)
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = x[i] * k + out[i - 1] * (1 - k)
        return out
    }

    /** RSI de Wilder. Les premières valeurs (avant la période) valent NaN. */
    fun rsi(x: DoubleArray, p: Int = 14): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.size <= p) return out
        var g = 0.0
        var l = 0.0
        for (i in 1..p) {
            val d = x[i] - x[i - 1]
            if (d > 0) g += d else l -= d
        }
        var ag = g / p
        var al = l / p
        out[p] = if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al)
        for (i in p + 1 until x.size) {
            val d = x[i] - x[i - 1]
            val gg = if (d > 0) d else 0.0
            val ll = if (d < 0) -d else 0.0
            ag = (ag * (p - 1) + gg) / p
            al = (al * (p - 1) + ll) / p
            out[i] = if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al)
        }
        return out
    }

    /** Histogramme MACD = ligne MACD − ligne de signal (12, 26, 9 par défaut). */
    fun macdHist(x: DoubleArray, f: Int = 12, s: Int = 26, sg: Int = 9): DoubleArray {
        val ef = ema(x, f)
        val es = ema(x, s)
        val macd = DoubleArray(x.size) { ef[it] - es[it] }
        val sig = ema(macd, sg)
        return DoubleArray(x.size) { macd[it] - sig[it] }
    }
}

private class IndRes(val base: String, val price: Double, val rsi: Double, val rsiPrev: Double, val hist: Double, val histPrev: Double)

/** Exécute les trois stratégies. Les ordres passent par Trader (mêmes protections, mêmes modes). */
object StrategyRunner {
    val rankStatus = MutableStateFlow("")
    val rsiStatus = MutableStateFlow("")
    val wallStatus = MutableStateFlow("")

    private val busy = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private val lastEntry = HashMap<String, Long>()
    private val bestRank = HashMap<Long, Int>()
    private var wallDay = ""
    private var wallCount = 0
    private var rsiKey = ""

    /** Appelé chaque seconde par le flux en direct. */
    fun tick(c: Context) {
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try {
                step(c)
            } catch (e: Exception) {
                DebugLog.log("ERREUR", "stratégies : ${e.message}")
                rankStatus.value = "Erreur : ${e.message}"
            } finally {
                busy.set(false)
            }
        }
    }

    /** Appelé régulièrement par le service : maintient le flux ouvert et déclenche l'analyse RSI + MACD. */
    fun manage(c: Context) {
        val sc = StratPrefs.load(c)
        val tc = TradePrefs.load(c)
        val need = (sc.rankOn || sc.wsOn) && tc.enabled
        if (need) LiveFeed.acquire(c, "strategies") else LiveFeed.release("strategies")
        rsiCheck(c, sc, tc)
    }

    private fun step(c: Context) {
        val sc = StratPrefs.load(c)
        if (!sc.rankOn && !sc.wsOn) return
        val tc = TradePrefs.load(c)
        if (!tc.enabled) {
            if (sc.rankOn) rankStatus.value = "Trading désactivé (interrupteur général)"
            if (sc.wsOn) wallStatus.value = "Trading désactivé (interrupteur général)"
            return
        }
        val cfg = Prefs.load(c)
        Positions.load(c)
        if (sc.rankOn) rankStep(c, sc, tc, cfg)
        if (sc.wsOn) wallStep(c, sc, tc, cfg)
    }

    private fun heldSymbols(env: String): Set<String> =
        Positions.items.value.filter { it.mode == env && it.status == "OPEN" }.map { it.symbol }.toSet()

    // ---------------------------------------------------------------- 1 : classement

    private fun rankStep(c: Context, sc: StratConfig, tc: TradeConfig, cfg: Config) {
        val rows = RankEngine.latest
        val now = System.currentTimeMillis()
        if (rows.isEmpty()) {
            rankStatus.value = "En attente du flux de prix…"
            return
        }
        // sorties : le rang se dégrade, ou durée maximale atteinte
        for (p in Positions.items.value.filter { it.status == "OPEN" && it.strategy == "RANK" && it.mode == tc.env }) {
            val rk = RankEngine.rankOf(p.symbol) ?: 9999
            val best = min(bestRank[p.id] ?: rk, rk)
            bestRank[p.id] = best
            val heldMin = (now - p.openTime) / 60_000L
            val why = when {
                rk > sc.rankExitBelow -> "RANG"
                rk - best >= sc.rankExitDrop -> "RANG"
                heldMin >= sc.rankMaxHoldMin -> "DUREE"
                else -> null
            }
            if (why != null) {
                val cr = Trader.closeNow(c, p.id, why)
                DebugLog.log("STRAT", "RANK sortie ${p.symbol} raison=$why rang=$rk meilleur=$best détention=${heldMin}min → ${cr.message}")
                bestRank.remove(p.id)
            }
        }
        val warm = RankEngine.warmMin.value
        if (warm < sc.rankWindowMin) {
            rankStatus.value = "Chauffe du classement : $warm / ${sc.rankWindowMin} min d'historique"
            return
        }
        if (cfg.btcFilter && ScanState.btcBlocked.value) {
            rankStatus.value = "En pause : état de Bitcoin défavorable"
            return
        }
        var msg: String? = null
        val held = heldSymbols(tc.env)
        for (r in rows.take(sc.rankTopN)) {
            if (r.symbol in held) continue
            if (now - (lastEntry[r.symbol] ?: 0L) < cfg.cooldownMin * 60_000L) continue
            val gain = RankEngine.rankDelta(r.symbol, sc.rankWindowMin) ?: continue
            if (gain < sc.rankGain) continue
            if (r.varPct < sc.rankVarMin || r.varPct > sc.rankVarMax) continue
            lastEntry[r.symbol] = now
            DebugLog.log("STRAT", "RANK entrée ${r.symbol} rang=${r.rank} gain=$gain/${sc.rankWindowMin}min var24h=${DebugLog.f(r.varPct)}% prix=${r.price}")
            val res = Trader.buy(c, r.symbol, "RANK", Signal(r.price, 0.0, r.varPct, 0.0, r.varPct), "RANK", sc.rankTp, sc.rankSl)
            DebugLog.log("STRAT", "RANK résultat ${r.symbol} : ${res.message}")
            msg = res.message
            if (res.blocked) break
        }
        val openN = Positions.items.value.count { it.status == "OPEN" && it.strategy == "RANK" && it.mode == tc.env }
        rankStatus.value = msg ?: "Actif · ${rows.size} jetons classés · $openN position(s) ouverte(s)"
    }

    // ---------------------------------------------------------------- 3 : ouverture de Wall Street

    private fun wallStep(c: Context, sc: StratConfig, tc: TradeConfig, cfg: Config) {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        for (p in Positions.items.value.filter { it.status == "OPEN" && it.strategy == "WALL" && it.mode == tc.env }) {
            if ((now - p.openTime) / 60_000L >= sc.wsHoldMin) {
                val cr = Trader.closeNow(c, p.id, "HEURE")
                DebugLog.log("STRAT", "WALL sortie ${p.symbol} durée atteinte → ${cr.message}")
            }
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (sc.wsWeekdays && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) {
            wallStatus.value = "Week-end : en attente"
            return
        }
        val minOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = sc.wsHour * 60 + sc.wsMinute
        val label = String.format(Locale.US, "%02d:%02d", sc.wsHour, sc.wsMinute)
        if (minOfDay < start || minOfDay >= start + sc.wsDurationMin) {
            wallStatus.value = "En attente de la fenêtre de $label (${sc.wsDurationMin} min)"
            return
        }
        val day = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        if (day != wallDay) {
            wallDay = day
            wallCount = 0
        }
        if (wallCount >= sc.wsMaxEntries) {
            wallStatus.value = "Fenêtre en cours : maximum d'entrées atteint"
            return
        }
        if (RankEngine.warmMin.value < sc.wsLookbackMin) {
            wallStatus.value = "Chauffe : ${RankEngine.warmMin.value} / ${sc.wsLookbackMin} min d'historique"
            return
        }
        if (cfg.btcFilter && ScanState.btcBlocked.value) {
            wallStatus.value = "En pause : état de Bitcoin défavorable"
            return
        }
        val held = heldSymbols(tc.env)
        val vars = RankEngine.varsOver(sc.wsLookbackMin)
        val cands = RankEngine.latest
            .filter { it.symbol !in held && (vars[it.symbol] ?: Double.NaN) >= sc.wsMinMovePct }
            .sortedByDescending { vars[it.symbol] ?: 0.0 }
        var msg = "Fenêtre ouverte · ${cands.size} jeton(s) au-dessus de ${sc.wsMinMovePct} % en ${sc.wsLookbackMin} min"
        for (r in cands) {
            if (wallCount >= sc.wsMaxEntries) break
            val v = vars[r.symbol] ?: 0.0
            DebugLog.log("STRAT", "WALL entrée ${r.symbol} mouvement=${DebugLog.f(v)}% sur ${sc.wsLookbackMin}min var24h=${DebugLog.f(r.varPct)}% prix=${r.price}")
            val res = Trader.buy(c, r.symbol, "WALL", Signal(r.price, 0.0, v, 0.0, r.varPct), "WALL", sc.wsTp, sc.wsSl)
            DebugLog.log("STRAT", "WALL résultat ${r.symbol} : ${res.message}")
            wallCount++
            msg = res.message
            if (res.blocked) break
        }
        wallStatus.value = msg
    }

    // ---------------------------------------------------------------- 2 : RSI + MACD (à la clôture de chaque bougie)

    private fun rsiCheck(c: Context, sc: StratConfig, tc: TradeConfig) {
        if (!sc.rsiOn) return
        if (!tc.enabled) {
            rsiStatus.value = "Trading désactivé (interrupteur général)"
            return
        }
        val ms = VolumeService.intervalMs(sc.rsiTf)
        val now = System.currentTimeMillis()
        val closedOpen = (now / ms) * ms - ms
        val key = "${sc.rsiTf}:$closedOpen"
        if (key == rsiKey || now < closedOpen + ms + 3_000L) return
        rsiKey = key
        Thread {
            try {
                rsiRun(c, sc, tc)
            } catch (e: Exception) {
                rsiStatus.value = "Erreur : ${e.message}"
            }
        }.start()
    }

    private fun rsiRun(c: Context, sc: StratConfig, tc: TradeConfig) {
        val cfg = Prefs.load(c)
        Positions.load(c)
        val held = Positions.items.value.filter { it.status == "OPEN" && it.strategy == "RSI" && it.mode == tc.env }
        val all = (Backtester.candidates(cfg, sc.rsiUniverse) + held.map { it.symbol }).distinct()
        val pool = Executors.newFixedThreadPool(6)
        val results = try {
            all.map { b ->
                pool.submit(Callable<IndRes?> {
                    try {
                        val cs = Binance.candles(b + "USDT", sc.rsiTf, 150).dropLast(1)   // bougie en cours exclue
                        if (cs.size < 60) {
                            null
                        } else {
                            val close = DoubleArray(cs.size) { cs[it].close }
                            val rsi = Ind.rsi(close, 14)
                            val hist = Ind.macdHist(close)
                            val n = cs.size
                            IndRes(b, close[n - 1], rsi[n - 1], rsi[n - 2], hist[n - 1], hist[n - 2])
                        }
                    } catch (e: Exception) {
                        null
                    }
                })
            }.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
        // sorties : RSI trop haut ou croisement baissier du MACD
        for (p in held) {
            val r = results.firstOrNull { it.base == p.symbol } ?: continue
            if (r.rsi >= sc.rsiExit || (r.histPrev > 0 && r.hist <= 0)) {
                val cr = Trader.closeNow(c, p.id, "INDIC")
                DebugLog.log("STRAT", "RSI sortie ${p.symbol} rsi=${DebugLog.f(r.rsi)} macdHist=${DebugLog.f(r.hist, 6)} → ${cr.message}")
            }
        }
        var entries = 0
        var msg: String? = null
        if (!(cfg.btcFilter && ScanState.btcBlocked.value)) {
            val heldNow = heldSymbols(tc.env)
            for (r in results) {
                if (r.base in heldNow) continue
                val ok = !r.rsi.isNaN() && !r.rsiPrev.isNaN() && r.histPrev <= 0 && r.hist > 0 &&
                    r.rsi >= sc.rsiMin && r.rsi <= sc.rsiMax && r.rsi > r.rsiPrev
                if (!ok) continue
                DebugLog.log("STRAT", "RSI entrée ${r.base} rsi=${DebugLog.f(r.rsi)} (avant ${DebugLog.f(r.rsiPrev)}) macdHist=${DebugLog.f(r.hist, 6)} (avant ${DebugLog.f(r.histPrev, 6)}) prix=${r.price}")
                val res = Trader.buy(c, r.base, "RSI", Signal(r.price, 0.0, 0.0, 0.0, 0.0), "RSI", sc.rsiTp, sc.rsiSl)
                DebugLog.log("STRAT", "RSI résultat ${r.base} : ${res.message}")
                entries++
                msg = res.message
                if (res.blocked) break
            }
        }
        val stamp = String.format(Locale.US, "%tH:%<tM", System.currentTimeMillis())
        rsiStatus.value = "Analyse de $stamp · ${results.size} jetons · $entries entrée(s)" + (if (msg != null) " · $msg" else "")
        DebugLog.log("STRAT", "RSI " + rsiStatus.value)
    }
}
