package com.btcvolume.notifier

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Dernier état d'une paire : prix actuel et statistiques sur 24 h glissantes. */
class Tick(val price: Double, val open24: Double, val high24: Double, val low24: Double, val qv: Double)

object LiveState {
    val ticks = MutableStateFlow<Map<String, Tick>>(emptyMap())   // clé : paire, par exemple DOGEUSDT
    val status = MutableStateFlow("Flux arrêté")
}

/**
 * Flux de prix en direct (WebSocket public de Binance, sans clé).
 * Plusieurs écrans ou services peuvent le demander : il reste ouvert tant qu'un « porteur » en a besoin.
 */
object LiveFeed {
    private val holders = HashSet<String>()
    private val data = ConcurrentHashMap<String, Tick>()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var lastMsg = 0L
    @Volatile private var sock: WebSocket? = null
    private var feedThread: Thread? = null
    private var pubThread: Thread? = null

    private fun setStatus(s: String) {
        LiveState.status.value = s
        DebugLog.log("FLUX", s)
    }

    @Synchronized
    fun acquire(c: Context, who: String) {
        holders.add(who)
        if (!running) {
            running = true
            val app = c.applicationContext
            feedThread = Thread { feedLoop(app) }.also { it.start() }
            pubThread = Thread { publishLoop(app) }.also { it.start() }
        }
    }

    @Synchronized
    fun release(who: String) {
        holders.remove(who)
        if (holders.isEmpty() && running) {
            running = false
            sock?.cancel()
            feedThread?.interrupt()
            pubThread?.interrupt()
            setStatus("Flux arrêté")
        }
    }

    /** Paires USDT suivies : filtres de prix et de volume de la configuration, stablecoins exclus, 400 maximum. */
    private fun universe(cfg: Config): List<String> {
        val arr = JSONArray(Binance.publicGet("REAL", "/api/v3/ticker/24hr", mapOf("type" to "MINI")))
        class U(val pair: String, val qv: Double)
        val l = ArrayList<U>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val pair = t.getString("symbol")
            if (!pair.endsWith("USDT")) continue
            val base = pair.removeSuffix("USDT")
            if (base in cfg.excluded) continue
            val price = t.optString("lastPrice").toDoubleOrNull() ?: continue
            val open = t.optString("openPrice").toDoubleOrNull() ?: continue
            val qv = t.optString("quoteVolume").toDoubleOrNull() ?: continue
            if (price <= 0.0 || open <= 0.0 || qv <= 0.0) continue
            val hi = t.optString("highPrice").toDoubleOrNull() ?: price
            val lo = t.optString("lowPrice").toDoubleOrNull() ?: price
            if (Stables.isStable(base, price, hi, lo)) continue
            val ok = base == "BTC" || base in cfg.favorites ||
                (price >= cfg.priceMin && price <= cfg.priceMax && qv >= cfg.minVol24h)
            if (!ok) continue
            data[pair] = Tick(price, open, hi, lo, qv)
            l.add(U(pair, qv))
        }
        return l.sortedByDescending { it.qv }.take(400).map { it.pair }
    }

    private fun onText(text: String) {
        lastMsg = System.currentTimeMillis()
        try {
            val d = JSONObject(text).optJSONObject("data") ?: return
            val s = d.optString("s")
            if (s.isEmpty()) return
            val c = d.optString("c").toDoubleOrNull() ?: return
            val o = d.optString("o").toDoubleOrNull() ?: return
            data[s] = Tick(
                c, o,
                d.optString("h").toDoubleOrNull() ?: c,
                d.optString("l").toDoubleOrNull() ?: c,
                d.optString("q").toDoubleOrNull() ?: 0.0
            )
        } catch (e: Exception) {
            // message illisible : ignoré
        }
    }

    private fun feedLoop(c: Context) {
        var backoff = 2_000L
        while (running) {
            try {
                val cfg = Prefs.load(c)
                setStatus("Connexion au flux…")
                val uni = universe(cfg)
                RankEngine.setUniverse(uni)
                val closed = CountDownLatch(1)
                lastMsg = System.currentTimeMillis()
                val req = Request.Builder().url("wss://stream.binance.com:9443/stream").build()
                val s = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Thread {
                            try {
                                var id = 1
                                for (chunk in uni.chunked(100)) {
                                    val params = chunk.joinToString(",") { "\"" + it.lowercase() + "@miniTicker\"" }
                                    webSocket.send("{\"method\":\"SUBSCRIBE\",\"params\":[" + params + "],\"id\":" + id + "}")
                                    id++
                                    Thread.sleep(300)   // limite de Binance : 5 messages par seconde
                                }
                                setStatus("En direct · ${uni.size} jetons")
                            } catch (e: Exception) {
                                // connexion fermée pendant l'abonnement
                            }
                        }.start()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        onText(text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        setStatus("Flux interrompu : ${t.message}")
                        closed.countDown()
                    }
                })
                sock = s
                backoff = 2_000L
                while (running && !closed.await(1, TimeUnit.SECONDS)) {
                    if (System.currentTimeMillis() - lastMsg > 20_000L) break   // flux muet : reconnexion
                }
                s.cancel()
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                setStatus("Erreur du flux : ${e.message}")
            }
            if (!running) return
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                return
            }
            backoff = min(backoff * 2, 30_000L)
        }
    }

    /** Une fois par seconde : diffusion aux écrans, calcul du classement, évaluation des stratégies. */
    private fun publishLoop(c: Context) {
        while (running) {
            try {
                Thread.sleep(1_000)
                val snap = HashMap(data)
                LiveState.ticks.value = snap
                RankEngine.compute(snap)
                DebugLog.onTick(c, snap)
                StrategyRunner.tick(c)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // le cycle suivant réessaiera
            }
        }
    }
}

class RankRow(
    val symbol: String,   // crypto de base, ex : DOGE
    val price: Double,
    val varPct: Double,   // variation sur 24 h glissantes en %
    val rank: Int,
    val d5: Int,          // places gagnées sur 5 min (négatif = perdues)
    val d15: Int,
    val d60: Int,
    val v5: Double,       // variation du prix sur 5 min en % (NaN si historique insuffisant)
    val qv: Double
)

/**
 * Tri local des jetons. Variation % = (prix actuel − prix d'ouverture) / prix d'ouverture × 100,
 * le prix d'ouverture étant celui d'il y a 24 h glissantes (comme Binance).
 */
object RankEngine {
    val rows = MutableStateFlow<List<RankRow>>(emptyList())
    val calcMicros = MutableStateFlow(0L)
    val warmMin = MutableStateFlow(0)

    @Volatile var latest: List<RankRow> = emptyList()

    private class Snap(val t: Long, val rank: ShortArray, val price: FloatArray)

    private val hist = java.util.ArrayDeque<Snap>()
    private var uni: List<String> = emptyList()
    private var index: Map<String, Int> = emptyMap()
    private var rankMap: Map<String, Int> = emptyMap()
    private var pxNow = DoubleArray(0)
    private var lastSnap = 0L

    @Synchronized
    fun setUniverse(l: List<String>) {
        uni = l
        val m = HashMap<String, Int>()
        for ((i, p) in l.withIndex()) m[p.removeSuffix("USDT")] = i
        index = m
        hist.clear()
        lastSnap = 0L
        pxNow = DoubleArray(l.size)
    }

    @Synchronized
    fun rankOf(base: String): Int? = rankMap[base]

    private fun snapAt(now: Long, min: Int): Snap? {
        val target = now - min * 60_000L
        val it = hist.descendingIterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.t <= target) return s
        }
        return null
    }

    private fun delta(s: Snap?, i: Int, cur: Int): Int {
        if (s == null) return 0
        val r = s.rank[i].toInt()
        return if (r > 0) r - cur else 0
    }

    @Synchronized
    fun compute(snap: Map<String, Tick>) {
        val t0 = System.nanoTime()
        val n = uni.size
        if (n == 0) return
        val v = DoubleArray(n)
        val px = DoubleArray(n)
        for (i in 0 until n) {
            val t = snap[uni[i]]
            if (t != null && t.open24 > 0.0) {
                px[i] = t.price
                v[i] = (t.price - t.open24) / t.open24 * 100.0
            } else {
                v[i] = Double.NaN
            }
        }
        val order = (0 until n).filter { !v[it].isNaN() }.sortedByDescending { v[it] }
        val rank = ShortArray(n)
        for ((pos, i) in order.withIndex()) rank[i] = (pos + 1).toShort()
        val now = System.currentTimeMillis()
        if (now - lastSnap >= 10_000L) {
            hist.addLast(Snap(now, rank.copyOf(), FloatArray(n) { px[it].toFloat() }))
            lastSnap = now
            while (hist.isNotEmpty() && now - hist.peekFirst()!!.t > 66 * 60_000L) hist.pollFirst()
        }
        val s5 = snapAt(now, 5)
        val s15 = snapAt(now, 15)
        val s60 = snapAt(now, 60)
        val out = ArrayList<RankRow>(order.size)
        val rm = HashMap<String, Int>(order.size * 2)
        for (i in order) {
            val base = uni[i].removeSuffix("USDT")
            val r = rank[i].toInt()
            val p5 = if (s5 != null) s5.price[i].toDouble() else 0.0
            val v5 = if (p5 > 0.0) (px[i] - p5) / p5 * 100.0 else Double.NaN
            out.add(RankRow(base, px[i], v[i], r, delta(s5, i, r), delta(s15, i, r), delta(s60, i, r), v5, snap[uni[i]]!!.qv))
            rm[base] = r
        }
        pxNow = px
        rankMap = rm
        latest = out
        rows.value = out
        warmMin.value = if (hist.isEmpty()) 0 else ((now - hist.peekFirst()!!.t) / 60_000L).toInt()
        calcMicros.value = (System.nanoTime() - t0) / 1000L
    }

    /** Places gagnées (positif) ou perdues par un jeton depuis [minutes] minutes, null si l'historique manque. */
    @Synchronized
    fun rankDelta(base: String, minutes: Int): Int? {
        val i = index[base] ?: return null
        val cur = rankMap[base] ?: return null
        val s = snapAt(System.currentTimeMillis(), minutes) ?: return null
        val r = s.rank[i].toInt()
        return if (r > 0) r - cur else null
    }

    /** Variation du prix en % de chaque jeton sur les [minutes] dernières minutes. */
    @Synchronized
    fun varsOver(minutes: Int): Map<String, Double> {
        val s = snapAt(System.currentTimeMillis(), minutes) ?: return emptyMap()
        val out = HashMap<String, Double>()
        for ((base, i) in index) {
            val p0 = s.price[i].toDouble()
            if (p0 > 0.0 && i < pxNow.size && pxNow[i] > 0.0) out[base] = (pxNow[i] - p0) / p0 * 100.0
        }
        return out
    }
}
