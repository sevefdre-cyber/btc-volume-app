package com.btcvolume.notifier

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

fun pnlColor(v: Double): Color = if (v >= 0) UpColor else DownColor

@Composable
fun EnvBadge(env: String) {
    val cs = MaterialTheme.colorScheme
    val label = when (env) {
        "REAL" -> "RÉEL"
        "TESTNET" -> "TESTNET"
        else -> "SIMULATION"
    }
    val bg = when (env) {
        "REAL" -> DownColor
        "TESTNET" -> cs.primaryContainer
        else -> cs.secondaryContainer
    }
    val fg = when (env) {
        "REAL" -> Color.White
        "TESTNET" -> cs.onPrimaryContainer
        else -> cs.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp
        )
    }
}

// ---------------------------------------------------------------- graphique en chandeliers

@Composable
fun CandleChart(candles: List<Candle>, lines: List<Pair<Double, Color>>) {
    val up = UpColor
    val down = DownColor
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
    ) {
        if (candles.isEmpty()) return@Canvas
        val cw = size.width
        val ch = size.height
        val volH = ch * 0.2f
        val priceH = ch - volH - 8f
        var hi = candles.maxOf { it.high }
        var lo = candles.minOf { it.low }
        for (l in lines) {
            hi = max(hi, l.first)
            lo = min(lo, l.first)
        }
        val range = if (hi - lo > 0) hi - lo else 1.0
        val maxV = candles.maxOf { it.vol }.let { if (it > 0) it else 1.0 }
        val slot = cw / candles.size
        val bodyW = slot * 0.7f

        fun y(p: Double): Float = ((hi - p) / range * priceH).toFloat()

        candles.forEachIndexed { i, cd ->
            val x = i * slot + slot / 2
            val col = if (cd.close >= cd.open) up else down
            drawLine(color = col, start = Offset(x, y(cd.high)), end = Offset(x, y(cd.low)), strokeWidth = 2f)
            val top = y(max(cd.open, cd.close))
            val bottom = y(min(cd.open, cd.close))
            drawRect(
                color = col,
                topLeft = Offset(x - bodyW / 2, top),
                size = Size(bodyW, max(bottom - top, 2f))
            )
            val vh = (cd.vol / maxV * volH).toFloat()
            drawRect(
                color = col.copy(alpha = 0.4f),
                topLeft = Offset(x - bodyW / 2, ch - vh),
                size = Size(bodyW, vh)
            )
        }
        for (l in lines) {
            val yy = y(l.first)
            drawLine(
                color = l.second, start = Offset(0f, yy), end = Offset(cw, yy), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
        }
    }
}

// ---------------------------------------------------------------- cartes de position

@Composable
fun PositionCard(p: Position, price: Double?, onClose: (() -> Unit)?) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${p.symbol}/USDT · ${envLabel(p.mode)}", fontWeight = FontWeight.Bold)
                Text(
                    if (p.status == "OPEN") "OUVERTE" else "CLÔTURÉE · ${p.closeReason}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                )
            }
            Muted("Entrée ${fmtPrice(p.entryPrice)} · Qté ${fmtQty(p.qty)} · Coût ${fmtNum(Math.round(p.cost * 100) / 100.0)} USDT")
            Muted("TP ${fmtPrice(p.tp)} · SL ${fmtPrice(p.sl)}")
            if (p.status == "OPEN") {
                if (price != null) {
                    val latent = p.qty * price * 0.999 - p.cost
                    Text("Latent ${fmtUsd(latent)}", color = pnlColor(latent), fontWeight = FontWeight.SemiBold)
                }
                if (onClose != null) {
                    Text(
                        "Clôturer au marché",
                        color = DownColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(vertical = 6.dp)
                    )
                }
            } else {
                Text(
                    "Résultat ${fmtUsd(p.pnl)} (sortie ${fmtPrice(p.exitPrice)})",
                    color = pnlColor(p.pnl), fontWeight = FontWeight.SemiBold
                )
            }
            val closedTxt = if (p.closeTime > 0) " · clôturée ${fmt.format(Date(p.closeTime))}" else ""
            Muted("Ouverte ${fmt.format(Date(p.openTime))}$closedTxt")
        }
    }
}

// ---------------------------------------------------------------- onglet Trade

@Composable
fun TradeTab(cfg: Config, update: (Config) -> Unit, tc: TradeConfig, updateTc: (TradeConfig) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val positions by Positions.items.collectAsState()
    val alerts by History.items.collectAsState()
    var msg by remember { mutableStateOf("") }
    var confirmPanic by remember { mutableStateOf(false) }
    var toClose by remember { mutableStateOf<Position?>(null) }
    var strategyOpen by remember { mutableStateOf(false) }
    var openText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                prices = withContext(Dispatchers.IO) { Binance.allPrices() }
            } catch (e: Exception) {
                // réseau indisponible : on réessaie
            }
            delay(15_000)
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    if (confirmPanic) {
        AlertDialog(
            onDismissRequest = { confirmPanic = false },
            title = { Text("Arrêt d'urgence") },
            text = {
                Text("Le trading sera désactivé et toutes les positions ouvertes seront vendues au marché (ordres de protection annulés). Continuer ?")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPanic = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Trader.panic(ctx) }
                        msg = r.message
                        updateTc(TradePrefs.load(ctx))
                    }
                }) { Text("Tout arrêter", color = DownColor) }
            },
            dismissButton = { TextButton(onClick = { confirmPanic = false }) { Text("Annuler") } }
        )
    }

    val closing = toClose
    if (closing != null) {
        AlertDialog(
            onDismissRequest = { toClose = null },
            title = { Text("Clôturer ${closing.symbol} ?") },
            text = { Text("La position sera vendue au marché maintenant (${envLabel(closing.mode)}).") },
            confirmButton = {
                TextButton(onClick = {
                    toClose = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Trader.closeNow(ctx, closing.id, "MANUEL") }
                        msg = r.message
                    }
                }) { Text("Clôturer", color = DownColor) }
            },
            dismissButton = { TextButton(onClick = { toClose = null }) { Text("Annuler") } }
        )
    }

    val sel = selected
    if (sel != null) {
        TokenDetail(
            symbol = sel, tc = tc, prices = prices, positions = positions,
            onBack = { selected = null }, onClose = { toClose = it }
        )
        return
    }

    val mine = positions.filter { it.mode == tc.env }
    val openList = mine.filter { it.status == "OPEN" }
    val closedList = mine.filter { it.status == "CLOSED" }
    val total = closedList.sumOf { it.pnl }
    val today = Trader.dailyPnl(positions, tc.env)
    val unreal = openList.sumOf { p ->
        val pr = prices[p.symbol + "USDT"] ?: p.entryPrice
        p.qty * pr * 0.999 - p.cost
    }
    val wins = closedList.count { it.pnl > 0 }
    val rate = if (closedList.isEmpty()) "–" else "${wins * 100 / closedList.size} %"
    val picked = remember(alerts) { alerts.map { it.symbol }.distinct().take(30) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Title("Trade")
                EnvBadge(tc.env)
            }
        }

        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Muted("Profit total (${envLabel(tc.env)})")
                            Text(fmtUsd(total), color = pnlColor(total), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Muted("Aujourd'hui")
                            Text(fmtUsd(today), color = pnlColor(today), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Muted("Latent ${fmtUsd(unreal)} · Positions ${openList.size}/${tc.maxPositions} · Réussite $rate")
                    Muted("Limite de perte du jour : ${fmtNum(tc.dailyLossLimit)} USDT · Résultats estimés (frais BNB non inclus)")
                    SwitchRow(
                        "Trading activé",
                        "Interrupteur général. Désactivé : aucun ordre n'est envoyé.",
                        tc.enabled
                    ) { updateTc(tc.copy(enabled = it)) }
                    if (tc.env == "REAL") {
                        Text("⚠️ Mode RÉEL : de l'argent réel est engagé.", color = DownColor, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { confirmPanic = true },
                        colors = ButtonDefaults.buttonColors(containerColor = DownColor, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🛑 ARRÊT D'URGENCE") }
                    if (msg.isNotEmpty()) Muted(msg)
                }
            }
        }

        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { strategyOpen = !strategyOpen },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚙️ Stratégie & configuration spot", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(if (strategyOpen) "▴" else "▾", fontSize = 18.sp)
                    }
                    if (strategyOpen) {
                        Muted("Achat au marché du montant fixé, puis vente automatique par un ordre OCO placé sur Binance : take-profit au-dessus, stop-loss en dessous. Si l'OCO est refusé, la position est revendue aussitôt.")
                        Text("Déclenchement des ordres")
                        ChoiceRow(
                            listOf("MANUAL" to "Manuel", "SEMI" to "Semi-auto", "AUTO" to "Automatique"),
                            tc.trigger
                        ) { updateTc(tc.copy(trigger = it)) }
                        Muted(
                            when (tc.trigger) {
                                "SEMI" -> "Chaque alerte affiche un bouton « Acheter » dans la notification. Rien n'est acheté sans votre appui."
                                "AUTO" -> "L'application achète seule les alertes qui respectent les filtres ci-dessous et les protections."
                                else -> "Vous achetez vous-même depuis la fiche du jeton, après confirmation."
                            }
                        )
                        NumField("Montant par trade (USDT)", tc.amountUsdt) { updateTc(tc.copy(amountUsdt = it)) }
                        NumField("Take-profit (%)", tc.tpPct) { updateTc(tc.copy(tpPct = it)) }
                        NumField("Stop-loss obligatoire (%)", tc.slPct) { updateTc(tc.copy(slPct = it)) }
                        NumField("Nombre max de positions ouvertes", tc.maxPositions.toDouble()) {
                            updateTc(tc.copy(maxPositions = it.toInt().coerceAtLeast(0)))
                        }
                        NumField("Limite de perte par jour (USDT)", tc.dailyLossLimit) { updateTc(tc.copy(dailyLossLimit = it)) }
                        if (tc.env == "SIM") {
                            NumField("Glissement simulé (%)", tc.simSlippagePct) { updateTc(tc.copy(simSlippagePct = it)) }
                        }
                        if (tc.trigger == "AUTO") {
                            NumField("Ratio de volume minimum pour acheter (×)", tc.autoMinRatio) {
                                updateTc(tc.copy(autoMinRatio = it))
                            }
                            SwitchRow(
                                "Acheter seulement si la bougie monte",
                                "Ignore les pics de volume accompagnés d'une baisse de prix",
                                tc.buyOnlyUp
                            ) { updateTc(tc.copy(buyOnlyUp = it)) }
                            if (tc.env == "REAL") {
                                SwitchRow(
                                    "Autoriser l'automatique en réel",
                                    "Sans cette case, aucun achat automatique n'est fait en mode réel.",
                                    tc.realAutoOk
                                ) { updateTc(tc.copy(realAutoOk = it)) }
                            }
                        }
                        Muted("Le stop-loss et la limite de perte journalière sont obligatoires : sans eux, aucun achat n'est envoyé.")
                    }
                }
            }
        }

        item { JournalCard(closedList, tc) }
        item { BacktestCard(cfg, tc, update, updateTc) }

        item { Text("Jetons sélectionnés par les alertes", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = openText,
                    onValueChange = { openText = it },
                    label = { Text("Ouvrir un jeton (ex : DOGE)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val s = openText.trim().uppercase().removeSuffix("USDT")
                    if (s.isNotEmpty()) selected = s
                    openText = ""
                }) { Text("Ouvrir") }
            }
        }
        if (picked.isEmpty()) {
            item { Muted("Aucun jeton pour le moment. Les jetons détectés par le scanner apparaîtront ici.") }
        }
        items(picked) { s ->
            val a = alerts.first { it.symbol == s }
            val live = prices[s + "USDT"]
            AppCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = s }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Muted("Prix ${if (live != null) fmtPrice(live) else fmtPrice(a.price)} USDT")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        RatioBadge(a.ratio)
                        Text(fmtPct(a.changePct), color = pnlColor(a.changePct), fontSize = 13.sp)
                    }
                    Text("›", fontSize = 24.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        item { Text("Positions ouvertes (${openList.size})", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
        if (openList.isEmpty()) item { Muted("Aucune position ouverte.") }
        items(openList, key = { it.id }) { p ->
            PositionCard(p, prices[p.symbol + "USDT"]) { toClose = p }
        }

        item { Text("Historique des opérations", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
        if (closedList.isEmpty()) item { Muted("Aucune opération clôturée.") }
        items(closedList.take(30), key = { it.id }) { p -> PositionCard(p, null, null) }
    }
}

// ---------------------------------------------------------------- fiche d'un jeton

@Composable
fun TokenDetail(
    symbol: String,
    tc: TradeConfig,
    prices: Map<String, Double>,
    positions: List<Position>,
    onBack: () -> Unit,
    onClose: (Position) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var interval by remember { mutableStateOf("5m") }
    var candles by remember(symbol, interval) { mutableStateOf<List<Candle>>(emptyList()) }
    var err by remember(symbol, interval) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val pair = symbol + "USDT"

    LaunchedEffect(symbol, interval) {
        while (true) {
            try {
                candles = withContext(Dispatchers.IO) { Binance.candles(pair, interval, 80) }
                err = null
            } catch (e: Exception) {
                err = e.message
            }
            delay(15_000)
        }
    }

    val mine = positions.filter { it.symbol == symbol && it.mode == tc.env }
    val openPos = mine.filter { it.status == "OPEN" }
    val lines = ArrayList<Pair<Double, Color>>()
    for (p in openPos) {
        lines.add(Pair(p.entryPrice, MaterialTheme.colorScheme.primary))
        lines.add(Pair(p.tp, UpColor))
        lines.add(Pair(p.sl, DownColor))
    }
    val live = prices[pair] ?: candles.lastOrNull()?.close

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Confirmer l'achat") },
            text = {
                Text(
                    "Mode : ${envLabel(tc.env)}" + (if (tc.env == "REAL") " (ARGENT RÉEL)" else "") +
                        "\nAchat au marché de ${fmtNum(tc.amountUsdt)} USDT de $symbol" +
                        "\nTake-profit +${fmtNum(tc.tpPct)} % · Stop-loss −${fmtNum(tc.slPct)} %"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Trader.buy(ctx, symbol, "MANUAL") }
                        msg = r.message
                        busy = false
                    }
                }) { Text("Acheter") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } }
        )
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            EnvBadge(tc.env)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Title("$symbol/USDT")
            if (live != null) Text(fmtPrice(live), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        ChoiceRow(
            listOf("1m" to "1 min", "5m" to "5 min", "15m" to "15 min", "1h" to "1 h"),
            interval
        ) { interval = it }

        AppCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (candles.isEmpty()) {
                    Muted(err ?: "Chargement du graphique…")
                } else {
                    CandleChart(candles, lines)
                    val hi = candles.maxOf { it.high }
                    val lo = candles.minOf { it.low }
                    Muted("Haut ${fmtPrice(hi)} · Bas ${fmtPrice(lo)} · ${candles.size} bougies · données publiques Binance")
                    if (openPos.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("– – Entrée", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            Text("– – TP", color = UpColor, fontSize = 12.sp)
                            Text("– – SL", color = DownColor, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Acheter", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Muted("${fmtNum(tc.amountUsdt)} USDT · TP +${fmtNum(tc.tpPct)} % · SL −${fmtNum(tc.slPct)} % · ${envLabel(tc.env)}")
                if (!tc.enabled) Muted("Le trading est désactivé : activez l'interrupteur général dans l'onglet Trade.")
                Button(
                    onClick = { confirm = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Envoi en cours…" else "Acheter ${fmtNum(tc.amountUsdt)} USDT de $symbol") }
                if (msg.isNotEmpty()) Muted(msg)
            }
        }

        Text("Opérations sur $symbol", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        if (mine.isEmpty()) Muted("Aucune opération pour ce jeton dans ce mode.")
        for (p in mine) {
            PositionCard(p, live, if (p.status == "OPEN") ({ onClose(p) }) else null)
        }
    }
}

// ---------------------------------------------------------------- Réglages : API Binance

@Composable
fun KeyEditor(env: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember(env) { mutableStateOf("") }
    var secret by remember(env) { mutableStateOf("") }
    var hint by remember(env) { mutableStateOf<String?>(Secrets.get(ctx, "key_$env")?.takeLast(4)) }
    var result by remember(env) { mutableStateOf("") }
    var busy by remember(env) { mutableStateOf(false) }

    Muted(
        if (hint != null) "Clé enregistrée (se termine par …$hint). Le secret n'est jamais affiché."
        else "Aucune clé enregistrée pour cet environnement."
    )
    OutlinedTextField(
        value = key, onValueChange = { key = it },
        label = { Text("API Key") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = secret, onValueChange = { secret = it },
        label = { Text("Secret Key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (key.isNotBlank() && secret.isNotBlank()) {
                    Secrets.put(ctx, "key_$env", key.trim())
                    Secrets.put(ctx, "secret_$env", secret.trim())
                    hint = key.trim().takeLast(4)
                    key = ""
                    secret = ""
                    result = "Clés enregistrées (chiffrées)."
                } else {
                    result = "Renseignez la clé et le secret."
                }
            },
            modifier = Modifier.weight(1f)
        ) { Text("Enregistrer") }
        OutlinedButton(
            onClick = {
                Secrets.remove(ctx, "key_$env")
                Secrets.remove(ctx, "secret_$env")
                hint = null
                result = "Clés supprimées."
            },
            modifier = Modifier.weight(1f)
        ) { Text("Supprimer") }
    }
    OutlinedButton(
        onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { Trader.testConnection(ctx, env) }
                result = r.message
                busy = false
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy) "Test en cours…" else "Tester la connexion") }
    if (result.isNotEmpty()) Muted(result)
    Muted(
        if (env == "REAL") "Sur Binance (Gestion des API) : cochez uniquement « Activer le trading spot ». Ne cochez JAMAIS les retraits."
        else "Clés de test : testnet.binance.vision (connexion avec GitHub, puis « Generate HMAC-SHA-256 Key »)."
    )
}

@Composable
fun ApiSection(tc: TradeConfig, updateTc: (TradeConfig) -> Unit) {
    var askReal by remember { mutableStateOf(false) }
    if (askReal) {
        AlertDialog(
            onDismissRequest = { askReal = false },
            title = { Text("Passer en mode RÉEL ?") },
            text = {
                Text("Les ordres utiliseront votre vrai compte Binance et de l'argent réel. Vous pouvez perdre tout ou partie des montants engagés. Le trading sera désactivé : vous devrez le réactiver vous-même dans l'onglet Trade.")
            },
            confirmButton = {
                TextButton(onClick = {
                    askReal = false
                    updateTc(tc.copy(env = "REAL", enabled = false))
                }) { Text("Je comprends, passer en réel", color = DownColor) }
            },
            dismissButton = { TextButton(onClick = { askReal = false }) { Text("Annuler") } }
        )
    }
    Section("API Binance (trading spot)") {
        Text("Environnement")
        ChoiceRow(
            listOf("SIM" to "Simulation", "TESTNET" to "Testnet", "REAL" to "Réel"),
            tc.env
        ) { sel ->
            if (sel != tc.env) {
                if (sel == "REAL") askReal = true else updateTc(tc.copy(env = sel, enabled = false))
            }
        }
        Muted(
            when (tc.env) {
                "SIM" -> "Simulation : aucun ordre envoyé à Binance, aucune clé requise. Prix réels, résultats fictifs."
                "TESTNET" -> "Testnet : ordres de test sur testnet.binance.vision avec de l'argent fictif. Clés séparées des clés réelles. Tous les jetons n'y sont pas disponibles."
                else -> "Réel : ordres exécutés sur votre compte Binance avec de l'argent réel."
            }
        )
        if (tc.env != "SIM") KeyEditor(tc.env)
    }
}

// ---------------------------------------------------------------- Journal & statistiques

@Composable
fun BucketBlock(title: String, rows: List<Bucket>) {
    if (rows.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    for (b in rows) {
        Muted("${b.label} : ${b.n} trade(s) · ${b.winPct} % gagnants · moyenne ${fmtUsd(b.avgPnl)}")
    }
}

@Composable
fun JournalCard(closed: List<Position>, tc: TradeConfig) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val st = Journal.stats(closed)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Réinitialiser le journal ?") },
            text = {
                Text("Supprime les opérations clôturées du mode ${envLabel(tc.env)} (statistiques et profit total). Les positions ouvertes sont conservées.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Positions.clearClosed(ctx, tc.env)
                }) { Text("Réinitialiser", color = DownColor) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } }
        )
    }

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📈 Journal & statistiques", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(if (open) "▴" else "▾", fontSize = 18.sp)
            }
            if (open) {
                if (st == null) {
                    Muted("Pas encore d'opération clôturée dans ce mode.")
                } else {
                    Muted("${st.n} trades · réussite ${st.wins * 100 / st.n} % · espérance ${fmtUsd(st.expectancy)} par trade")
                    Muted("Gain moyen ${fmtUsd(st.avgWin)} · perte moyenne ${fmtUsd(st.avgLoss)} · facteur de profit ${fmtPf(st.profitFactor)}")
                    Muted("Repli maximal ${fmtUsd(-st.maxDd)} · glissement moyen à l'entrée ${fmtPct(st.avgSlip)}")
                    BucketBlock("Par ratio de volume", Journal.buckets(closed, { it.sigRatio }, listOf(5.0, 10.0), "×"))
                    BucketBlock("Par mèche haute de la bougie", Journal.buckets(closed, { it.sigWick }, listOf(20.0, 40.0), " %"))
                    BucketBlock("Par hausse sur 24 h", Journal.buckets(closed, { it.sigChg24 }, listOf(10.0, 30.0), " %"))
                    BucketBlock("Par variation de la bougie", Journal.buckets(closed, { it.sigChange }, listOf(2.0, 5.0), " %"))
                    BucketBlock("Par type de sortie", Journal.byReason(closed))
                }
                Muted("Les tranches ne comptent que les achats issus d'une alerte (automatique ou semi-auto). Avec moins de 30 trades, les chiffres restent peu fiables.")
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Réinitialiser le journal")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Backtest et balayage

fun winPct(r: BtResult): Int = if (r.n == 0) 0 else r.wins * 100 / r.n

@Composable
fun BtLine(label: String, r: BtResult) {
    Text(label, fontWeight = FontWeight.SemiBold)
    if (r.n == 0) {
        Muted("Aucun signal sur la période avec ces réglages.")
    } else {
        Text(
            "${r.n} trades · réussite ${winPct(r)} % · total ${fmtPct(r.totalPct)} · ${fmtPct(r.avgPct)} par trade",
            color = pnlColor(r.totalPct), fontSize = 13.sp
        )
        Muted("Gain moyen ${fmtPct(r.avgWinPct)} · perte moyenne ${fmtPct(r.avgLossPct)} · facteur de profit ${fmtPf(r.profitFactor)} · repli max ${fmtPct(-r.maxDdPct)}")
        Muted("Sorties : TP ${r.tpN} · SL ${r.slN} · suiveur ${r.trailN} · durée max ${r.timeN}")
    }
}

fun sweepLabel(r: SweepRow): String {
    val range = if (r.maxR > 0) "${fmtNum(r.minR)}×–${fmtNum(r.maxR)}×" else "≥ ${fmtNum(r.minR)}×"
    return "${r.interval} · volume $range · TP ${fmtNum(r.tp)} % / SL ${fmtNum(r.sl)} %"
}

@Composable
fun SweepRowCard(rank: Int, r: SweepRow, onApply: () -> Unit) {
    val confirmed = r.valid.n >= 15 && r.valid.avgPct > 0 && r.valid.profitFactor > 1.1
    val verdict = when {
        confirmed -> "✓ confirmé hors échantillon"
        r.valid.n < 15 -> "? trop peu de trades en validation"
        else -> "✗ non confirmé hors échantillon"
    }
    AppCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#$rank · ${sweepLabel(r)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Optimisation : ${r.train.n} trades · ${fmtPct(r.train.avgPct)} par trade · PF ${fmtPf(r.train.profitFactor)} · réussite ${winPct(r.train)} %",
                color = pnlColor(r.train.avgPct), fontSize = 12.sp
            )
            Text(
                "Validation : ${r.valid.n} trades · ${fmtPct(r.valid.avgPct)} par trade · PF ${fmtPf(r.valid.profitFactor)} · réussite ${winPct(r.valid)} %",
                color = pnlColor(r.valid.avgPct), fontSize = 12.sp
            )
            Muted("Sorties en validation : TP ${r.valid.tpN} · SL ${r.valid.slN} · durée max ${r.valid.timeN}")
            Text(
                verdict,
                color = if (confirmed) UpColor else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp
            )
            TextButton(onClick = onApply) { Text("Appliquer ces réglages") }
        }
    }
}

@Composable
fun BacktestCard(
    cfg: Config,
    tc: TradeConfig,
    update: (Config) -> Unit,
    updateTc: (TradeConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<BtResult, BtResult>?>(null) }
    var sweep by remember { mutableStateOf<List<SweepRow>?>(null) }
    var count by remember { mutableStateOf(60.0) }
    var trail by remember { mutableStateOf(2.0) }
    var hold by remember { mutableStateOf(48.0) }

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🧪 Backtest et balayage", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(if (open) "▴" else "▾", fontSize = 18.sp)
            }
            if (open) {
                Muted("Rejoue vos règles (filtres de scan, intervalle de volume, TP, SL, glissement) sur l'historique récent des cryptos les plus échangées de votre sélection. L'historique est court (environ 3 jours en bougies de 5 min, 10 jours en 15 min) : avec peu de trades, le résultat est peu fiable.")
                NumField("Nombre de cryptos testées", count) { count = it }
                NumField("Durée maximale d'un trade (bougies)", hold) { hold = it }

                Text("Test de vos réglages actuels", fontWeight = FontWeight.SemiBold)
                NumField("Stop suiveur (%) activé après le TP", trail) { trail = it }
                Button(
                    onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    Backtester.run(
                                        cfg, tc, count.toInt().coerceIn(5, 200), trail, hold.toInt(),
                                        { status = it }
                                    )
                                }
                                result = r
                                status = "Terminé."
                            } catch (e: Exception) {
                                status = "Échec : ${e.message}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Calcul en cours…" else "Lancer le backtest") }
                val r = result
                if (r != null) {
                    BtLine("A · TP fixe +${fmtNum(tc.tpPct)} % / SL −${fmtNum(tc.slPct)} %", r.first)
                    BtLine("B · Stop suiveur ${fmtNum(trail)} % après le TP", r.second)
                }

                Text("Balayage automatique des réglages", fontWeight = FontWeight.SemiBold)
                Muted("Teste environ 90 combinaisons (bougies 5 et 15 min, volume minimum et maximum, TP/SL) avec sortie à TP fixe. Les 60 % les plus anciens de la période servent à choisir, les 40 % les plus récents à vérifier. Seule la validation compte.")
                Button(
                    onClick = {
                        busy = true
                        sweep = null
                        scope.launch {
                            try {
                                val rows = withContext(Dispatchers.IO) {
                                    Backtester.sweep(cfg, tc, count.toInt().coerceIn(5, 200), hold.toInt()) { status = it }
                                }
                                sweep = rows
                                status = "Terminé."
                            } catch (e: Exception) {
                                status = "Échec : ${e.message}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Calcul en cours…" else "Lancer le balayage") }
                if (status.isNotEmpty()) Muted(status)

                val rows = sweep
                if (rows != null) {
                    val eligible = rows.filter { it.train.n >= 30 }
                    val ranked = eligible.sortedByDescending { it.train.avgPct }.take(5)
                    val holdUp = eligible.count { it.train.avgPct > 0 && it.valid.n >= 15 && it.valid.avgPct > 0 }
                    val positiveTrain = eligible.count { it.train.avgPct > 0 }
                    Muted("${rows.size} combinaisons testées · ${eligible.size} avec au moins 30 trades en optimisation · $holdUp restent positives en validation sur $positiveTrain positives en optimisation.")
                    if (ranked.isEmpty()) {
                        Muted("Aucune combinaison avec au moins 30 trades en optimisation : augmentez le nombre de cryptos ou assouplissez les filtres.")
                    }
                    ranked.forEachIndexed { idx, row ->
                        SweepRowCard(idx + 1, row) {
                            update(cfg.copy(interval = row.interval, volThreshold = row.minR, volMax = row.maxR))
                            updateTc(tc.copy(tpPct = row.tp, slPct = row.sl, autoMinRatio = row.minR))
                            status = "Réglages appliqués : ${sweepLabel(row)}"
                        }
                    }
                    Muted("Avec autant de combinaisons, certaines paraissent bonnes par pur hasard. Une combinaison n'est crédible que si elle reste positive en validation, avec assez de trades, et idéalement si vous la retestez plus tard sur une nouvelle période.")
                }
                Muted("Hypothèses : signal à la clôture de la bougie, entrée à l'ouverture suivante, frais 0,2 % aller-retour, glissement ${fmtNum(tc.simSlippagePct)} %, SL supposé touché avant le TP dans une même bougie. Limite de positions, filtre Bitcoin et profondeur du carnet d'ordres non simulés.")
            }
        }
    }
}
