package com.btcvolume.notifier

import java.math.BigDecimal
import java.util.Locale

fun fmtPrice(p: Double): String = when {
    p >= 1000 -> String.format(Locale.US, "%,.2f", p)
    p >= 1 -> String.format(Locale.US, "%.4f", p)
    p >= 0.01 -> String.format(Locale.US, "%.5f", p)
    else -> String.format(Locale.US, "%.8f", p)
}

fun fmtVol(v: Double): String = when {
    v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.0f", v)
}

fun fmtRatio(r: Double): String = String.format(Locale.US, "×%.1f", r)

fun fmtPct(p: Double): String = String.format(Locale.US, "%+.2f%%", p)

fun fmtNum(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

fun fmtUsd(v: Double): String = String.format(Locale.US, "%+.2f USDT", v)

fun fmtQty(q: Double): String =
    BigDecimal(q).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

fun fmtPf(d: Double): String = if (d.isInfinite()) "∞" else String.format(Locale.US, "%.2f", d)


/** Stablecoins, monnaies fiat et paires « collées » à 1 USDT : jamais surveillés. */
object Stables {
    private val KNOWN = setOf(
        "USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "USDE", "USD1", "AEUR", "EUR", "EURI",
        "XUSD", "UST", "USDS", "PYUSD", "RLUSD", "USDD", "GUSD", "FRAX", "LUSD", "SUSD", "CRVUSD",
        "GHO", "USDG", "BFUSD", "USDX", "USDT", "VAI", "IDRT", "BIDR", "BVND",
        "GBP", "TRY", "BRL", "ARS", "UAH", "PLN", "RON", "CZK", "JPY", "MXN", "COP", "ZAR", "NGN", "RUB"
    )

    fun isStable(base: String, price: Double, high: Double, low: Double): Boolean {
        if (base in KNOWN) return true
        // détection dynamique : prix proche de 1 et variation sur 24 h très faible
        if (price in 0.9..1.1 && low > 0 && (high - low) / low * 100.0 < 1.5) return true
        return false
    }
}

class BtcInfo(val chg1h: Double, val trend: Double, val shock: Double)

/** État de Bitcoin utilisé comme indicateur du climat de marché (il réagit vite aux nouvelles). */
object BtcRules {
    fun blocked(cfg: Config, chg1h: Double, trend: Double, shock: Double): Boolean =
        (cfg.btcDropPct > 0 && chg1h < -cfg.btcDropPct) ||
            (cfg.btcTrendFilter && trend < 0) ||
            (cfg.btcShockMax > 0 && shock > cfg.btcShockMax)

    fun reason(cfg: Config, i: BtcInfo): String? = when {
        cfg.btcDropPct > 0 && i.chg1h < -cfg.btcDropPct -> "BTC en baisse (" + fmtPct(i.chg1h) + " sur 1 h)"
        cfg.btcTrendFilter && i.trend < 0 -> "tendance BTC baissière"
        cfg.btcShockMax > 0 && i.shock > cfg.btcShockMax -> "choc de volatilité BTC (x" + String.format(Locale.US, "%.1f", i.shock) + ")"
        else -> null
    }
}
