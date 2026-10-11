package com.joapetis.bitcoinfuturos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BotSnapshot(
    val symbol: String, val balance: Double, val price: Double, val pnl: Double,
    val position: String, val entry: Double, val trades: Int, val wins: Int,
    val status: String, val leverage: Int, val risk: Double, val learned: Int
)

object NativeTradingEngine {
    private val symbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "DOGE-USDT", "BNB-USDT", "LINK-USDT", "ADA-USDT", "AVAX-USDT", "SUI-USDT")
    private val lock = Any()
    private val bots = linkedMapOf<String, JSONObject>()
    private lateinit var app: Context
    private var lastError = ""
    private var initialized = false
    private val levOptions = listOf(1,2,3,5,10,20,50,75,100)

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return
        app = context.applicationContext
        val primary = File(app.filesDir, "CryptoFutures")
        val external = app.getExternalFilesDir(null)?.let { File(it, "CryptoFutures") }
        primary.mkdirs(); external?.mkdirs()
        val saved = File(primary, "state-v1.json")
        val externalSaved = external?.let { File(it, "state-v1.json") }
        val source = when {
            saved.exists() -> saved
            externalSaved?.exists() == true -> externalSaved
            else -> null
        }
        if (source != null) {
            try {
                val root = JSONObject(source.readText())
                val arr = root.optJSONArray("bots") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    val sym = b.optString("symbol")
                    if (sym in symbols) bots[sym] = b
                }
            } catch (_: Exception) { lastError = "Estado guardado dañado; se inicia recuperación" }
        }
        symbols.forEach { sym ->
            if (!bots.containsKey(sym)) bots[sym] = JSONObject()
                .put("symbol", sym).put("balance", 100.0).put("initial", 100.0)
                .put("price", 0.0).put("entry", 0.0).put("qty", 0.0).put("side", "FLAT")
                .put("stop", 0.0).put("target", 0.0).put("trades", 0).put("wins", 0)
                .put("losses", 0).put("learned", 0).put("risk", 5.0).put("leverage", 100)
                .put("lastCandle", 0L).put("nnBias", 0.0).put("nnTrend", 0.0).put("nnMomentum", 0.0)
                .put("lastSignal", "Analizando mercado").put("history", JSONArray())
        }
        // Keep model state and trade ledger intact across code/version upgrades.
        saveLocked()
        initialized = true
    }

    fun setRisk(symbol: String, risk: Double) = synchronized(lock) {
        bots[symbol]?.put("risk", risk.coerceIn(0.5, 100.0)); saveLocked()
    }
    fun setLeverage(symbol: String, leverage: Int) = synchronized(lock) {
        bots[symbol]?.put("leverage", if (leverage in levOptions) leverage else 10); saveLocked()
    }
    fun snapshots(): List<BotSnapshot> = synchronized(lock) {
        symbols.map { s ->
            val b = bots[s]!!
            val p = b.optDouble("price", 0.0)
            val entry = b.optDouble("entry", 0.0)
            val side = b.optString("side", "FLAT")
            val floating = if (entry > 0 && p > 0 && side != "FLAT")
                (if (side == "LONG") p-entry else entry-p) / entry * b.optDouble("qty", 0.0) else 0.0
            BotSnapshot(s, b.optDouble("balance",100.0), p, b.optDouble("balance",100.0)-b.optDouble("initial",100.0)+floating,
                side, entry, b.optInt("trades"), b.optInt("wins"), b.optString("lastSignal"),
                b.optInt("leverage",100), b.optDouble("risk",5.0), b.optInt("learned"))
        }
    }
    fun totalPnl() = snapshots().sumOf { it.pnl }
    fun lastError(): String = synchronized(lock) { lastError }
    fun storagePath(): String = synchronized(lock) { File(app.filesDir, "CryptoFutures").absolutePath }

    fun tickAll() {
        if (!initialized) return
        symbols.forEach { symbol ->
            try { processSymbol(symbol) }
            catch (e: Exception) { synchronized(lock) { lastError = "$symbol: ${e.message ?: "sin conexión"}".take(120) } }
        }
        synchronized(lock) { saveLocked() }
    }

    private fun processSymbol(symbol: String) {
        val candles = fetchCandles(symbol) ?: return
        if (candles.size < 60) return
        val closes = candles.map { it.optDouble(4) }
        val highs = candles.map { it.optDouble(2) }
        val lows = candles.map { it.optDouble(3) }
        val last = candles.last()
        val close = closes.last()
        val prevCandleTime = last.optLong(0)
        synchronized(lock) {
            val b = bots[symbol] ?: return
            b.put("price", close)
            val entry = b.optDouble("entry")
            val side = b.optString("side")
            val atr = atr(highs, lows, closes, 14)
            val ema9 = ema(closes, 9); val ema21 = ema(closes, 21)
            val ema50 = ema(closes, 50); val ema200 = ema(closes, min(200, closes.size))
            val rsi = rsi(closes, 14)
            val returns = if (closes.size > 12 && closes[closes.size-12] != 0.0) close / closes[closes.size-12] - 1.0 else 0.0
            val trend = ((ema9-ema21)/close*100.0).coerceIn(-3.0,3.0)
            val regime = if (abs(ema50-ema200)/close > 0.004) "TENDENCIA" else "RANGO"
            val nn = b.optDouble("nnBias") + b.optDouble("nnTrend")*trend + b.optDouble("nnMomentum")*(returns*100)
            val trendScore = (if (ema9 > ema21) 1 else -1) + (if (ema50 > ema200) 1 else -1)
            val momentumScore = when { returns > 0.004 && rsi in 48.0..72.0 -> 1; returns < -0.004 && rsi in 28.0..52.0 -> -1; else -> 0 }
            val meanReversion = if (regime == "RANGO") when { rsi < 28 -> 1; rsi > 72 -> -1; else -> 0 } else 0
            val raw = (trendScore * if (regime == "TENDENCIA") 1.0 else 0.45) + momentumScore + meanReversion + nn.coerceIn(-1.0,1.0)
            val signal = when { raw >= 2.0 -> "LONG"; raw <= -2.0 -> "SHORT"; else -> "FLAT" }
            b.put("lastSignal", "$regime · RSI ${rsi.toInt()} · Señal $signal")
            if (prevCandleTime <= b.optLong("lastCandle", 0L)) return
            b.put("lastCandle", prevCandleTime)
            if (side != "FLAT" && entry > 0) {
                val stop = b.optDouble("stop")
                val target = b.optDouble("target")
                val stopHit = if (side == "LONG") close <= stop else close >= stop
                val targetHit = if (side == "LONG") close >= target else close <= target
                if (stopHit || targetHit || (signal != "FLAT" && signal != side)) {
                    val gross = (if (side == "LONG") close-entry else entry-close) / entry * b.optDouble("qty")
                    val fee = b.optDouble("qty") * close * 0.0006
                    val net = gross-fee
                    b.put("balance", max(0.0, b.optDouble("balance")+net))
                    if (net > 0) b.put("wins", b.optInt("wins")+1) else b.put("losses", b.optInt("losses")+1)
                    learn(b, net > 0, trend, returns*100)
                    val h = b.optJSONArray("history") ?: JSONArray()
                    h.put(JSONObject().put("time", System.currentTimeMillis()).put("side", side).put("entry",entry).put("exit",close).put("pnl",net).put("reason",if(stopHit) "Stop ATR" else if(targetHit) "Objetivo ATR" else "Señal contraria"))
                    while (h.length()>300) h.remove(0)
                    b.put("history",h).put("side","FLAT").put("entry",0.0).put("qty",0.0).put("stop",0.0).put("target",0.0).put("trades",b.optInt("trades")+1)
                }
            } else if (side == "FLAT" && signal != "FLAT" && b.optDouble("balance") > 0.5) {
                val riskCash = b.optDouble("balance") * b.optDouble("risk",5.0)/100.0
                val stopDistance = max(atr*1.8, close*0.001)
                val maxNotional = b.optDouble("balance") * b.optInt("leverage",100)
                val qty = min(riskCash / stopDistance, maxNotional / close)
                if (qty > 0 && qty.isFinite()) {
                    val stop = if(signal=="LONG") close-stopDistance else close+stopDistance
                    val target = if(signal=="LONG") close+atr*2.7 else close-atr*2.7
                    b.put("side",signal).put("entry",close).put("qty",qty).put("stop",stop).put("target",target)
                }
            }
        }
    }

    private fun learn(b: JSONObject, won: Boolean, trend: Double, momentum: Double) {
        // Tiny online logistic learner, deliberately conservative; persistent weights survive upgrades.
        val y = if (won) 1.0 else 0.0
        val x1 = trend.coerceIn(-3.0,3.0)/3.0
        val x2 = momentum.coerceIn(-5.0,5.0)/5.0
        val bias = b.optDouble("nnBias")
        val w1 = b.optDouble("nnTrend")
        val w2 = b.optDouble("nnMomentum")
        val z = (bias + w1*x1 + w2*x2).coerceIn(-20.0,20.0)
        val pred = 1.0/(1.0+Math.exp(-z))
        val delta = (y-pred)*0.03
        b.put("nnBias", (bias+delta).coerceIn(-3.0,3.0))
            .put("nnTrend",(w1+delta*x1).coerceIn(-3.0,3.0))
            .put("nnMomentum",(w2+delta*x2).coerceIn(-3.0,3.0))
            .put("learned",b.optInt("learned")+1)
    }

    private fun fetchCandles(symbol: String): List<JSONArray>? {
        val url = URL("https://www.okx.com/api/v5/market/candles?instId=$symbol&bar=1H&limit=220")
        val conn = (url.openConnection() as HttpURLConnection)
        conn.connectTimeout=7000; conn.readTimeout=7000; conn.requestMethod="GET"
        return try {
            val body=conn.inputStream.bufferedReader().use { it.readText() }
            val root=JSONObject(body)
            if(root.optString("code")!="0") return null
            val arr=root.optJSONArray("data") ?: return null
            (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.reversed()
        } finally { conn.disconnect() }
    }
    private fun ema(v: List<Double>, n: Int): Double {
        if(v.isEmpty()) return 0.0
        val k=2.0/(n+1); var e=v.first()
        v.drop(1).forEach { e=it*k+e*(1-k) }; return e
    }
    private fun rsi(v: List<Double>, n: Int): Double {
        if(v.size<=n) return 50.0
        var up=0.0; var down=0.0
        for(i in v.size-n until v.size) { val d=v[i]-v[i-1]; if(d>0) up+=d else down-=d }
        if(down==0.0) return 100.0
        return 100.0-100.0/(1.0+up/down)
    }
    private fun atr(h: List<Double>, l: List<Double>, c: List<Double>, n: Int): Double {
        if(c.size<2) return c.lastOrNull()?.times(0.005) ?: 0.0
        val start=max(1,c.size-n)
        val tr=(start until c.size).map { max(h[it]-l[it], max(abs(h[it]-c[it-1]),abs(l[it]-c[it-1]))) }
        return tr.average().takeIf { it.isFinite() && it>0 } ?: c.last()*0.005
    }
    private fun saveLocked() {
        if (!::app.isInitialized) return
        try {
            val root=JSONObject().put("schemaVersion",1).put("savedAt",System.currentTimeMillis())
            val arr=JSONArray(); symbols.forEach { bots[it]?.let(arr::put) }; root.put("bots",arr)
            val folder=File(app.filesDir,"CryptoFutures"); folder.mkdirs()
            val tmp=File(folder,"state-v1.json.tmp"); tmp.writeText(root.toString())
            val dst=File(folder,"state-v1.json")
            if(!tmp.renameTo(dst)) { dst.writeText(root.toString()); tmp.delete() }
            app.getExternalFilesDir(null)?.let { ext ->
                val backup=File(ext,"CryptoFutures"); backup.mkdirs()
                File(backup,"state-v1.json.tmp").writeText(root.toString())
                File(backup,"state-v1.json.tmp").renameTo(File(backup,"state-v1.json"))
                val log=File(backup,"events.csv")
                if(!log.exists()) log.writeText("timestamp,symbol,price,balance,side,trades,learned\n")
                symbols.forEach { s ->
                    val b=bots[s]!!
                    log.appendText("${System.currentTimeMillis()},$s,${b.optDouble("price")},${b.optDouble("balance")},${b.optString("side")},${b.optInt("trades")},${b.optInt("learned")}\n")
                }
            }
        } catch(e: Exception) { lastError="No se pudo guardar: ${e.message}".take(120) }
    }
}
