package com.btcvolume.notifier

import android.content.Context
import android.content.SharedPreferences

data class Config(
    val interval: String = "5m",
    val volThreshold: Double = 3.0,         // ratio de volume minimum
    val volMax: Double = 0.0,               // ratio de volume maximum, 0 = pas de maximum
    val explosionMin: Double = 0.0,         // explosion de prix : hausse minimale de la bougie (%), 0 = désactivé
    val mode: String = "VOLUME",            // VOLUME ou VOLUME_PRICE
    val priceThreshold: Double = 1.0,       // % de mouvement de prix (mode VOLUME_PRICE)
    val notifMode: String = "SUMMARY",      // SUMMARY ou INDIVIDUAL
    val priceMin: Double = 0.0,
    val priceMax: Double = 1_000_000.0,
    val minVol24h: Double = 1_000_000.0,    // USDT
    val cooldownMin: Int = 30,
    val maxSymbols: Int = 300,
    val autoStart: Boolean = false,
    val wakeLock: Boolean = true,
    val debugOn: Boolean = false,             // journal de debug
    val debugRec: Boolean = true,             // enregistreurs de marché (classement, écarts)
    val liveOn: Boolean = true,              // prix en direct (WebSocket) quand l'écran est visible
    val themeMode: String = "SYSTEM",       // SYSTEM, LIGHT ou DARK
    val maxWickPct: Double = 40.0,          // mèche haute max (% de la bougie), 100 = désactivé
    val max24hPct: Double = 50.0,           // hausse 24h max (%), 0 = désactivé
    val btcFilter: Boolean = true,
    val btcDropPct: Double = 1.0,           // baisse BTC (~1 h) au-delà de laquelle on bloque
    val btcTrendFilter: Boolean = true,     // BTC : moyenne 10 >= moyenne 30 (bougies de 15 min)
    val btcShockMax: Double = 3.0,          // choc de volatilité BTC maximal (x), 0 = désactivé
    val atrMin: Double = 0.1,               // volatilité minimale (ATR en % du prix), 0 = désactivé
    val atrMax: Double = 0.0,               // volatilité maximale (ATR %), 0 = désactivé
    val rangeMinRatio: Double = 1.5,        // expansion de volatilité minimale (x), 0 = désactivé
    val trendFilter: Boolean = true,        // écarte les cryptos en tendance baissière (MA10 < MA30)
    val trendTolPct: Double = 0.0,          // tolérance de baisse MA10/MA30 (%)
    val upCandleOnly: Boolean = true,       // bougie du signal haussière uniquement
    val favorites: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet()
)

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg2", Context.MODE_PRIVATE)

    private fun SharedPreferences.dbl(key: String, def: Double): Double =
        getString(key, null)?.toDoubleOrNull() ?: def

    fun load(c: Context): Config {
        val p = sp(c)
        val d = Config()
        return Config(
            interval = p.getString("interval", d.interval) ?: d.interval,
            volThreshold = p.dbl("volThreshold", d.volThreshold),
            volMax = p.dbl("volMax", d.volMax),
            explosionMin = p.dbl("explosionMin", d.explosionMin),
            mode = p.getString("mode", d.mode) ?: d.mode,
            priceThreshold = p.dbl("priceThreshold", d.priceThreshold),
            notifMode = p.getString("notifMode", d.notifMode) ?: d.notifMode,
            priceMin = p.dbl("priceMin", d.priceMin),
            priceMax = p.dbl("priceMax", d.priceMax),
            minVol24h = p.dbl("minVol24h", d.minVol24h),
            cooldownMin = p.getInt("cooldownMin", d.cooldownMin),
            maxSymbols = p.getInt("maxSymbols", d.maxSymbols),
            autoStart = p.getBoolean("autoStart", d.autoStart),
            wakeLock = p.getBoolean("wakeLock", d.wakeLock),
            debugOn = p.getBoolean("debugOn", d.debugOn),
            debugRec = p.getBoolean("debugRec", d.debugRec),
            liveOn = p.getBoolean("liveOn", d.liveOn),
            themeMode = p.getString("themeMode", d.themeMode) ?: d.themeMode,
            maxWickPct = p.dbl("maxWickPct", d.maxWickPct),
            max24hPct = p.dbl("max24hPct", d.max24hPct),
            btcFilter = p.getBoolean("btcFilter", d.btcFilter),
            btcDropPct = p.dbl("btcDropPct", d.btcDropPct),
            btcTrendFilter = p.getBoolean("btcTrendFilter", d.btcTrendFilter),
            btcShockMax = p.dbl("btcShockMax", d.btcShockMax),
            atrMin = p.dbl("atrMin", d.atrMin),
            atrMax = p.dbl("atrMax", d.atrMax),
            rangeMinRatio = p.dbl("rangeMinRatio", d.rangeMinRatio),
            trendFilter = p.getBoolean("trendFilter", d.trendFilter),
            trendTolPct = p.dbl("trendTolPct", d.trendTolPct),
            upCandleOnly = p.getBoolean("upCandleOnly", d.upCandleOnly),
            favorites = (p.getStringSet("favorites", emptySet()) ?: emptySet()).toSet(),
            excluded = (p.getStringSet("excluded", emptySet()) ?: emptySet()).toSet()
        )
    }

    fun save(c: Context, cfg: Config) {
        sp(c).edit()
            .putString("interval", cfg.interval)
            .putString("volThreshold", cfg.volThreshold.toString())
            .putString("volMax", cfg.volMax.toString())
            .putString("explosionMin", cfg.explosionMin.toString())
            .putString("mode", cfg.mode)
            .putString("priceThreshold", cfg.priceThreshold.toString())
            .putString("notifMode", cfg.notifMode)
            .putString("priceMin", cfg.priceMin.toString())
            .putString("priceMax", cfg.priceMax.toString())
            .putString("minVol24h", cfg.minVol24h.toString())
            .putInt("cooldownMin", cfg.cooldownMin)
            .putInt("maxSymbols", cfg.maxSymbols)
            .putBoolean("autoStart", cfg.autoStart)
            .putBoolean("wakeLock", cfg.wakeLock)
            .putBoolean("debugOn", cfg.debugOn)
            .putBoolean("debugRec", cfg.debugRec)
            .putBoolean("liveOn", cfg.liveOn)
            .putString("themeMode", cfg.themeMode)
            .putString("maxWickPct", cfg.maxWickPct.toString())
            .putString("max24hPct", cfg.max24hPct.toString())
            .putBoolean("btcFilter", cfg.btcFilter)
            .putString("btcDropPct", cfg.btcDropPct.toString())
            .putBoolean("btcTrendFilter", cfg.btcTrendFilter)
            .putString("btcShockMax", cfg.btcShockMax.toString())
            .putString("atrMin", cfg.atrMin.toString())
            .putString("atrMax", cfg.atrMax.toString())
            .putString("rangeMinRatio", cfg.rangeMinRatio.toString())
            .putBoolean("trendFilter", cfg.trendFilter)
            .putString("trendTolPct", cfg.trendTolPct.toString())
            .putBoolean("upCandleOnly", cfg.upCandleOnly)
            .putStringSet("favorites", HashSet(cfg.favorites))
            .putStringSet("excluded", HashSet(cfg.excluded))
            .apply()
    }

    fun isRunning(c: Context): Boolean = sp(c).getBoolean("running", false)

    fun setRunning(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("running", value).apply()
    }
}
