package com.btcvolume.notifier

import android.content.Context
import android.content.SharedPreferences

data class Config(
    val interval: String = "5m",
    val volThreshold: Double = 3.0,
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
            favorites = (p.getStringSet("favorites", emptySet()) ?: emptySet()).toSet(),
            excluded = (p.getStringSet("excluded", emptySet()) ?: emptySet()).toSet()
        )
    }

    fun save(c: Context, cfg: Config) {
        sp(c).edit()
            .putString("interval", cfg.interval)
            .putString("volThreshold", cfg.volThreshold.toString())
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
            .putStringSet("favorites", HashSet(cfg.favorites))
            .putStringSet("excluded", HashSet(cfg.excluded))
            .apply()
    }

    fun isRunning(c: Context): Boolean = sp(c).getBoolean("running", false)

    fun setRunning(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("running", value).apply()
    }
}
