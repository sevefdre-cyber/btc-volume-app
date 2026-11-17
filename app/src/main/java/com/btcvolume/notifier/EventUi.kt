package com.btcvolume.notifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun evLabel(r: EvRow): String {
    val m24 = if (r.max24 > 0) "≤ ${fmtNum(r.max24)} %" else "sans limite"
    val btc = when (r.btc) {
        0 -> "–"
        1 -> "doux"
        else -> "strict"
    }
    return "${r.interval} · hausse ≥ ${fmtNum(r.minChg)} % · volume ≥ ${fmtNum(r.minRatio)}× · mèche ≤ ${fmtNum(r.maxWick)} % · " +
        "hausse 24 h $m24 · tendance ${if (r.trend) "✓" else "–"} · BTC $btc · TP ${fmtNum(r.tp)} % / SL ${fmtNum(r.sl)} % · ${r.hold} bougies max"
}

@Composable
fun EvResultCard(
    r: EvResult,
    cfg: Config,
    tc: TradeConfig,
    update: (Config) -> Unit,
    updateTc: (TradeConfig) -> Unit
) {
    Muted("${r.total} combinaisons testées sur ${r.events} événements de hausse brutale.")
    Text(
        "Seuil de hasard : t ≥ ${fmtNum(Math.round(r.threshold * 100) / 100.0)} · ${r.above} combinaison(s) le dépassent",
        fontWeight = FontWeight.SemiBold, fontSize = 13.sp
    )
    Muted("Avec autant d'essais, le meilleur résultat d'une stratégie sans aucun avantage atteindrait environ ce seuil par pur hasard. Seules les combinaisons au-dessus sont crédibles, et même elles doivent rester positives en validation.")

    Text("Walk-forward : choisir sur le passé, juger sur la période suivante", fontWeight = FontWeight.SemiBold)
    r.folds.forEachIndexed { i, f ->
        val row = f.row
        val tr = f.train
        val va = f.valid
        if (row == null || tr == null || va == null) {
            Muted("Période ${i + 1} : aucune combinaison avec assez de trades.")
        } else {
            Text("Période ${i + 1} · ${evLabel(row)}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Muted("Optimisation : ${aggLine(tr)}")
            Text("Validation : ${aggLine(va)}", color = pnlColor(va.avg), fontSize = 12.sp)
        }
    }
    val p = r.pooled
    if (p.n > 0) {
        Text("Total validé : ${aggLine(p)}", color = pnlColor(p.avg), fontWeight = FontWeight.Bold)
        val verdict = when {
            p.n < 100 -> "? trop peu de trades validés pour conclure"
            p.avg > 0 && p.pf > 1.1 -> "✓ reste positif hors échantillon"
            else -> "✗ pas rentable hors échantillon"
        }
        Text(verdict, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }

    Text("Meilleures combinaisons (par statistique t, au moins 100 trades)", fontWeight = FontWeight.SemiBold)
    if (r.top.isEmpty()) Muted("Aucune combinaison avec au moins 100 trades.")
    r.top.take(6).forEachIndexed { i, t ->
        AppCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${i + 1} · ${evLabel(t)}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(aggLine(t.all), color = pnlColor(t.all.avg), fontSize = 12.sp)
                Muted("t = ${fmtNum(Math.round(t.t * 100) / 100.0)} (seuil ${fmtNum(Math.round(r.threshold * 100) / 100.0)})")
                TextButton(onClick = {
                    update(
                        cfg.copy(
                            interval = t.interval, explosionMin = t.minChg,
                            volThreshold = if (t.minRatio < 1.05) 1.05 else t.minRatio, volMax = 0.0,
                            maxWickPct = t.maxWick, max24hPct = t.max24, trendFilter = t.trend,
                            btcFilter = t.btc > 0, btcTrendTol = if (t.btc == 1) 0.5 else 0.0
                        )
                    )
                    updateTc(
                        tc.copy(
                            tpPct = t.tp, slPct = t.sl, autoMinRatio = if (t.minRatio < 1.05) 1.05 else t.minRatio,
                            holdMin = (t.hold * VolumeService.intervalMs(t.interval) / 60_000L).toInt()
                        )
                    )
                }) { Text("Appliquer ces réglages") }
            }
        }
    }
    Muted("Les coûts sont ceux du mode choisi (par jeton ou fixe). Biais de survie, historique de 5 min limité à 3 mois, SL supposé touché avant le TP dans une même bougie.")
}
