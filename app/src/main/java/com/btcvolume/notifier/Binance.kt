package com.btcvolume.notifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ApiException(val code: Int, message: String) : IOException(message)

class Filters(
    val tick: BigDecimal,
    val step: BigDecimal,
    val minQty: BigDecimal,
    val minNotional: BigDecimal
)

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val vol: Double
)

object Binance {
    private const val REAL_URL = "https://api.binance.com"
    private const val TEST_URL = "https://testnet.binance.vision"

    private val offsets = HashMap<String, Long>()

    fun baseUrl(env: String): String = if (env == "TESTNET") TEST_URL else REAL_URL

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    private fun call(method: String, url: String, body: String?, apiKey: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        if (apiKey != null) conn.setRequestProperty("X-MBX-APIKEY", apiKey)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                var c = code
                var msg = "HTTP $code"
                try {
                    val o = JSONObject(text)
                    c = o.optInt("code", code)
                    msg = o.optString("msg", msg)
                } catch (e: Exception) {
                    // corps non JSON
                }
                throw ApiException(c, "$msg (code $c)")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun publicGet(env: String, path: String, params: Map<String, String> = emptyMap()): String {
        val q = if (params.isEmpty()) "" else "?" + query(params)
        return call("GET", baseUrl(env) + path + q, null, null)
    }

    private fun serverTime(env: String): Long =
        JSONObject(publicGet(env, "/api/v3/time")).getLong("serverTime")

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun signedOnce(
        env: String, method: String, path: String,
        params: Map<String, String>, key: String, secret: String
    ): String {
        val off = offsets.getOrPut(env) { serverTime(env) - System.currentTimeMillis() }
        val p = LinkedHashMap(params)
        p["timestamp"] = (System.currentTimeMillis() + off).toString()
        p["recvWindow"] = "10000"
        val q = query(p)
        val full = q + "&signature=" + hmac(secret, q)
        return if (method == "POST") {
            call("POST", baseUrl(env) + path, full, key)
        } else {
            call(method, baseUrl(env) + path + "?" + full, null, key)
        }
    }

    /** Requête signée, journalisée (jamais la clé ni la signature). */
    fun signed(
        env: String, method: String, path: String,
        params: Map<String, String>, key: String, secret: String
    ): String {
        val t0 = System.currentTimeMillis()
        val desc = "$env $method $path " + params.entries.joinToString(" ") { it.key + "=" + it.value }
        try {
            val r = signedRaw(env, method, path, params, key, secret)
            DebugLog.log("API", "$desc → ok ${System.currentTimeMillis() - t0} ms")
            return r
        } catch (e: Exception) {
            DebugLog.log("API", "$desc → ERREUR ${System.currentTimeMillis() - t0} ms : ${e.message}")
            throw e
        }
    }

    /** Requête signée (HMAC-SHA256). Resynchronise l'horloge et réessaie une fois si besoin. */
    private fun signedRaw(
        env: String, method: String, path: String,
        params: Map<String, String>, key: String, secret: String
    ): String {
        return try {
            signedOnce(env, method, path, params, key, secret)
        } catch (e: ApiException) {
            if (e.code == -1021) {
                offsets.remove(env)
                signedOnce(env, method, path, params, key, secret)
            } else {
                throw e
            }
        }
    }

    fun filters(env: String, pair: String): Filters {
        val o = JSONObject(publicGet(env, "/api/v3/exchangeInfo", mapOf("symbol" to pair)))
        val s = o.getJSONArray("symbols").getJSONObject(0)
        if (s.optString("status") != "TRADING") throw IOException("$pair n'est pas échangeable actuellement")
        var tick = BigDecimal("0.00000001")
        var step = BigDecimal("0.00000001")
        var minQty = BigDecimal("0")
        var minNotional = BigDecimal("5")
        val fs = s.getJSONArray("filters")
        for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            when (f.getString("filterType")) {
                "PRICE_FILTER" -> tick = BigDecimal(f.getString("tickSize"))
                "LOT_SIZE" -> {
                    step = BigDecimal(f.getString("stepSize"))
                    minQty = BigDecimal(f.getString("minQty"))
                }
                "NOTIONAL", "MIN_NOTIONAL" -> minNotional = BigDecimal(f.getString("minNotional"))
            }
        }
        return Filters(tick, step, minQty, minNotional)
    }

    fun floorTo(v: Double, step: BigDecimal): BigDecimal {
        val bd = BigDecimal(v.toString())
        if (step.signum() <= 0) return bd
        return bd.divide(step, 0, RoundingMode.DOWN).multiply(step)
    }

    fun plain(b: BigDecimal): String = b.stripTrailingZeros().toPlainString()

    // ---- données publiques (toujours le marché réel, pour prix et graphiques) ----

    fun price(pair: String): Double =
        JSONObject(publicGet("REAL", "/api/v3/ticker/price", mapOf("symbol" to pair)))
            .getString("price").toDouble()

    fun allPrices(): Map<String, Double> {
        val arr = JSONArray(publicGet("REAL", "/api/v3/ticker/price"))
        val m = HashMap<String, Double>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.getString("symbol")
            if (s.endsWith("USDT")) {
                val p = o.getString("price").toDoubleOrNull()
                if (p != null) m[s] = p
            }
        }
        return m
    }

    /** Jusqu'à pages x 1000 bougies (les plus récentes en dernier). */
    fun candlesPaged(pair: String, interval: String, pages: Int): List<Candle> {
        var all = candles(pair, interval, 1000)
        for (p in 1 until pages) {
            if (all.isEmpty()) break
            val older = candles(pair, interval, 1000, all.first().time - 1)
            if (older.isEmpty()) break
            all = older + all
        }
        return all
    }

    private fun klineParams(pair: String, interval: String, limit: Int, endTime: Long?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["symbol"] = pair
        m["interval"] = interval
        m["limit"] = limit.toString()
        if (endTime != null) m["endTime"] = endTime.toString()
        return m
    }

    fun candles(pair: String, interval: String, limit: Int, endTime: Long? = null): List<Candle> {
        val arr = JSONArray(
            publicGet(
                "REAL", "/api/v3/klines",
                klineParams(pair, interval, limit, endTime)
            )
        )
        val out = ArrayList<Candle>()
        for (i in 0 until arr.length()) {
            val k = arr.getJSONArray(i)
            out.add(
                Candle(
                    k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                    k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble()
                )
            )
        }
        return out
    }
}
