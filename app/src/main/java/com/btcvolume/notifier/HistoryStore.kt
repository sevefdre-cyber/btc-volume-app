package com.btcvolume.notifier

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Historique de bougies stocké sur le téléphone : un fichier compressé par crypto et par taille de bougie. */
object HistoryStore {
    private const val BASE_SECS = 1_577_836_800L   // 1er janvier 2020 : les dates sont stockées en secondes depuis cette date

    private fun root(c: Context): File {
        val d = File(c.filesDir, "hist")
        d.mkdirs()
        return d
    }

    private fun file(c: Context, interval: String, base: String): File {
        val d = File(root(c), interval)
        d.mkdirs()
        return File(d, "$base.bin")
    }

    fun write(c: Context, interval: String, base: String, cs: List<Candle>) {
        val target = file(c, interval, base)
        val tmp = File(target.parentFile, "$base.tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(tmp)))).use { out ->
            out.writeInt(cs.size)
            for (x in cs) {
                out.writeInt((x.time / 1000 - BASE_SECS).toInt())
                out.writeFloat(x.open.toFloat())
                out.writeFloat(x.high.toFloat())
                out.writeFloat(x.low.toFloat())
                out.writeFloat(x.close.toFloat())
                out.writeFloat(x.vol.toFloat())
            }
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun read(c: Context, interval: String, base: String): List<Candle>? {
        val f = file(c, interval, base)
        if (!f.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f)))).use { inp ->
                val n = inp.readInt()
                val out = ArrayList<Candle>(n)
                for (i in 0 until n) {
                    val t = (inp.readInt() + BASE_SECS) * 1000L
                    val o = inp.readFloat().toDouble()
                    val h = inp.readFloat().toDouble()
                    val l = inp.readFloat().toDouble()
                    val cl = inp.readFloat().toDouble()
                    val v = inp.readFloat().toDouble()
                    out.add(Candle(t, o, h, l, cl, v))
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveSymbols(c: Context, list: List<String>) {
        File(root(c), "symbols.txt").writeText(list.joinToString("\n"))
    }

    fun symbols(c: Context): List<String> {
        val f = File(root(c), "symbols.txt")
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    fun sizeBytes(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear(c: Context) {
        root(c).deleteRecursively()
    }

    fun describe(c: Context): String {
        val n = symbols(c).size
        if (n == 0) return "Aucun historique téléchargé."
        val mb = sizeBytes(c) / 1_048_576
        return "$n cryptos · $mb Mo sur le téléphone"
    }
}

object HistState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val errors = MutableStateFlow(0)
    val lastError = MutableStateFlow("")
    val info = MutableStateFlow("")

    @Volatile
    var cancel = false

    fun refresh(c: Context) {
        info.value = HistoryStore.describe(c)
    }
}
