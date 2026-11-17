package com.btcvolume.notifier

/**
 * Coût d'exécution estimé par jeton. Pour un petit ordre (15 USDT), l'essentiel du coût est la moitié de
 * l'écart acheteur-vendeur, qui dépend de la liquidité. Les paliers ci-dessous sont des estimations calées
 * sur les écarts mesurés (médiane 0,07 % pour les jetons liquides, jusqu'à 0,47 % pour les petits) :
 * à affiner avec les relevés du journal.
 */
object CostModel {
    const val EXTRA = 0.02   // impact supplémentaire (%) d'un petit ordre

    /** Glissement par côté (%) selon le volume échangé sur 24 h, en USDT. */
    fun tierSlip(qv24: Double): Double = when {
        qv24 >= 100e6 -> 0.03
        qv24 >= 30e6 -> 0.05
        qv24 >= 10e6 -> 0.08
        qv24 >= 3e6 -> 0.15
        qv24 >= 1e6 -> 0.25
        else -> 0.40
    }

    /** Pour un historique : volume quotidien moyen déduit des bougies. */
    fun forSeries(cs: List<Candle>, ms: Long): Double {
        if (cs.isEmpty()) return 0.25
        var s = 0.0
        for (x in cs) s += x.vol * x.close
        return tierSlip(s / cs.size * (86_400_000.0 / ms))
    }

    /** En direct : volume 24 h du flux, ou une valeur prudente si le jeton n'est pas suivi. */
    fun liveSlip(base: String): Double = tierSlip(LiveState.ticks.value[base + "USDT"]?.qv ?: 2_000_000.0)
}
