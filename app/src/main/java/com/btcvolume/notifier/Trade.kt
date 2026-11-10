package com.btcvolume.notifier

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TradeConfig(
    val env: String = "SIM",             // SIM / TESTNET / REAL
    val enabled: Boolean = false,        // interrupteur général (désactivé par défaut)
    val trigger: String = "MANUAL",      // MANUAL / SEMI / AUTO
    val amountUsdt: Double = 15.0,
    val tpPct: Double = 3.0,
    val slPct: Double = 1.5,             // obligatoire
    val maxPositions: Int = 3,
    val dailyLossLimit: Double = 20.0,   // USDT, obligatoire (> 0)
    val autoMinRatio: Double = 5.0,
    val buyOnlyUp: Boolean = true,
    val realAutoOk: Boolean = false
)

private fun SharedPreferences.dbl(key: String, def: Double): Double =
    getString(key, null)?.toDoubleOrNull() ?: def

object TradePrefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("trade", Context.MODE_PRIVATE)

    fun load(c: Context): TradeConfig {
        val p = sp(c)
        val d = TradeConfig()
        return TradeConfig(
            env = p.getString("env", d.env) ?: d.env,
            enabled = p.getBoolean("enabled", d.enabled),
            trigger = p.getString("trigger", d.trigger) ?: d.trigger,
            amountUsdt = p.dbl("amountUsdt", d.amountUsdt),
            tpPct = p.dbl("tpPct", d.tpPct),
            slPct = p.dbl("slPct", d.slPct),
            maxPositions = p.getInt("maxPositions", d.maxPositions),
            dailyLossLimit = p.dbl("dailyLossLimit", d.dailyLossLimit),
            autoMinRatio = p.dbl("autoMinRatio", d.autoMinRatio),
            buyOnlyUp = p.getBoolean("buyOnlyUp", d.buyOnlyUp),
            realAutoOk = p.getBoolean("realAutoOk", d.realAutoOk)
        )
    }

    fun save(c: Context, t: TradeConfig) {
        sp(c).edit()
            .putString("env", t.env)
            .putBoolean("enabled", t.enabled)
            .putString("trigger", t.trigger)
            .putString("amountUsdt", t.amountUsdt.toString())
            .putString("tpPct", t.tpPct.toString())
            .putString("slPct", t.slPct.toString())
            .putInt("maxPositions", t.maxPositions)
            .putString("dailyLossLimit", t.dailyLossLimit.toString())
            .putString("autoMinRatio", t.autoMinRatio.toString())
            .putBoolean("buyOnlyUp", t.buyOnlyUp)
            .putBoolean("realAutoOk", t.realAutoOk)
            .apply()
    }
}

data class Position(
    val id: Long,
    val symbol: String,        // crypto de base, ex : DOGE
    val mode: String,          // SIM / TESTNET / REAL
    val entryPrice: Double,
    val qty: Double,           // quantité nette détenue
    val cost: Double,          // USDT dépensés
    val tp: Double,
    val sl: Double,
    val orderListId: Long,     // OCO Binance, -1 si simulation ou sans protection
    val status: String,        // OPEN / CLOSED
    val exitPrice: Double,
    val pnl: Double,           // résultat estimé en USDT
    val closeReason: String,
    val openTime: Long,
    val closeTime: Long
)

object Positions {
    private const val MAX = 500
    private val _items = MutableStateFlow<List<Position>>(emptyList())
    val items: StateFlow<List<Position>> = _items
    private var loaded = false

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.getSharedPreferences("positions", Context.MODE_PRIVATE)
            .getString("items", null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = ArrayList<Position>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Position(
                        o.getLong("id"), o.getString("symbol"), o.getString("mode"),
                        o.getDouble("entry"), o.getDouble("qty"), o.getDouble("cost"),
                        o.getDouble("tp"), o.getDouble("sl"), o.getLong("listId"),
                        o.getString("status"), o.getDouble("exit"), o.getDouble("pnl"),
                        o.getString("reason"), o.getLong("openTime"), o.getLong("closeTime")
                    )
                )
            }
            _items.value = list
        } catch (e: Exception) {
            // données illisibles
        }
    }

    @Synchronized
    fun get(c: Context, id: Long): Position? {
        load(c)
        return _items.value.firstOrNull { it.id == id }
    }

    @Synchronized
    fun upsert(c: Context, p: Position) {
        load(c)
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(0, p)
        val out = list.take(MAX)
        _items.value = out
        save(c, out)
    }

    private fun save(c: Context, list: List<Position>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("symbol", p.symbol).put("mode", p.mode)
                    .put("entry", p.entryPrice).put("qty", p.qty).put("cost", p.cost)
                    .put("tp", p.tp).put("sl", p.sl).put("listId", p.orderListId)
                    .put("status", p.status).put("exit", p.exitPrice).put("pnl", p.pnl)
                    .put("reason", p.closeReason).put("openTime", p.openTime)
                    .put("closeTime", p.closeTime)
            )
        }
        c.getSharedPreferences("positions", Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }
}
