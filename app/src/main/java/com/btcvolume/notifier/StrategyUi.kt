package com.btcvolume.notifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun StratHeader(icon: ImageVector, title: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconTitle(icon, title, MaterialTheme.colorScheme.primary)
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/** Les trois stratégies, intégrées au menu « Stratégie & configuration spot ». */
@Composable
fun StrategyPack(cfg: Config, tc: TradeConfig) {
    val ctx = LocalContext.current
    var sc by remember { mutableStateOf(StratPrefs.load(ctx)) }
    val set: (StratConfig) -> Unit = {
        sc = it
        StratPrefs.save(ctx, it)
    }
    Text("Stratégies automatiques", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    Muted("Désactivées par défaut. Elles utilisent les protections communes (stop-loss obligatoire, perte maximale par jour, nombre maximal de positions, arrêt d'urgence) et le mode choisi dans Réglages (simulation, Testnet ou réel). Testez-les d'abord avec le backtest puis en simulation.")
    RankStrategyCard(sc, set, cfg, tc)
    RsiStrategyCard(sc, set, tc)
    WallStrategyCard(sc, set, cfg, tc)
}

@Composable
fun RankStrategyCard(sc: StratConfig, set: (StratConfig) -> Unit, cfg: Config, tc: TradeConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by StrategyRunner.rankStatus.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<RankBtRow>?>(null) }
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StratHeader(OutIcons.Trade, "1 · Montée dans le classement", sc.rankOn) { set(sc.copy(rankOn = it)) }
            Muted("Le classement trie les jetons par variation sur 24 h glissantes : (prix actuel − prix d'ouverture) / prix d'ouverture × 100. On entre quand un jeton grimpe vite vers le haut du classement, on sort quand son rang se dégrade. Il faut que le flux en direct soit actif (service démarré ou écran visible) et que « Trading activé » soit sur oui.")
            NumField("Entrer si le rang est dans le top (N)", sc.rankTopN.toDouble()) { set(sc.copy(rankTopN = it.toInt().coerceAtLeast(1))) }
            NumField("… et a gagné au moins (places)", sc.rankGain.toDouble()) { set(sc.copy(rankGain = it.toInt().coerceAtLeast(1))) }
            NumField("… sur la fenêtre (minutes)", sc.rankWindowMin.toDouble()) { set(sc.copy(rankWindowMin = it.toInt().coerceIn(1, 60))) }
            NumField("Variation 24 h minimale (%)", sc.rankVarMin) { set(sc.copy(rankVarMin = it)) }
            NumField("Variation 24 h maximale (%, évite de courir après)", sc.rankVarMax) { set(sc.copy(rankVarMax = it)) }
            NumField("Sortir si le rang dépasse", sc.rankExitBelow.toDouble()) { set(sc.copy(rankExitBelow = it.toInt().coerceAtLeast(1))) }
            NumField("Sortir si le rang recule de (places depuis le meilleur)", sc.rankExitDrop.toDouble()) { set(sc.copy(rankExitDrop = it.toInt().coerceAtLeast(1))) }
            NumField("Durée maximale (minutes)", sc.rankMaxHoldMin.toDouble()) { set(sc.copy(rankMaxHoldMin = it.toInt().coerceAtLeast(1))) }
            NumField("Take-profit de sécurité (%)", sc.rankTp) { set(sc.copy(rankTp = it)) }
            NumField("Stop-loss obligatoire (%)", sc.rankSl) { set(sc.copy(rankSl = it)) }
            if (status.isNotEmpty()) Muted(status)
            OutlinedButton(
                onClick = {
                    busy = true
                    rows = null
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) {
                                RankBacktest.run(ctx, cfg, tc, sc, 300) { msg = it }
                            }
                            rows = r
                            msg = "Terminé."
                        } catch (e: Exception) {
                            msg = "Échec : ${e.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Calcul en cours…" else "Backtest (90 jours, bougies 5 min)") }
            if (msg.isNotEmpty()) Muted(msg)
            val r = rows
            if (r != null) {
                for (row in r.take(5)) {
                    Text("Top ${row.topN} · au moins +${row.gain} places", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    BtLine("Optimisation (60 % anciens)", row.train)
                    BtLine("Validation (40 % récents)", row.valid)
                }
                Muted("Classement reconstitué toutes les 5 minutes à partir de l'historique local : les mouvements plus rapides que 5 minutes ne sont pas visibles. Biais de survie et glissement uniforme.")
            }
        }
    }
}

@Composable
fun RsiStrategyCard(sc: StratConfig, set: (StratConfig) -> Unit, tc: TradeConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by StrategyRunner.rsiStatus.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<IndRow>?>(null) }
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StratHeader(OutIcons.Assess, "2 · RSI + MACD", sc.rsiOn) { set(sc.copy(rsiOn = it)) }
            Muted("Entrée à la clôture d'une bougie quand l'histogramme MACD (12, 26, 9) passe au-dessus de zéro et que le RSI (14) est dans la zone choisie et en hausse. Sortie quand le RSI devient trop haut ou que le MACD repasse sous zéro.")
            Text("Taille des bougies")
            ChoiceRow(listOf("5m" to "5 min", "15m" to "15 min", "1h" to "1 heure"), sc.rsiTf) { set(sc.copy(rsiTf = it)) }
            NumField("RSI minimal à l'entrée", sc.rsiMin) { set(sc.copy(rsiMin = it)) }
            NumField("RSI maximal à l'entrée", sc.rsiMax) { set(sc.copy(rsiMax = it)) }
            NumField("Sortir si le RSI atteint", sc.rsiExit) { set(sc.copy(rsiExit = it)) }
            NumField("Jetons analysés (les plus échangés)", sc.rsiUniverse.toDouble()) { set(sc.copy(rsiUniverse = it.toInt().coerceIn(5, 200))) }
            NumField("Take-profit (%)", sc.rsiTp) { set(sc.copy(rsiTp = it)) }
            NumField("Stop-loss obligatoire (%)", sc.rsiSl) { set(sc.copy(rsiSl = it)) }
            if (status.isNotEmpty()) Muted(status)
            OutlinedButton(
                onClick = {
                    busy = true
                    rows = null
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) {
                                IndicatorBacktest.run(ctx, tc, sc, 300) { msg = it }
                            }
                            rows = r
                            msg = "Terminé."
                        } catch (e: Exception) {
                            msg = "Échec : ${e.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Calcul en cours…" else "Backtest (5 min, 15 min, 1 h)") }
            if (msg.isNotEmpty()) Muted(msg)
            val r = rows
            if (r != null) {
                for (row in r) {
                    BtLine("Bougies ${row.tf} · toute la période", row.all)
                    BtLine("Bougies ${row.tf} · validation (40 % récents)", row.valid)
                }
                Muted("Mêmes réglages sur chaque taille de bougie. Durée maximale d'un trade : 48 bougies. 5 min : 3 mois d'historique, 15 min et 1 h : 12 mois.")
            }
        }
    }
}

@Composable
fun WallStrategyCard(sc: StratConfig, set: (StratConfig) -> Unit, cfg: Config, tc: TradeConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by StrategyRunner.wallStatus.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<WallResult?>(null) }
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StratHeader(OutIcons.Alerts, "3 · Ouverture de Wall Street", sc.wsOn) { set(sc.copy(wsOn = it)) }
            Muted("Pendant la fenêtre choisie (heure locale du téléphone), achète les jetons qui ont déjà monté d'au moins X % sur les dernières minutes, dans la limite d'entrées par jour. Attention : l'ouverture réelle de Wall Street (9h30 à New York) tombe à 15h30 en Tunisie en hiver et à 14h30 en été.")
            NumField("Heure de début (heure locale)", sc.wsHour.toDouble()) { set(sc.copy(wsHour = it.toInt().coerceIn(0, 23))) }
            NumField("Minute de début", sc.wsMinute.toDouble()) { set(sc.copy(wsMinute = it.toInt().coerceIn(0, 59))) }
            NumField("Durée de la fenêtre (minutes)", sc.wsDurationMin.toDouble()) { set(sc.copy(wsDurationMin = it.toInt().coerceAtLeast(1))) }
            NumField("Mouvement observé sur (minutes)", sc.wsLookbackMin.toDouble()) { set(sc.copy(wsLookbackMin = it.toInt().coerceIn(1, 60))) }
            NumField("Mouvement minimal (%)", sc.wsMinMovePct) { set(sc.copy(wsMinMovePct = it)) }
            NumField("Entrées maximales par jour", sc.wsMaxEntries.toDouble()) { set(sc.copy(wsMaxEntries = it.toInt().coerceAtLeast(1))) }
            NumField("Durée maximale d'un trade (minutes)", sc.wsHoldMin.toDouble()) { set(sc.copy(wsHoldMin = it.toInt().coerceAtLeast(1))) }
            SwitchRow("Jours ouvrés seulement", "Lundi à vendredi", sc.wsWeekdays) { set(sc.copy(wsWeekdays = it)) }
            NumField("Take-profit (%)", sc.wsTp) { set(sc.copy(wsTp = it)) }
            NumField("Stop-loss obligatoire (%)", sc.wsSl) { set(sc.copy(wsSl = it)) }
            if (status.isNotEmpty()) Muted(status)
            OutlinedButton(
                onClick = {
                    busy = true
                    res = null
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) {
                                WallStudy.run(ctx, cfg, tc, sc, 300) { msg = it }
                            }
                            res = r
                            msg = "Terminé."
                        } catch (e: Exception) {
                            msg = "Échec : ${e.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Calcul en cours…" else "Étudier l'heure (3 mois, bougies 5 min)") }
            if (msg.isNotEmpty()) Muted(msg)
            val r = res
            if (r != null) {
                Text("Créneaux les plus mouvementés (jours ouvrés, heure locale)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                for (s in r.slots) {
                    Muted("${s.label} · mouvement moyen ${fmtPct(s.avgAbs)} par bougie · volume ×${fmtNum(Math.round(s.avgRatio * 100) / 100.0)}")
                }
                Muted("Votre créneau (${sc.wsHour}:${if (sc.wsMinute < 10) "0" else ""}${sc.wsMinute}) se classe n° ${r.chosenRank} sur 48 en mouvement moyen.")
                BtLine("Même règle d'entrée, DANS la fenêtre", r.inWin)
                BtLine("Même règle d'entrée, HORS fenêtre (témoin)", r.outWin)
                Muted("Si la fenêtre ne fait pas nettement mieux que le témoin, l'heure n'apporte rien. ${r.symbols} cryptos analysées.")
            }
        }
    }
}

// ---------------------------------------------------------------- vue « Classement live »

@Composable
fun RankDelta(d: Int) {
    if (d == 0) {
        Muted("=")
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (d > 0) OutIcons.Up else OutIcons.Down,
                contentDescription = null,
                tint = if (d > 0) UpColor else DownColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "${abs(d)}",
                color = if (d > 0) UpColor else DownColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun RankingScreen(onView: (String) -> Unit) {
    val rows by RankEngine.rows.collectAsState()
    val status by LiveState.status.collectAsState()
    val micros by RankEngine.calcMicros.collectAsState()
    val warm by RankEngine.warmMin.collectAsState()
    val alerts by History.items.collectAsState()
    var onlyScan by remember { mutableStateOf(false) }
    val recent = alerts.filter { System.currentTimeMillis() - it.time < 3_600_000L }.map { it.symbol }.toSet()
    val shown = (if (onlyScan) rows.filter { it.symbol in recent } else rows).take(60)
    val calc = if (micros < 1000) "$micros µs" else String.format(java.util.Locale.US, "%.1f ms", micros / 1000.0)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("Volume Scanner") }
        item { ChoiceRow(listOf("SCAN" to "Scan par bougie", "RANK" to "Classement live"), "RANK") { onView(it) } }
        item {
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(status, fontWeight = FontWeight.SemiBold)
                    Muted("${rows.size} jetons classés en $calc · historique de classement : $warm min")
                    Muted("Variation = (prix actuel − prix d'ouverture 24 h) / prix d'ouverture × 100. Les flèches montrent les places gagnées ou perdues sur 15 minutes.")
                }
            }
        }
        item {
            ChoiceRow(
                listOf("ALL" to "Tous les jetons", "SCAN" to "Retenus par le scan (1 h)"),
                if (onlyScan) "SCAN" else "ALL"
            ) { onlyScan = it == "SCAN" }
        }
        if (shown.isEmpty()) item { Muted("Aucun jeton à afficher pour le moment. Le flux en direct doit être activé (Réglages > Données en direct).") }
        items(shown, key = { it.symbol }) { r ->
            AppCard {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${r.rank}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        TokenLink(r.symbol)
                        Muted("${fmtPrice(r.price)} USDT" + if (!r.v5.isNaN()) " · 5 min ${fmtPct(r.v5)}" else "")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtPct(r.varPct), color = pnlColor(r.varPct), fontWeight = FontWeight.Bold)
                        RankDelta(r.d15)
                    }
                }
            }
        }
    }
}
