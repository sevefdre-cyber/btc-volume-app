package com.btcvolume.notifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetectorSection(cfg: Config, update: (Config) -> Unit) {
    Section("Détecteur de mouvements") {
        Muted("Repère les jetons dont le prix bouge brusquement sur quelques minutes, les classe du plus actif au moins actif et envoie une notification « X est le plus actif ». Utilise le flux en direct : Wi-Fi conseillé.")
        SwitchRow("Notifications du détecteur", "Fonctionne aussi écran éteint tant que le service du Scanner est démarré.", cfg.detOn) {
            update(cfg.copy(detOn = it))
        }
        NumField("Fenêtre d'observation (minutes)", cfg.detWindowMin.toDouble()) { update(cfg.copy(detWindowMin = it.toInt().coerceIn(1, 30))) }
        NumField("Mouvement minimal sur la fenêtre (%)", cfg.detMovePct) { update(cfg.copy(detMovePct = it)) }
        NumField("Repos par jeton après une notification (minutes)", cfg.detCooldownMin.toDouble()) { update(cfg.copy(detCooldownMin = it.toInt().coerceAtLeast(0))) }
        NumField("Délai minimal entre deux notifications (secondes)", cfg.detGapSec.toDouble()) { update(cfg.copy(detGapSec = it.toInt().coerceAtLeast(0))) }
        NumField("Jetons listés dans la notification", cfg.detTop.toDouble()) { update(cfg.copy(detTop = it.toInt().coerceIn(1, 10))) }
        SwitchRow("Hausses seulement", "Désactivé : les baisses brusques comptent aussi.", cfg.detUpOnly) {
            update(cfg.copy(detUpOnly = it))
        }
    }
}

@Composable
fun ActiveScreen(onView: (String) -> Unit) {
    val rows by Detector.active.collectAsState()
    val status by LiveState.status.collectAsState()
    val warm by RankEngine.warmMin.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("Volume Scanner") }
        item {
            ChoiceRow(
                listOf("SCAN" to "Scan par bougie", "RANK" to "Classement live", "ACT" to "Plus actifs"),
                "ACT"
            ) { onView(it) }
        }
        item {
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(status, fontWeight = FontWeight.SemiBold)
                    Muted("Jetons classés par ampleur du mouvement sur la fenêtre du détecteur (Réglages > Détecteur de mouvements). Historique disponible : $warm min.")
                }
            }
        }
        if (rows.isEmpty()) item { Muted("Aucun mouvement à afficher : le flux en direct doit être actif et l'historique d'au moins une minute.") }
        items(rows, key = { it.symbol }) { r ->
            AppCard {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${rows.indexOf(r) + 1}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        TokenLink(r.symbol)
                        Muted("${fmtPrice(r.price)} USDT · rang 24 h ${r.rank}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtPct(r.move), color = pnlColor(r.move), fontWeight = FontWeight.Bold)
                        RankDelta(r.d5)
                    }
                }
            }
        }
    }
}
