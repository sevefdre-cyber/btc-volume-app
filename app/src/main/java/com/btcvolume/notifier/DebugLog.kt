package com.btcvolume.notifier

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Journal de debug : fichiers texte et CSV sur le téléphone, exportables en zip dans Téléchargements.
 * Jamais de clé API, de secret ni de signature. Les résultats de backtest sont toujours enregistrés.
 */
object DebugLog {
    @Volatile var on = false
    @Volatile var rec = true
    @Volatile private var appCtx: Context? = null
    private val lock = Any()
    private val track = ConcurrentHashMap<Long, DoubleArray>()     // id de position -> [plus haut, plus bas]
    @Volatile private var lastRank = 0L
    @Volatile private var lastSpread = 0L
    private const val CAP_BYTES = 150L * 1024 * 1024
    private const val BT_FILE = "backtests.log"

    fun f(d: Double, n: Int = 2): String =
        if (d.isNaN() || d.isInfinite()) d.toString() else String.format(Locale.US, "%." + n + "f", d)

    private fun dayStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun nowStamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    fun init(c: Context) {
        appCtx = c.applicationContext
        val cfg = Prefs.load(c)
        on = cfg.debugOn
        rec = cfg.debugRec
        prune(c)
        if (on) {
            val v = try {
                c.packageManager.getPackageInfo(c.packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            log("APP", "démarrage v$v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL} · fuseau ${TimeZone.getDefault().id}")
        }
    }

    fun set(c: Context, debugOn: Boolean, recOn: Boolean) {
        appCtx = c.applicationContext
        on = debugOn
        rec = recOn
        if (on) log("APP", "journal activé · enregistreurs de marché : $recOn")
    }

    private fun dir(): File? {
        val c = appCtx ?: return null
        val d = File(c.filesDir, "logs")
        d.mkdirs()
        return d
    }

    private fun append(name: String, line: String, header: String? = null) {
        val d = dir() ?: return
        try {
            synchronized(lock) {
                val file = File(d, name)
                if (header != null && !file.exists()) file.appendText(header + "\n")
                file.appendText(line + "\n")
            }
        } catch (e: Exception) {
            // le journal ne doit jamais faire planter l'application
        }
    }

    fun log(cat: String, msg: String) {
        if (!on) return
        append("events-" + dayStamp() + ".log", nowStamp() + " | " + cat + " | " + msg)
    }

    fun csv(base: String, header: String, line: String) {
        if (!on) return
        append(base + "-" + dayStamp() + ".csv", line, header)
    }

    fun prune(c: Context) {
        try {
            val d = File(c.filesDir, "logs")
            val files = d.listFiles() ?: return
            val now = System.currentTimeMillis()
            for (f in files) {
                val maxDays = if (f.name.startsWith("ranks") || f.name.startsWith("spreads")) 3 else 7
                if (f.name != BT_FILE && now - f.lastModified() > maxDays * 86_400_000L) f.delete()
            }
            val left = (d.listFiles() ?: return).sortedBy { it.lastModified() }.toMutableList()
            var total = left.sumOf { it.length() }
            while (total > CAP_BYTES && left.isNotEmpty()) {
                val f = left.removeAt(0)
                total -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            // ignoré
        }
    }

    // ---------------------------------------------------------------- plus haut / plus bas pendant une position

    fun trackPrice(id: Long, entry: Double, price: Double) {
        val a = track.getOrPut(id) { doubleArrayOf(entry, entry) }
        if (price > a[0]) a[0] = price
        if (price < a[1]) a[1] = price
    }

    fun extremes(id: Long): DoubleArray? = track.remove(id)

    /** Appelé chaque seconde par le flux en direct. */
    fun onTick(c: Context, snap: Map<String, Tick>) {
        for (p in Positions.items.value) {
            if (p.status != "OPEN") continue
            val t = snap[p.symbol + "USDT"] ?: continue
            trackPrice(p.id, p.entryPrice, t.price)
        }
        if (!on || !rec) return
        val now = System.currentTimeMillis()
        if (now - lastRank >= 10_000L) {
            lastRank = now
            val top = RankEngine.latest.take(30)
            if (top.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append(now)
                for (r in top) {
                    sb.append(';').append(r.symbol).append(',').append(r.rank).append(',').append(r.price)
                        .append(',').append(f(r.varPct, 3)).append(',').append(r.d15)
                }
                csv("ranks", "ts_ms;symbole,rang,prix,variation24h_pct,places15min", sb.toString())
            }
        }
        if (now - lastSpread >= 300_000L) {
            lastSpread = now
            Thread {
                try {
                    recordSpreads()
                } catch (e: Exception) {
                    log("ERREUR", "écarts acheteur-vendeur : ${e.message}")
                }
            }.start()
        }
    }

    private fun recordSpreads() {
        val syms = LinkedHashSet<String>()
        syms.add("BTCUSDT")
        for (r in RankEngine.latest.take(30)) syms.add(r.symbol + "USDT")
        for (p in Positions.items.value) if (p.status == "OPEN") syms.add(p.symbol + "USDT")
        for (r in RankEngine.latest.sortedByDescending { it.qv }.take(80)) syms.add(r.symbol + "USDT")
        val js = JSONArray()
        for (s in syms.take(100)) js.put(s)
        val arr = JSONArray(Binance.publicGet("REAL", "/api/v3/ticker/bookTicker", mapOf("symbols" to js.toString())))
        val ts = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bid = o.optString("bidPrice").toDoubleOrNull() ?: continue
            val ask = o.optString("askPrice").toDoubleOrNull() ?: continue
            if (bid <= 0.0 || ask <= 0.0) continue
            val mid = (bid + ask) / 2.0
            csv(
                "spreads", "ts_ms,symbole,bid,ask,spread_pct,qte_bid,qte_ask",
                "$ts,${o.getString("symbol")},$bid,$ask,${f((ask - bid) / mid * 100.0, 4)},${o.optString("bidQty")},${o.optString("askQty")}"
            )
        }
    }

    fun logClose(p: Position, q: Position) {
        log(
            "POSITION",
            "CLÔTURE id=${p.id} ${p.symbol} mode=${p.mode} strat=${p.strategy.ifEmpty { "-" }} raison=${q.closeReason} " +
                "entrée=${p.entryPrice} sortie=${q.exitPrice} qté=${p.qty} coût=${p.cost} pnl=${f(q.pnl, 4)} " +
                "durée=${(q.closeTime - p.openTime) / 60_000L}min plusHaut=${f(q.mfePct)}% plusBas=${f(q.maePct)}%"
        )
    }

    // ---------------------------------------------------------------- résultats de backtest (toujours enregistrés)

    private fun bt(r: BtResult): String =
        "n=${r.n} gagnants=${r.wins} total=${f(r.totalPct)}% moy=${f(r.avgPct, 3)}% gainMoy=${f(r.avgWinPct, 3)}% " +
            "perteMoy=${f(r.avgLossPct, 3)}% PF=${f(r.profitFactor)} repliMax=${f(r.maxDdPct)}% " +
            "TP=${r.tpN} SL=${r.slN} suiveur=${r.trailN} durée=${r.timeN}"

    private fun ag(a: Agg): String =
        "n=${a.n} gagnants=${a.wins} moy=${f(a.avg, 3)}% PF=${f(a.pf)} TP=${a.tp} SL=${a.sl} durée=${a.dur}"

    private fun ctxLine(cfg: Config, tc: TradeConfig): String =
        "réglages: bougies=${cfg.interval} volume=${cfg.volThreshold}-${cfg.volMax} explosion>=${cfg.explosionMin} " +
            "mèche<=${cfg.maxWickPct} hausse24h<=${cfg.max24hPct} atr=${cfg.atrMin}-${cfg.atrMax} expansion>=${cfg.rangeMinRatio} " +
            "tendance=${cfg.trendFilter} bougieHaussière=${cfg.upCandleOnly} btc=${cfg.btcFilter} repos=${cfg.cooldownMin}min " +
            "prix=${cfg.priceMin}-${cfg.priceMax} vol24h>=${cfg.minVol24h} | TP=${tc.tpPct}% SL=${tc.slPct}% " +
            "glissement=${tc.simSlippagePct}% montant=${tc.amountUsdt}"

    private fun btWrite(title: String, lines: List<String>) {
        val d = dir() ?: return
        try {
            synchronized(lock) {
                val file = File(d, BT_FILE)
                if (file.exists() && file.length() > 20L * 1024 * 1024) file.delete()
                val sb = StringBuilder()
                sb.append("=== ").append(nowStamp()).append(" · ").append(title).append(" ===\n")
                for (l in lines) sb.append(l).append('\n')
                sb.append('\n')
                file.appendText(sb.toString())
            }
        } catch (e: Exception) {
            // ignoré
        }
    }

    fun logAB(cfg: Config, tc: TradeConfig, trail: Double, a: BtResult, b: BtResult) {
        btWrite(
            "backtest A/B (API, bougies récentes)",
            listOf(ctxLine(cfg, tc), "stop suiveur=$trail%", "A TP fixe : " + bt(a), "B stop suiveur : " + bt(b))
        )
    }

    fun logSweep(title: String, cfg: Config, tc: TradeConfig, rows: List<SweepRow>) {
        val lines = ArrayList<String>()
        lines.add(ctxLine(cfg, tc))
        lines.add("combinaisons=${rows.size}")
        for (r in rows) {
            lines.add(
                "${r.interval};hausse>=${r.minChg};volume=${r.minR}-${r.maxR};TP=${r.tp};SL=${r.sl};tendance=${r.trend};btc=${r.btc}" +
                    " | optim: ${bt(r.train)} | valid: ${bt(r.valid)}"
            )
        }
        btWrite(title, lines)
    }

    fun logOffline(title: String, cfg: Config, tc: TradeConfig, r: OffResult) {
        val lines = ArrayList<String>()
        lines.add(ctxLine(cfg, tc))
        lines.add("combinaisons=${r.combos} cryptos=${r.symbols}${r.note}")
        for ((i, fo) in r.folds.withIndex()) {
            val ch = fo.chosen
            val tr = fo.train
            val va = fo.valid
            if (ch == null || tr == null || va == null) {
                lines.add("walk-forward période ${i + 1} : aucune combinaison")
            } else {
                lines.add("walk-forward période ${i + 1} : ${comboLabel(ch)} | optim: ${ag(tr)} | valid: ${ag(va)}")
            }
        }
        lines.add("total validé : ${ag(r.pooled)}")
        lines.add("effet des filtres : tendance avec=${f(r.effect[0], 3)} sans=${f(r.effect[1], 3)} | btc avec=${f(r.effect[2], 3)} sans=${f(r.effect[3], 3)}")
        for ((i, t) in r.tops.withIndex()) {
            lines.add("top ${i + 1} : ${comboLabel(t.combo)} | ${ag(t.all)} | tranches positives ${t.posBuckets}/${t.usedBuckets}")
        }
        lines.add("--- détail de toutes les combinaisons (total puis n:moyenne% par tranche de temps) ---")
        for ((cb, acc) in r.detail) {
            val per = (0 until OfflineBacktest.B).joinToString(" ") {
                val x = acc.range(it, it + 1)
                "${x.n}:${f(x.avg, 2)}"
            }
            lines.add("${comboLabel(cb)} | ${ag(acc.range(0, OfflineBacktest.B))} | $per")
        }
        btWrite(title, lines)
    }

    fun logRank(cfg: Config, tc: TradeConfig, sc: StratConfig, rows: List<RankBtRow>) {
        val lines = ArrayList<String>()
        lines.add(ctxLine(cfg, tc))
        lines.add("classement: fenêtre=${sc.rankWindowMin}min var24h=${sc.rankVarMin}-${sc.rankVarMax} sortie rang>${sc.rankExitBelow} ou recul>=${sc.rankExitDrop} durée<=${sc.rankMaxHoldMin}min TP=${sc.rankTp}% SL=${sc.rankSl}%")
        for (r in rows) lines.add("top=${r.topN} gain>=${r.gain} | optim: ${bt(r.train)} | valid: ${bt(r.valid)}")
        btWrite("backtest classement (90 jours, 5 min)", lines)
    }

    fun logInd(cfg: Config, tc: TradeConfig, sc: StratConfig, rows: List<IndRow>) {
        val lines = ArrayList<String>()
        lines.add(ctxLine(cfg, tc))
        lines.add("RSI+MACD: RSI ${sc.rsiMin}-${sc.rsiMax} sortie>=${sc.rsiExit} TP=${sc.rsiTp}% SL=${sc.rsiSl}%")
        for (r in rows) {
            lines.add("bougies ${r.tf} toute période | ${bt(r.all)}")
            lines.add("bougies ${r.tf} validation   | ${bt(r.valid)}")
        }
        btWrite("backtest RSI + MACD", lines)
    }

    fun logWall(cfg: Config, tc: TradeConfig, sc: StratConfig, r: WallResult) {
        val lines = ArrayList<String>()
        lines.add(ctxLine(cfg, tc))
        lines.add("Wall Street: début=${sc.wsHour}:${sc.wsMinute} durée=${sc.wsDurationMin}min mouvement>=${sc.wsMinMovePct}% sur ${sc.wsLookbackMin}min TP=${sc.wsTp}% SL=${sc.wsSl}% cryptos=${r.symbols}")
        for (s in r.slots) lines.add("créneau ${s.label} mouvementMoyen=${f(s.avgAbs, 3)}% ratioVolume=${f(s.avgRatio)}")
        lines.add("rang du créneau choisi : ${r.chosenRank}/48")
        lines.add("DANS la fenêtre : " + bt(r.inWin))
        lines.add("HORS fenêtre    : " + bt(r.outWin))
        btWrite("étude de l'heure de Wall Street", lines)
    }

    // ---------------------------------------------------------------- export

    private fun positionsCsv(): String {
        val sb = StringBuilder("id;symbole;mode;strategie;ouverture;cloture;entree;sortie;qte;cout;tp;sl;statut;raison;pnl_usdt;pnl_pct;plus_haut_pct;plus_bas_pct;sig_ratio;sig_variation;sig_meche;sig_hausse24h;glissement_pct\n")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (p in Positions.items.value) {
            val pct = if (p.cost > 0.0) p.pnl / p.cost * 100.0 else 0.0
            val close = if (p.closeTime > 0) iso.format(Date(p.closeTime)) else ""
            sb.append(
                listOf<Any>(
                    p.id, p.symbol, p.mode, p.strategy.ifEmpty { "-" }, iso.format(Date(p.openTime)), close,
                    p.entryPrice, p.exitPrice, p.qty, p.cost, p.tp, p.sl, p.status, p.closeReason,
                    f(p.pnl, 4), f(pct, 3), f(p.mfePct), f(p.maePct), f(p.sigRatio), f(p.sigChange), f(p.sigWick, 1),
                    f(p.sigChg24), f(p.slippagePct, 3)
                ).joinToString(";")
            ).append('\n')
        }
        return sb.toString()
    }

    private fun alertsCsv(): String {
        val sb = StringBuilder("heure;symbole;prix;variation_bougie_pct;ratio_volume;volume_usdt\n")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (a in History.items.value) {
            sb.append(listOf<Any>(iso.format(Date(a.time)), a.symbol, a.price, f(a.changePct, 3), f(a.ratio), f(a.volUsdt, 0)).joinToString(";")).append('\n')
        }
        return sb.toString()
    }

    private fun readme(): String =
        """
        Journal de debug Volume Scanner
        ------------------------------
        events-AAAAMMJJ.log    événements : scans (avec motifs de rejet), stratégies, ordres, API (sans clé), flux, erreurs
        scan-AAAAMMJJ.csv      les 40 jetons au plus fort ratio de volume de chaque scan, avec le motif de rejet
        ranks-AAAAMMJJ.csv     classement du top 30 toutes les 10 s (symbole,rang,prix,variation24h,places gagnées sur 15 min)
        spreads-AAAAMMJJ.csv   écarts acheteur-vendeur relevés toutes les 5 min
        backtests.log          résultats détaillés de tous les backtests lancés
        positions.csv          toutes les positions, avec plus haut et plus bas atteints depuis l'entrée
        alertes.csv            historique des alertes
        settings.txt           réglages au moment de l'export (aucune clé API ni secret)
        """.trimIndent()

    private fun writeZip(c: Context, out: OutputStream): Int {
        val d = File(c.filesDir, "logs")
        val files = (d.listFiles() ?: emptyArray()).filter { it.isFile }
        val extras = LinkedHashMap<String, String>()
        extras["README.txt"] = readme()
        extras["settings.txt"] = "Config: ${Prefs.load(c)}\nTrade: ${TradePrefs.load(c)}\nStratégies: ${StratPrefs.load(c)}\n" +
            "Flux: ${LiveState.status.value}\nBTC: ${ScanState.btc.value}\nHistorique: ${HistoryStore.describe(c)}\n" +
            "Version Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MODEL}\nFuseau: ${TimeZone.getDefault().id}\n"
        extras["positions.csv"] = positionsCsv()
        extras["alertes.csv"] = alertsCsv()
        ZipOutputStream(out).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            for ((n, t) in extras) {
                z.putNextEntry(ZipEntry(n))
                z.write(t.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return files.size
    }

    /** Écrit un zip dans Téléchargements/VolumeScanner et retourne un message pour l'utilisateur. */
    fun exportZip(c: Context): String {
        prune(c)
        val name = "volumescanner-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        val n: Int
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VolumeScanner")
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("création du fichier impossible")
            val os = c.contentResolver.openOutputStream(uri) ?: throw IOException("écriture impossible")
            n = writeZip(c, os)
        } else {
            val dl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VolumeScanner")
            dl.mkdirs()
            n = writeZip(c, FileOutputStream(File(dl, name)))
        }
        return "Enregistré dans Téléchargements/VolumeScanner/$name ($n fichiers de journal, plus positions, alertes et réglages)"
    }

    fun sizeMb(c: Context): Double =
        File(c.filesDir, "logs").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576.0

    fun clear(c: Context) {
        synchronized(lock) { File(c.filesDir, "logs").deleteRecursively() }
    }
}
