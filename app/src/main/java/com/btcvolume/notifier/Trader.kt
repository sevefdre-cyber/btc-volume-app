package com.btcvolume.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

data class Signal(val price: Double, val ratio: Double, val change: Double, val wick: Double, val chg24: Double)

class TradeResult(val ok: Boolean, val message: String, val blocked: Boolean = false)

fun envLabel(env: String): String = when (env) {
    "REAL" -> "Réel"
    "TESTNET" -> "Testnet"
    else -> "Simulation"
}

/** Notifications hors service (résultats d'ordres). */
object Notifier {
    private var nextId = 5000

    @Synchronized
    fun post(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(VolumeService.CH_ALERT, "Alertes de volume", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, VolumeService.CH_ALERT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(nextId++, n)
    }
}

/** Bouton « Acheter » des notifications en mode semi-automatique. */
class TradeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val symbol = intent.getStringExtra("symbol") ?: return
        val nid = intent.getIntExtra("nid", -1)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val sig = if (intent.hasExtra("ratio")) {
                    Signal(
                        intent.getDoubleExtra("price", 0.0), intent.getDoubleExtra("ratio", 0.0),
                        intent.getDoubleExtra("change", 0.0), intent.getDoubleExtra("wick", 0.0),
                        intent.getDoubleExtra("chg24", 0.0)
                    )
                } else null
                val r = Trader.buy(app, symbol, "SEMI", sig)
                if (nid >= 0) app.getSystemService(NotificationManager::class.java).cancel(nid)
                if (r.blocked) Notifier.post(app, "⚠️ Achat $symbol non effectué", r.message)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object Trader {
    private const val SIM_FEE = 0.999

    fun startOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun dailyPnl(list: List<Position>, env: String): Double {
        val s = startOfDay()
        return list.filter { it.mode == env && it.status == "CLOSED" && it.closeTime >= s }.sumOf { it.pnl }
    }

    /** Protections : retourne la raison du blocage, ou null si l'achat est autorisé. */
    private fun check(tc: TradeConfig): String? {
        if (!tc.enabled) return "Trading désactivé (interrupteur général)."
        if (tc.slPct < 0.1 || tc.slPct > 50.0) return "Stop-loss obligatoire : réglez-le entre 0,1 % et 50 %."
        if (tc.tpPct <= 0.0) return "Take-profit invalide."
        if (tc.amountUsdt <= 0.0) return "Montant par trade invalide."
        if (tc.dailyLossLimit <= 0.0) return "Définissez une limite de perte journalière supérieure à 0."
        val list = Positions.items.value
        val open = list.count { it.mode == tc.env && it.status == "OPEN" }
        if (open >= tc.maxPositions) return "Nombre maximum de positions atteint (${tc.maxPositions})."
        if (dailyPnl(list, tc.env) <= -tc.dailyLossLimit) return "Limite de perte journalière atteinte."
        return null
    }

    // ------------------------------------------------------------ achat

    @Synchronized
    fun buy(c: Context, base: String, source: String, sig: Signal? = null): TradeResult {
        Positions.load(c)
        val tc = TradePrefs.load(c)
        val block = check(tc)
        if (block != null) return TradeResult(false, block, true)
        val pair = base + "USDT"
        return try {
            val p0 = if (tc.env == "SIM") buySim(c, tc, base, pair) else buyLive(c, tc, base, pair)
            val p = if (sig != null) tag(c, p0, sig) else p0
            val msg = "Achat $base à ${fmtPrice(p.entryPrice)} · TP ${fmtPrice(p.tp)} · SL ${fmtPrice(p.sl)} (${envLabel(tc.env)})"
            if (source != "MANUAL") Notifier.post(c, "✅ Achat $base", msg)
            TradeResult(true, msg)
        } catch (e: Exception) {
            val m = e.message ?: "erreur inconnue"
            if (source != "MANUAL") Notifier.post(c, "⚠️ Achat $base échoué", m)
            TradeResult(false, "Achat $base échoué : $m")
        }
    }

    /** Enregistre les caractéristiques du signal pour le journal. */
    private fun tag(c: Context, p: Position, sig: Signal): Position {
        val slip = if (sig.price > 0) (p.entryPrice - sig.price) / sig.price * 100.0 else 0.0
        val q = p.copy(
            sigRatio = sig.ratio, sigChange = sig.change, sigWick = sig.wick,
            sigChg24 = sig.chg24, slippagePct = slip
        )
        Positions.upsert(c, q)
        return q
    }

    private fun buySim(c: Context, tc: TradeConfig, base: String, pair: String): Position {
        val price = Binance.price(pair) * (1 + tc.simSlippagePct / 100.0)
        val now = System.currentTimeMillis()
        val p = Position(
            id = now, symbol = base, mode = "SIM", entryPrice = price,
            qty = tc.amountUsdt / price * SIM_FEE, cost = tc.amountUsdt,
            tp = price * (1 + tc.tpPct / 100), sl = price * (1 - tc.slPct / 100),
            orderListId = -1L, status = "OPEN", exitPrice = 0.0, pnl = 0.0,
            closeReason = "", openTime = now, closeTime = 0L
        )
        Positions.upsert(c, p)
        return p
    }

    private fun buyLive(c: Context, tc: TradeConfig, base: String, pair: String): Position {
        val env = tc.env
        val keys = Secrets.pair(c, env)
            ?: throw IOException("Clé API ${envLabel(env)} non enregistrée (Réglages).")
        val key = keys.first
        val secret = keys.second

        val f = Binance.filters(env, pair)
        val amount = BigDecimal(tc.amountUsdt.toString())
        if (amount < f.minNotional) {
            throw IOException("Montant inférieur au minimum Binance (${f.minNotional.toPlainString()} USDT).")
        }
        val quote = amount.setScale(2, RoundingMode.DOWN)

        val r = JSONObject(
            Binance.signed(
                env, "POST", "/api/v3/order",
                linkedMapOf(
                    "symbol" to pair, "side" to "BUY", "type" to "MARKET",
                    "quoteOrderQty" to quote.toPlainString(), "newOrderRespType" to "FULL"
                ),
                key, secret
            )
        )
        if (r.optString("status") != "FILLED") {
            throw IOException("Ordre d'achat non exécuté (statut ${r.optString("status")}). Vérifiez vos ordres sur Binance.")
        }
        val exec = r.getString("executedQty").toDouble()
        val spent = r.getString("cummulativeQuoteQty").toDouble()
        var fee = 0.0
        val fills = r.optJSONArray("fills")
        if (fills != null) {
            for (i in 0 until fills.length()) {
                val fl = fills.getJSONObject(i)
                if (fl.optString("commissionAsset") == base) {
                    fee += fl.optString("commission").toDoubleOrNull() ?: 0.0
                }
            }
        }
        val entry = spent / exec
        val net = exec - fee
        val qtyBd = Binance.floorTo(net, f.step)
        val qtyStr = Binance.plain(qtyBd)
        val tp = Binance.plain(Binance.floorTo(entry * (1 + tc.tpPct / 100), f.tick))
        val slStop = Binance.plain(Binance.floorTo(entry * (1 - tc.slPct / 100), f.tick))
        val slLimit = Binance.plain(Binance.floorTo(entry * (1 - tc.slPct / 100) * 0.995, f.tick))
        val now = System.currentTimeMillis()

        var listId = -1L
        try {
            if (qtyBd < f.minQty) throw IOException("quantité trop faible pour poser une protection")
            val oco = JSONObject(
                Binance.signed(
                    env, "POST", "/api/v3/orderList/oco",
                    linkedMapOf(
                        "symbol" to pair, "side" to "SELL", "quantity" to qtyStr,
                        "aboveType" to "LIMIT_MAKER", "abovePrice" to tp,
                        "belowType" to "STOP_LOSS_LIMIT", "belowStopPrice" to slStop,
                        "belowPrice" to slLimit, "belowTimeInForce" to "GTC",
                        "newOrderRespType" to "FULL"
                    ),
                    key, secret
                )
            )
            listId = oco.getLong("orderListId")
        } catch (e: Exception) {
            // Stop-loss obligatoire : si la protection échoue, on revend aussitôt.
            var sold: Pair<Double, Double>? = null
            var sellErr = ""
            try {
                sold = sellMarket(env, key, secret, pair, qtyStr)
            } catch (e2: Exception) {
                sellErr = e2.message ?: "erreur"
            }
            val s = sold
            if (s != null) {
                Positions.upsert(
                    c,
                    Position(
                        now, base, env, entry, net, spent, entry * (1 + tc.tpPct / 100),
                        entry * (1 - tc.slPct / 100), -1L, "CLOSED", s.first, s.second - spent,
                        "ÉCHEC PROTECTION", now, System.currentTimeMillis()
                    )
                )
                throw IOException("Protection TP/SL refusée (${e.message}) : position revendue aussitôt.")
            }
            Positions.upsert(
                c,
                Position(
                    now, base, env, entry, net, spent, entry * (1 + tc.tpPct / 100),
                    entry * (1 - tc.slPct / 100), -1L, "OPEN", 0.0, 0.0, "NON PROTÉGÉE", now, 0L
                )
            )
            throw IOException(
                "ATTENTION : protection refusée (${e.message}) ET revente échouée ($sellErr). " +
                    "La position $base n'est PAS protégée : fermez-la sur Binance."
            )
        }

        val p = Position(
            now, base, env, entry, net, spent, tp.toDouble(), slStop.toDouble(),
            listId, "OPEN", 0.0, 0.0, "", now, 0L
        )
        Positions.upsert(c, p)
        return p
    }

    /** Vente au marché. Retourne (prix de sortie moyen, USDT nets reçus). */
    private fun sellMarket(
        env: String, key: String, secret: String, pair: String, qty: String
    ): Pair<Double, Double> {
        val r = JSONObject(
            Binance.signed(
                env, "POST", "/api/v3/order",
                linkedMapOf(
                    "symbol" to pair, "side" to "SELL", "type" to "MARKET",
                    "quantity" to qty, "newOrderRespType" to "FULL"
                ),
                key, secret
            )
        )
        if (r.optString("status") != "FILLED") {
            throw IOException("Vente non exécutée (statut ${r.optString("status")}).")
        }
        val q = r.getString("cummulativeQuoteQty").toDouble()
        val e = r.getString("executedQty").toDouble()
        var fee = 0.0
        val fills = r.optJSONArray("fills")
        if (fills != null) {
            for (i in 0 until fills.length()) {
                val fl = fills.getJSONObject(i)
                if (fl.optString("commissionAsset") == "USDT") {
                    fee += fl.optString("commission").toDoubleOrNull() ?: 0.0
                }
            }
        }
        return Pair(q / e, q - fee)
    }

    // ------------------------------------------------------------ clôture

    private fun finish(c: Context, p: Position, exit: Double, netQuote: Double, reason: String): Position {
        val q = p.copy(
            status = "CLOSED", exitPrice = exit, pnl = netQuote - p.cost,
            closeReason = reason, closeTime = System.currentTimeMillis()
        )
        Positions.upsert(c, q)
        return q
    }

    @Synchronized
    fun closeNow(c: Context, id: Long, reason: String): TradeResult {
        val p = Positions.get(c, id) ?: return TradeResult(false, "Position introuvable.")
        if (p.status != "OPEN") return TradeResult(false, "Position déjà clôturée.")
        val pair = p.symbol + "USDT"
        return try {
            if (p.mode == "SIM") {
                val price = Binance.price(pair) * (1 - TradePrefs.load(c).simSlippagePct / 100.0)
                val q = finish(c, p, price, p.qty * price * SIM_FEE, reason)
                TradeResult(true, "${p.symbol} clôturé : ${fmtUsd(q.pnl)}")
            } else {
                val keys = Secrets.pair(c, p.mode)
                    ?: throw IOException("Clé API ${envLabel(p.mode)} non enregistrée.")
                if (p.orderListId >= 0) {
                    try {
                        Binance.signed(
                            p.mode, "DELETE", "/api/v3/orderList",
                            linkedMapOf("symbol" to pair, "orderListId" to p.orderListId.toString()),
                            keys.first, keys.second
                        )
                    } catch (e: ApiException) {
                        // L'OCO est peut-être déjà exécuté : on resynchronise.
                        syncLive(c, p)
                        val again = Positions.get(c, id)
                        if (again != null && again.status != "OPEN") {
                            return TradeResult(true, "${p.symbol} : déjà exécuté sur Binance (${again.closeReason}).")
                        }
                        throw e
                    }
                }
                val f = Binance.filters(p.mode, pair)
                val qty = Binance.plain(Binance.floorTo(p.qty, f.step))
                val sale = sellMarket(p.mode, keys.first, keys.second, pair, qty)
                val q = finish(c, p, sale.first, sale.second, reason)
                TradeResult(true, "${p.symbol} clôturé : ${fmtUsd(q.pnl)}")
            }
        } catch (e: Exception) {
            TradeResult(false, "Clôture de ${p.symbol} échouée : ${e.message}")
        }
    }

    /** Arrêt d'urgence : désactive le trading et clôture toutes les positions ouvertes. */
    fun panic(c: Context): TradeResult {
        Positions.load(c)
        TradePrefs.save(c, TradePrefs.load(c).copy(enabled = false))
        val open = Positions.items.value.filter { it.status == "OPEN" }
        var ok = 0
        var fail = 0
        val errors = ArrayList<String>()
        for (p in open) {
            val r = closeNow(c, p.id, "URGENCE")
            if (r.ok) ok++ else {
                fail++
                errors.add(r.message)
            }
        }
        val msg = "Trading désactivé. $ok position(s) clôturée(s), $fail échec(s)." +
            if (errors.isNotEmpty()) " " + errors.joinToString(" ") else ""
        return TradeResult(fail == 0, msg)
    }

    // ------------------------------------------------------------ synchronisation

    private fun syncSim(c: Context, p: Position, prices: Map<String, Double>, slip: Double): Position? {
        val price = prices[p.symbol + "USDT"] ?: return null
        return when {
            price >= p.tp -> finish(c, p, p.tp, p.qty * p.tp * SIM_FEE, "TP")
            price <= p.sl -> finish(c, p, p.sl * (1 - slip), p.qty * p.sl * (1 - slip) * SIM_FEE, "SL")
            else -> null
        }
    }

    /** Vérifie l'ordre OCO sur Binance. Retourne true si la position vient d'être clôturée. */
    private fun syncLive(c: Context, p: Position): Boolean {
        if (p.orderListId < 0) return false
        val keys = Secrets.pair(c, p.mode) ?: return false
        val pair = p.symbol + "USDT"
        val list = JSONObject(
            Binance.signed(
                p.mode, "GET", "/api/v3/orderList",
                linkedMapOf("orderListId" to p.orderListId.toString()), keys.first, keys.second
            )
        )
        if (list.optString("listOrderStatus") != "ALL_DONE") return false
        val orders = list.getJSONArray("orders")
        for (i in 0 until orders.length()) {
            val oid = orders.getJSONObject(i).getLong("orderId")
            val o = JSONObject(
                Binance.signed(
                    p.mode, "GET", "/api/v3/order",
                    linkedMapOf("symbol" to pair, "orderId" to oid.toString()), keys.first, keys.second
                )
            )
            if (o.optString("status") == "FILLED") {
                val trades = JSONArray(
                    Binance.signed(
                        p.mode, "GET", "/api/v3/myTrades",
                        linkedMapOf("symbol" to pair, "orderId" to oid.toString()), keys.first, keys.second
                    )
                )
                var quote = 0.0
                var qty = 0.0
                var fee = 0.0
                for (j in 0 until trades.length()) {
                    val t = trades.getJSONObject(j)
                    quote += t.getString("quoteQty").toDouble()
                    qty += t.getString("qty").toDouble()
                    if (t.optString("commissionAsset") == "USDT") {
                        fee += t.optString("commission").toDoubleOrNull() ?: 0.0
                    }
                }
                val exit = if (qty > 0) quote / qty else o.optString("price").toDoubleOrNull() ?: 0.0
                val reason = if (o.optString("type") == "LIMIT_MAKER") "TP" else "SL"
                finish(c, p, exit, quote - fee, reason)
                return true
            }
        }
        // Les deux ordres ont été annulés (par exemple depuis l'application Binance).
        finish(c, p, 0.0, p.cost, "ANNULÉ")
        return true
    }

    @Synchronized
    fun syncAll(c: Context) {
        Positions.load(c)
        val open = Positions.items.value.filter { it.status == "OPEN" }
        if (open.isEmpty()) return
        val slip = TradePrefs.load(c).simSlippagePct / 100.0
        var prices: Map<String, Double>? = null
        for (p in open) {
            try {
                var closed: Position? = null
                if (p.mode == "SIM") {
                    val pr = prices ?: Binance.allPrices().also { prices = it }
                    closed = syncSim(c, p, pr, slip)
                } else if (syncLive(c, p)) {
                    closed = Positions.get(c, p.id)
                }
                if (closed != null && closed.status == "CLOSED") {
                    val icon = when (closed.closeReason) {
                        "TP" -> "🎯"
                        "SL" -> "🛑"
                        else -> "ℹ️"
                    }
                    Notifier.post(
                        c, "$icon ${closed.symbol} clôturé (${closed.closeReason})",
                        "Résultat estimé : ${fmtUsd(closed.pnl)} · ${envLabel(closed.mode)}"
                    )
                }
            } catch (e: Exception) {
                // nouvelle tentative au prochain cycle
            }
        }
    }

    // ------------------------------------------------------------ test de connexion

    fun testConnection(c: Context, env: String): TradeResult {
        if (env == "SIM") return TradeResult(true, "Simulation : aucune clé nécessaire.")
        val keys = Secrets.pair(c, env)
            ?: return TradeResult(false, "Aucune clé enregistrée pour ${envLabel(env)}.")
        return try {
            val o = JSONObject(
                Binance.signed(env, "GET", "/api/v3/account", emptyMap(), keys.first, keys.second)
            )
            var usdt = "0"
            val bal = o.optJSONArray("balances")
            if (bal != null) {
                for (i in 0 until bal.length()) {
                    val b = bal.getJSONObject(i)
                    if (b.optString("asset") == "USDT") usdt = b.optString("free")
                }
            }
            TradeResult(true, "Connexion OK · trading autorisé : ${o.optBoolean("canTrade")} · USDT libre : $usdt")
        } catch (e: Exception) {
            TradeResult(false, "Échec : ${e.message}")
        }
    }
}
