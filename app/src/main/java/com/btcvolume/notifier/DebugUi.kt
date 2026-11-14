package com.btcvolume.notifier

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DebugSection(cfg: Config, update: (Config) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(DebugLog.sizeMb(ctx)) }
    Section("Journal de debug") {
        Muted("Enregistre les scans (avec le motif de rejet de chaque jeton), les décisions des stratégies, les ordres, les positions avec leur plus haut et leur plus bas, l'état du flux et les erreurs. Aucune clé API ni secret n'est écrit. Les résultats détaillés des backtests sont toujours enregistrés, même journal éteint.")
        SwitchRow(
            "Journal de debug",
            "Écrit les événements dans des fichiers sur le téléphone (rotation automatique).",
            cfg.debugOn
        ) {
            update(cfg.copy(debugOn = it))
            DebugLog.set(ctx, it, cfg.debugRec)
        }
        SwitchRow(
            "Enregistreurs de marché",
            "Classement du top 30 toutes les 10 s et écarts acheteur-vendeur toutes les 5 min (environ 7 Mo par jour). Demande le flux en direct.",
            cfg.debugRec
        ) {
            update(cfg.copy(debugRec = it))
            DebugLog.set(ctx, cfg.debugOn, it)
        }
        Muted("Taille actuelle des journaux : " + String.format(Locale.US, "%.1f", size) + " Mo")
        Button(
            onClick = {
                scope.launch {
                    msg = try {
                        withContext(Dispatchers.IO) { DebugLog.exportZip(ctx) }
                    } catch (e: Exception) {
                        "Échec de l'export : ${e.message}"
                    }
                    size = DebugLog.sizeMb(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer dans Téléchargements (zip)") }
        OutlinedButton(
            onClick = {
                DebugLog.clear(ctx)
                size = DebugLog.sizeMb(ctx)
                msg = "Journaux effacés."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Effacer les journaux") }
        if (msg.isNotEmpty()) Muted(msg)
    }
}
