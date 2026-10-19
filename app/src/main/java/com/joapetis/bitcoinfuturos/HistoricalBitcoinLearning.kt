package com.joapetis.bitcoinfuturos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Descarga hasta tres años de BTC 1H y entrena cronológicamente con costes incluidos. */
internal object HistoricalBitcoinLearning {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "btc-history-learning").apply { isDaemon = true } }
    @Volatile private var running = false
    private const val COST_RATE = 0.0014
    fun start(context: Context, brain: LearningBrain, onStatus: (String) -> Unit) {
        synchronized(this) { if (running) return; running = true }
        val app = context.applicationContext
        executor.execute {
            try {
                val dir = File(app.filesDir, "CryptoFutures/history").apply { mkdirs() }
                val dataFile = File(dir, "BTCUSDT_1h.json")
                val metaFile = File(dir, "training-meta.json")
                val meta = try { JSONObject(if (metaFile.exists()) metaFile.readText() else "{}") } catch (_: Exception) { JSONObject() }
                var candles = readCache(dataFile)
                if (candles.size < 1000 || System.currentTimeMillis() - meta.optLong("downloadedAt") > 86400000L) {
                    onStatus("Descargando histórico BTC 1H de hasta 3 años")
                    val fresh = download()
                    if (fresh.size > candles.size / 2) {
                        candles = fresh
                        dataFile.writeText(JSONArray().apply { fresh.forEach { put(JSONObject().put("t",it.time).put("o",it.open).put("h",it.high).put("l",it.low).put("c",it.close).put("v",it.volume)) } }.toString())
                        meta.put("downloadedAt", System.currentTimeMillis())
                    }
                }
                if (candles.size < 300) { onStatus("Histórico BTC insuficiente; se reintentará automáticamente"); return@execute }
                val previous = meta.optLong("trainedThrough", 0L)
                val initial = previous == 0L
                val trainEnd = if (initial) candles.size * 4 / 5 else candles.size
                val start = if (initial) 80 else max(80, candles.indexOfFirst { it.time > previous }.let { if (it < 0) candles.size else it })
                val end = trainEnd - 7
                var learned = 0
                var evaluated = 0
                onStatus("Analizando " + candles.size + " velas históricas BTC")
                if (end > start) for (i in start until end step 2) {
                    val f = features(candles.subList(i - 80, i + 1))
                    val entry = candles[i].close
                    val sign = if (f.side == "LONG") 1.0 else -1.0
                    var outcome = (candles[i + 6].close - entry) * sign / entry
                    for (j in i + 1..i + 6) {
                        val c = candles[j]
                        val stop = if (f.side == "LONG") c.low <= entry * 0.9975 else c.high >= entry * 1.0025
                        val target = if (f.side == "LONG") c.high >= entry * 1.0045 else c.low <= entry * 0.9955
                        if (stop) { outcome = -0.0025; break }
                        if (target) { outcome = 0.0045; break }
                    }
                    brain.learnHistoricalSample(f, outcome - COST_RATE > 0.0, outcome - COST_RATE)
                    learned++; evaluated++
                }
                if (initial) meta.put("trainedThrough", candles.lastOrNull()?.time ?: 0L)
                else if (end > start) meta.put("trainedThrough", candles.getOrNull(end - 1)?.time ?: previous)
                meta.put("lastSamples", evaluated).put("lastLearned", learned).put("lastRun", System.currentTimeMillis())
                metaFile.writeText(meta.toString())
                val validationStart = if (initial) trainEnd else max(80, candles.size * 4 / 5)
                var win = 0; var n = 0
                for (i in validationStart until candles.size - 7 step 3) {
                    val f = features(candles.subList(i - 80, i + 1))
                    val sign = if (f.side == "LONG") 1.0 else -1.0
                    if ((candles[i + 6].close - candles[i].close) * sign / candles[i].close > COST_RATE) win++
                    n++
                }
                onStatus("Histórico BTC listo: " + candles.size + " velas; aprendidas " + learned + "; validación direccional " + (if (n > 0) win * 100 / n else 0) + "% (" + n + " muestras)")
            } catch (e: Exception) {
                onStatus("Aprendizaje histórico BTC: " + (e.message ?: "error") + "; se reintentará")
            } finally { synchronized(this) { running = false } }
        }
    }
    private fun download(): List<CandleSnapshot> {
        val cutoff = System.currentTimeMillis() - 1095L * 86400000L
        val all = sortedMapOf<Long, CandleSnapshot>()
        var end = System.currentTimeMillis(); var pages = 0
        while (end > cutoff && pages < 80) {
            val url = URL("https://api.bybit.com/v5/market/kline?category=linear&symbol=BTCUSDT&interval=60&limit=1000&end=" + end)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val rows = try {
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (root.optInt("retCode", -1) != 0) break
                root.optJSONObject("result")?.optJSONArray("list") ?: break
            } finally { conn.disconnect() }
            if (rows.length() == 0) break
            var oldest = Long.MAX_VALUE
            for (i in 0 until rows.length()) {
                val a = rows.optJSONArray(i) ?: continue
                val t = a.optLong(0)
                if (t < cutoff || t <= 0L || a.length() < 6) continue
                val o = a.optString(1).toDoubleOrNull() ?: continue
                val h = a.optString(2).toDoubleOrNull() ?: continue
                val l = a.optString(3).toDoubleOrNull() ?: continue
                val c = a.optString(4).toDoubleOrNull() ?: continue
                val v = a.optString(5).toDoubleOrNull() ?: 0.0
                all[t] = CandleSnapshot(t, o, h, l, c, v); oldest = min(oldest, t)
            }
            if (oldest == Long.MAX_VALUE || oldest >= end) break
            end = oldest - 1; pages++
        }
        return all.values.toList()
    }
    private fun readCache(file: File): List<CandleSnapshot> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { CandleSnapshot(it.optLong("t"),it.optDouble("o"),it.optDouble("h"),it.optDouble("l"),it.optDouble("c"),it.optDouble("v")) } }.distinctBy { it.time }.sortedBy { it.time }
    } catch (_: Exception) { emptyList() }
    private fun features(c: List<CandleSnapshot>): TradeFeatures {
        val closes = c.map { it.close }; val last = closes.last()
        val e9 = ema(closes, 9); val e21 = ema(closes, 21); val e50 = ema(closes, 50)
        val momentum = (e9 - e21) / last
        val side = if (momentum >= 0.0) "LONG" else "SHORT"
        val vol = c.takeLast(14).map { abs(it.high - it.low) }.average() / last * 10000.0
        val hour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = c.last().time }.get(java.util.Calendar.HOUR_OF_DAY)
        return TradeFeatures(side,(momentum*1500).coerceIn(-1.0,1.0),0.0,1.0,(momentum*1200).coerceIn(-1.0,1.0),vol.coerceIn(1.0,200.0),hour,0.0,0.0,((e21-e50)/last*500).coerceIn(-1.0,1.0))
    }
    private fun ema(v: List<Double>, n: Int): Double {
        val k = 2.0 / (min(n, v.size) + 1.0); var e = v.first()
        for (x in v.drop(1)) e = x * k + e * (1.0 - k)
        return e
    }
}
