package com.btcvolume.notifier

import android.Manifest
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- écran principal

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Prefs.load(ctx)) }
    val update: (Config) -> Unit = {
        cfg = it
        Prefs.save(ctx, it)
    }
    var tab by remember { mutableStateOf(0) }
    var tc by remember { mutableStateOf(TradePrefs.load(ctx)) }
    val updateTc: (TradeConfig) -> Unit = {
        tc = it
        TradePrefs.save(ctx, it)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val tabs = listOf(
        Triple(OutIcons.Scan, FillIcons.Scan, "Scanner"),
        Triple(OutIcons.Alerts, FillIcons.Alerts, "Alertes"),
        Triple(OutIcons.Trade, FillIcons.Trade, "Trade"),
        Triple(OutIcons.Star, FillIcons.Star, "Listes"),
        Triple(OutIcons.Tune, FillIcons.Tune, "Réglages")
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, pair ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) pair.second else pair.first, contentDescription = null) },
                        label = { Text(pair.third) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ScannerTab(cfg, update)
                1 -> AlertsTab()
                2 -> TradeTab(cfg, update, tc, updateTc)
                3 -> ListsTab(cfg, update)
                else -> SettingsTab(cfg, update, tc, updateTc)
            }
        }
    }
}

// ---------------------------------------------------------------- composants communs

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (LocalDark.current) 0.dp else 2.dp),
        content = content
    )
}

@Composable
fun RatioBadge(ratio: Double) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = fmtRatio(ratio),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/** Nom d'un jeton : lien souligné vers sa page de trading sur Binance. */
@Composable
fun TokenLink(symbol: String, size: TextUnit = 16.sp, suffix: String = "") {
    val uri = LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable {
            try {
                uri.openUri("https://www.binance.com/fr/trade/${symbol}_USDT?type=spot")
            } catch (e: Exception) {
                // aucun navigateur disponible
            }
        }
    ) {
        Text(
            symbol + suffix,
            fontWeight = FontWeight.Bold,
            fontSize = size,
            textDecoration = TextDecoration.Underline
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            OutIcons.Open,
            contentDescription = "Ouvrir $symbol/USDT sur Binance",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size((size.value * 0.85f).dp)
        )
    }
}

@Composable
fun IconTitle(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---------------------------------------------------------------- onglet Scanner

@Composable
fun ScannerTab(cfg: Config, update: (Config) -> Unit) {
    val ctx = LocalContext.current
    val running by ScanState.running.collectAsState()
    val status by ScanState.status.collectAsState()
    val rows by ScanState.rows.collectAsState()
    val monitored by ScanState.monitored.collectAsState()
    val total by ScanState.totalPairs.collectAsState()
    val btc by ScanState.btc.collectAsState()
    var view by remember { mutableStateOf("SCAN") }
    val liveMap by LiveState.ticks.collectAsState()
    if (view == "RANK") {
        RankingScreen { view = it }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("Volume Scanner") }
        item { ChoiceRow(listOf("SCAN" to "Scan par bougie", "RANK" to "Classement live"), view) { view = it } }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(if (running) UpColor else DownColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (running) "Surveillance active" else "Arrêtée",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Muted(status)
                    if (btc.isNotEmpty()) Muted(btc)
                    if (running && monitored > 0) {
                        Muted("$monitored cryptos surveillées sur $total paires USDT")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (running) VolumeService.stop(ctx) else VolumeService.start(ctx)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (running) "Arrêter" else "Démarrer") }
                        if (running) {
                            OutlinedButton(
                                onClick = { VolumeService.forceScan = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("Scanner") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Cryptos analysées (${rows.size}) · triées par ratio de volume",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (rows.isEmpty()) {
            item { Muted("Rien à afficher. Démarrez la surveillance : le premier scan prend quelques secondes.") }
        }
        items(rows, key = { it.symbol }) { r -> ScanRowCard(r, cfg, update, liveMap[r.symbol + "USDT"]) }
    }
}

@Composable
fun ScanRowCard(r: ScanRow, cfg: Config, update: (Config) -> Unit, live: Tick?) {
    val fav = r.symbol in cfg.favorites
    AppCard {
        Row(
            Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val px = live?.price ?: r.price
            val candlePct = if (live != null && r.curOpen > 0.0) (live.price - r.curOpen) / r.curOpen * 100.0 else r.changePct
            val pct24 = if (live != null && live.open24 > 0.0) (live.price - live.open24) / live.open24 * 100.0 else null
            Column(Modifier.weight(1f)) {
                TokenLink(r.symbol)
                Muted("${fmtPrice(px)} USDT · Vol ${fmtVol(r.volUsdt)}")
                if (pct24 != null) Muted("24 h ${fmtPct(pct24)}")
            }
            Column(horizontalAlignment = Alignment.End) {
                RatioBadge(r.ratio)
                Text(
                    fmtPct(candlePct),
                    color = if (candlePct >= 0) UpColor else DownColor,
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = {
                val f = if (fav) cfg.favorites - r.symbol else cfg.favorites + r.symbol
                update(cfg.copy(favorites = f))
            }) {
                Icon(
                    if (fav) FillIcons.Star else OutIcons.Star,
                    contentDescription = if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = {
                update(cfg.copy(excluded = cfg.excluded + r.symbol, favorites = cfg.favorites - r.symbol))
            }) {
                Icon(
                    OutIcons.Block,
                    contentDescription = "Exclure ${r.symbol}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- onglet Alertes

@Composable
fun AlertsTab() {
    val ctx = LocalContext.current
    val list by History.items.collectAsState()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Title("Historique (${list.size})")
                if (list.isNotEmpty()) {
                    TextButton(onClick = { History.clear(ctx) }) { Text("Effacer") }
                }
            }
        }
        if (list.isEmpty()) {
            item { Muted("Aucune alerte pour le moment. Les pics détectés apparaîtront ici.") }
        }
        items(list) { a ->
            AppCard {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        TokenLink(a.symbol)
                        Muted("${fmt.format(Date(a.time))} · ${fmtPrice(a.price)} USDT")
                        Muted("Vol bougie ${fmtVol(a.volUsdt)} USDT")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        RatioBadge(a.ratio)
                        Text(
                            fmtPct(a.changePct),
                            color = if (a.changePct >= 0) UpColor else DownColor,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- onglet Listes

@Composable
fun ListsTab(cfg: Config, update: (Config) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Title("Listes") }
        item { IconTitle(FillIcons.Star, "Favoris") }
        item { Muted("Toujours surveillés, même hors du filtre de prix et de volume.") }
        item { SymbolEditor(cfg.favorites) { update(cfg.copy(favorites = it)) } }
        item { IconTitle(OutIcons.Block, "Exclusions") }
        item { Muted("Jamais surveillées, quoi qu'il arrive.") }
        item { SymbolEditor(cfg.excluded) { update(cfg.copy(excluded = it)) } }
    }
}

@Composable
fun SymbolEditor(symbols: Set<String>, onChange: (Set<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Ex : DOGE") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val s = text.trim().uppercase().removeSuffix("USDT")
                    if (s.isNotEmpty()) onChange(symbols + s)
                    text = ""
                }) { Text("Ajouter") }
            }
            if (symbols.isEmpty()) Muted("Aucune crypto dans cette liste.")
            for (s in symbols.sorted()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TokenLink(s)
                    TextButton(onClick = { onChange(symbols - s) }) { Text("Retirer") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- onglet Réglages

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        for (o in options) {
            FilterChip(
                selected = selected == o.first,
                onClick = { onSelect(o.first) },
                label = { Text(o.second) }
            )
        }
    }
}

@Composable
fun NumField(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(fmtNum(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Muted(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsTab(cfg: Config, update: (Config) -> Unit, tc: TradeConfig, updateTc: (TradeConfig) -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Title("Réglages")

        Section("Apparence") {
            Text("Thème")
            ChoiceRow(
                listOf("SYSTEM" to "Automatique", "LIGHT" to "Clair", "DARK" to "Sombre"),
                cfg.themeMode
            ) {
                ThemeState.mode.value = it
                update(cfg.copy(themeMode = it))
            }
            Muted("Automatique suit le réglage de votre téléphone.")
        }

        Section("Données en direct") {
            SwitchRow(
                "Prix en direct (WebSocket)",
                "Met à jour prix et pourcentages chaque seconde quand l'écran est visible, et alimente le classement. Consomme des données : Wi-Fi conseillé.",
                cfg.liveOn
            ) {
                update(cfg.copy(liveOn = it))
                if (it) LiveFeed.acquire(ctx, "ui") else LiveFeed.release("ui")
            }
        }

        Section("Détection") {
            Text("Taille des bougies")
            ChoiceRow(
                listOf("1m" to "1 min", "5m" to "5 min", "15m" to "15 min", "1h" to "1 heure"),
                cfg.interval
            ) { update(cfg.copy(interval = it)) }
            NumField("Volume minimum (× la moyenne)", cfg.volThreshold) {
                update(cfg.copy(volThreshold = it))
            }
            NumField("Volume maximum (×, 0 = pas de maximum)", cfg.volMax) {
                update(cfg.copy(volMax = it))
            }
            Muted("Intervalle de volume : sous le minimum le signal est trop faible, au-dessus du maximum le mouvement est probablement déjà trop avancé.")
            NumField("Explosion de prix : hausse minimale de la bougie (%, 0 = désactivé)", cfg.explosionMin) {
                update(cfg.copy(explosionMin = it))
            }
            Text("Type d'alerte")
            ChoiceRow(
                listOf("VOLUME" to "Volume seul", "VOLUME_PRICE" to "Volume + prix"),
                cfg.mode
            ) { update(cfg.copy(mode = it)) }
            if (cfg.mode == "VOLUME_PRICE") {
                NumField("Mouvement de prix minimum (%)", cfg.priceThreshold) {
                    update(cfg.copy(priceThreshold = it))
                }
            }
            NumField("Délai de repos par crypto (minutes)", cfg.cooldownMin.toDouble()) {
                update(cfg.copy(cooldownMin = it.toInt().coerceAtLeast(0)))
            }
            NumField("Mèche haute maximale (% de la bougie, 100 = désactivé)", cfg.maxWickPct) {
                update(cfg.copy(maxWickPct = it))
            }
            NumField("Hausse 24 h maximale (%, 0 = désactivé)", cfg.max24hPct) {
                update(cfg.copy(max24hPct = it))
            }
        }

        Section("Volatilité et tendance") {
            NumField("Volatilité minimale (ATR en % du prix, 0 = désactivé)", cfg.atrMin) {
                update(cfg.copy(atrMin = it))
            }
            NumField("Volatilité maximale (ATR %, 0 = désactivé)", cfg.atrMax) {
                update(cfg.copy(atrMax = it))
            }
            NumField("Expansion de volatilité minimale (×, 0 = désactivé)", cfg.rangeMinRatio) {
                update(cfg.copy(rangeMinRatio = it))
            }
            SwitchRow(
                "Filtre de tendance",
                "Écarte les cryptos en tendance baissière : moyenne des 10 dernières bougies sous celle des 30",
                cfg.trendFilter
            ) { update(cfg.copy(trendFilter = it)) }
            if (cfg.trendFilter) {
                NumField("Tolérance de baisse de la tendance (%)", cfg.trendTolPct) {
                    update(cfg.copy(trendTolPct = it))
                }
            }
            SwitchRow(
                "Bougie haussière uniquement",
                "Écarte les pics de volume accompagnés d'une baisse de prix",
                cfg.upCandleOnly
            ) { update(cfg.copy(upCandleOnly = it)) }
        }

        Section("Bitcoin : état du marché") {
            Muted("Les nouvelles ne se mesurent pas directement, mais Bitcoin y réagit en quelques minutes : on s'en sert comme baromètre du climat de marché.")
            SwitchRow(
                "Filtre Bitcoin",
                "Aucune alerte quand l'état de Bitcoin est défavorable",
                cfg.btcFilter
            ) { update(cfg.copy(btcFilter = it)) }
            if (cfg.btcFilter) {
                NumField("Baisse maximale de BTC sur environ 1 h (%)", cfg.btcDropPct) {
                    update(cfg.copy(btcDropPct = it))
                }
                SwitchRow(
                    "Exiger une tendance BTC non baissière",
                    "Moyenne 10 bougies ≥ moyenne 30 (bougies de 15 min)",
                    cfg.btcTrendFilter
                ) { update(cfg.copy(btcTrendFilter = it)) }
                NumField("Choc de volatilité BTC maximal (×, 0 = désactivé)", cfg.btcShockMax) {
                    update(cfg.copy(btcShockMax = it))
                }
            }
        }

        Section("Sélection des cryptos (paires USDT)") {
            NumField("Prix minimum (USDT)", cfg.priceMin) { update(cfg.copy(priceMin = it)) }
            NumField("Prix maximum (USDT)", cfg.priceMax) { update(cfg.copy(priceMax = it)) }
            NumField("Volume 24h minimum (USDT)", cfg.minVol24h) { update(cfg.copy(minVol24h = it)) }
            NumField("Nombre maximum de cryptos analysées", cfg.maxSymbols.toDouble()) {
                update(cfg.copy(maxSymbols = it.toInt().coerceAtLeast(1)))
            }
            Muted("Les favoris ignorent ces filtres. Les stablecoins sont exclus automatiquement.")
        }

        Section("Notifications") {
            ChoiceRow(
                listOf("SUMMARY" to "Résumé groupé", "INDIVIDUAL" to "Une par crypto"),
                cfg.notifMode
            ) { update(cfg.copy(notifMode = it)) }
            Muted("Résumé : une seule notification avec le top 5. Une par crypto : maximum 10 par scan.")
        }

        Section("Système") {
            SwitchRow(
                "Démarrage automatique",
                "Relance la surveillance après un redémarrage du téléphone",
                cfg.autoStart
            ) { update(cfg.copy(autoStart = it)) }
            SwitchRow(
                "Garder le CPU actif",
                "Plus fiable écran éteint, consomme un peu plus. Effet au prochain démarrage.",
                cfg.wakeLock
            ) { update(cfg.copy(wakeLock = it)) }
            OutlinedButton(
                onClick = {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Désactiver l'optimisation batterie") }
        }

        ApiSection(tc, updateTc)

        Muted("Les alertes sont des signaux d'information, pas des conseils d'investissement.")
    }
}
