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
