package com.btcvolume.notifier

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ScanRow(
    val symbol: String,      // crypto de base, ex : DOGE
    val price: Double,
    val changePct: Double,   // variation de la dernière bougie clôturée
    val ratio: Double,       // volume de la bougie / moyenne
    val volUsdt: Double,     // volume de la bougie en USDT
    val candle: Long,        // heure d'ouverture de la bougie
    val wickPct: Double = 0.0,   // mèche haute en % de la bougie
    val chg24: Double = 0.0      // variation sur 24 h en %
)

data class AlertItem(
    val time: Long,
    val symbol: String,
    val price: Double,
    val changePct: Double,
    val ratio: Double,
    val volUsdt: Double
)

object ScanState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Appuyez sur Démarrer")
    val rows = MutableStateFlow<List<ScanRow>>(emptyList())
    val monitored = MutableStateFlow(0)
    val totalPairs = MutableStateFlow(0)
    val lastScan = MutableStateFlow(0L)
}

object History {
    private const val MAX = 300
    private val _items = MutableStateFlow<List<AlertItem>>(emptyList())
    val items: StateFlow<List<AlertItem>> = _items
    private var loaded = false

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.getSharedPreferences("history", Context.MODE_PRIVATE)
            .getString("items", null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = ArrayList<AlertItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    AlertItem(
                        o.getLong("t"), o.getString("s"), o.getDouble("p"),
                        o.getDouble("c"), o.getDouble("r"), o.getDouble("v")
                    )
                )
            }
            _items.value = list
        } catch (e: Exception) {
            // historique illisible : on repart de zéro
        }
    }

    @Synchronized
    fun add(c: Context, news: List<AlertItem>) {
        load(c)
        if (news.isEmpty()) return
        val all = (news + _items.value).take(MAX)
        _items.value = all
        save(c, all)
    }

    @Synchronized
    fun clear(c: Context) {
        load(c)
        _items.value = emptyList()
        save(c, emptyList())
    }

    private fun save(c: Context, list: List<AlertItem>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("t", a.time).put("s", a.symbol).put("p", a.price)
                    .put("c", a.changePct).put("r", a.ratio).put("v", a.volUsdt)
            )
        }
        c.getSharedPreferences("history", Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }
}
