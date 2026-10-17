package com.joapetis.bitcoinfuturos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.TreeMap

data class CandleSnapshot(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
private data class CooperativeDecision(val direction: String, val detail: String, val mtfScore: Double)

data class BotSnapshot(
    val symbol: String, val balance: Double, val price: Double, val pnl: Double,
    val position: String, val entry: Double, val trades: Int, val wins: Int,
    val status: String, val leverage: Int, val risk: Double, val learned: Int,
    val equity: Double = balance
)

object NativeTradingEngine {
    private const val TAKER_FEE_RATE = 0.0005
    private const val SLIPPAGE_RATE = 0.0002
    private val symbols = listOf("BTC-USDT")
    private val lock = Any()
    private val bots = linkedMapOf<String, JSONObject>()
    private val candleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartCandleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val ensembleExecutor = java.util.concurrent.Executors.newFixedThreadPool(5)
    private val chartRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val quoteClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private var pionexSocket: WebSocket? = null
    private val pionexQuotes = linkedMapOf<String, Double>()
    private val pionexQuoteAt = linkedMapOf<String, Long>()
    private var pionexNextFundingRate = 0.0
    private var pionexNextFundingTime = 0L
    private var lastFundingRatesFetchAt = 0L
    private val pionexFundingRates = linkedMapOf<Long, Double>()
    private val pionexFundingForecasts = linkedMapOf<Long, Double>()
    private val pionexBookBids = TreeMap<Double, Double>(java.util.Collections.reverseOrder<Double>())
    private val pionexBookAsks = TreeMap<Double, Double>()
    private var pionexBookUpdatedAt = 0L
    private var pionexBookSequence = 0L
    private var pionexBookSource = "PIONEX"
    private var bybitSocket: WebSocket? = null
    private val bybitQuotes = linkedMapOf<String, Double>()
    private val bybitQuoteAt = linkedMapOf<String, Long>()
    private var quoteSocketStarted = false
    private lateinit var app: Context
    private var lastError = ""
    private var initialized = false
    private var backupTreeUri: Uri? = null
    private val levOptions = listOf(1, 2, 3, 5, 10, 20, 50, 75, 100)
    @Volatile private var currentMtfScore = 0.0

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return
        app = context.applicationContext
        backupTreeUri = app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).getString("tree_uri", null)?.let { Uri.parse(it) }
        val primary = File(app.filesDir, "CryptoFutures")
        val external = app.getExternalFilesDir(null)?.let { File(it, "CryptoFutures") }
        primary.mkdirs(); external?.mkdirs()
        val saved = File(primary, "state-v1.json")
        val externalSaved = external?.let { File(it, "state-v1.json") }
        val source = when { saved.exists() -> saved; externalSaved?.exists() == true -> externalSaved; else -> null }
        if (source != null) {
            try {
                val root = JSONObject(source.readText())
                val arr = root.optJSONArray("bots") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    if (b.optString("symbol") == "BTC-USDT") bots["BTC-USDT"] = b
                }
            } catch (_: Exception) { lastError = "Estado guardado recuperado" }
        }
        symbols.forEach { sym ->
            if (!bots.containsKey(sym)) bots[sym] = JSONObject()
                .put("symbol", sym).put("balance", 100.0).put("initial", 100.0)
                .put("price", 0.0).put("entry", 0.0).put("qty", 0.0).put("side", "FLAT")
                .put("stop", 0.0).put("target", 0.0).put("trades", 0).put("wins", 0)
                .put("losses", 0).put("learned", 0).put("risk", 5.0).put("leverage", 100).put("enabled", true)
                .put("injectedCapital", 0.0).put("recharges", 0)
                .put("lastSignal", "Iniciando Enjambre MTF + Order Flow L2…").put("history", JSONArray())
        }
        bots["BTC-USDT"]?.put("tradingMode", "SWING")
        loadBrainLocked()
        saveLocked()
        initialized = true
        startQuoteStreamsLocked()
        startTradingLoopLocked()
        NewsIntelligence.init(app)
        PixelAi.prepare()
    }

    fun isRunning(): Boolean = synchronized(lock) { bots["BTC-USDT"]?.optBoolean("enabled", true) == true }
    fun resumeAll() = synchronized(lock) { bots["BTC-USDT"]?.put("enabled", true)?.put("lastSignal", "Reanudado · Enjambre MTF + Gemini Nano"); saveLocked() }
    fun setBotEnabled(symbol: String, enabled: Boolean) = synchronized(lock) { bots[symbol]?.put("enabled", enabled); saveLocked() }
    fun setRisk(symbol: String, risk: Double) = synchronized(lock) { bots[symbol]?.put("risk", risk.coerceIn(1.0, 100.0)); saveLocked() }
    fun setLeverage(symbol: String, leverage: Int) = synchronized(lock) { bots[symbol]?.put("leverage", if (leverage in levOptions) leverage else 100); saveLocked() }

    fun snapshots(): List<BotSnapshot> = synchronized(lock) {
        symbols.map { s ->
            val b = bots[s]!!
            val p = b.optDouble("price", 0.0)
            val entry = b.optDouble("entry", 0.0)
            val side = b.optString("side", "FLAT")
            val floating = if (entry > 0 && p > 0 && side != "FLAT") (if (side == "LONG") p - entry else entry - p) * b.optDouble("qty", 0.0) else 0.0
            val balance = b.optDouble("balance", 100.0)
            BotSnapshot(s, balance, p, balance - b.optDouble("initial", 100.0) - b.optDouble("injectedCapital", 0.0) + floating,
                side, entry, b.optInt("trades"), b.optInt("wins"), b.optString("lastSignal"),
                b.optInt("leverage", 100), b.optDouble("risk", 5.0), brain.samples, balance + floating)
        }
    }

    fun jointOperationState(): JSONObject = synchronized(lock) {
        val b = bots["BTC-USDT"] ?: JSONObject()
        val price = b.optDouble("price", 0.0)
        val entry = b.optDouble("entry", 0.0)
        val qty = b.optDouble("qty", 0.0)
        val side = b.optString("side", "FLAT")
        val floating = if (side == "LONG") (price - entry) * qty else if (side == "SHORT") (entry - price) * qty else 0.0
        JSONObject().put("symbol", "BTC-USDT").put("enabled", b.optBoolean("enabled", true)).put("side", side)
            .put("price", price).put("entry", entry).put("qty", qty)
            .put("stop", b.optDouble("stop", 0.0)).put("target", b.optDouble("target", 0.0))
            .put("trailingActive", b.optBoolean("trailingActive", false)).put("balance", b.optDouble("balance", 100.0)).put("floatingPnl", floating)
            .put("lastSignal", b.optString("lastSignal", "Esperando análisis"))
            .put("expertConsensusDetail", b.optString("expertConsensusDetail", "Sincronizando 1m/5m/15m/1H/4H"))
    }

    fun jointOperationActivity(): List<JSONObject> = synchronized(lock) {
        val arr = bots["BTC-USDT"]?.optJSONArray("expertActivity") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { JSONObject(it.toString()) } }.sortedByDescending { it.optLong("time", 0L) }
    }

    fun recordNewsEvent(message: String) = synchronized(lock) {
        val b = bots["BTC-USDT"] ?: return@synchronized
        recordExpertActivityLocked(b, "NOTICIAS IA", message, b.optDouble("price", 0.0))
        brain.addLesson("noticias", message)
    }

    private fun recordExpertActivityLocked(b: JSONObject, type: String, message: String, price: Double = b.optDouble("price", 0.0), pnl: Double? = null) {
        val arr = b.optJSONArray("expertActivity") ?: JSONArray()
        val last = arr.optJSONObject(arr.length() - 1)
        if (last != null && last.optString("type") == type && last.optString("message") == message) return
        val event = JSONObject().put("time", System.currentTimeMillis()).put("type", type).put("message", message).put("price", price)
        if (pnl != null) event.put("pnl", pnl)
        arr.put(event)
        while (arr.length() > 300) arr.remove(0)
        b.put("expertActivity", arr)
    }

    fun tradeHistory(symbol: String? = null): List<JSONObject> = synchronized(lock) {
        val out = mutableListOf<JSONObject>()
        symbols.filter { symbol == null || it == symbol }.forEach { sym ->
            val arr = bots[sym]?.optJSONArray("history") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { item -> out.add(JSONObject(item.toString()).put("symbol", sym)) }
        }
        out.sortedByDescending { it.optLong("time", 0L) }
    }

    fun liveQuoteSource(): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        when {
            pionexQuoteAt["BTC-USDT"]?.let { now - it < 15_000L } == true -> "Pionex Futures · principal"
            bybitQuoteAt["BTC-USDT"]?.let { now - it < 15_000L } == true -> "Bybit Futures · directo"
            else -> "Pionex REST / OKX · respaldo"
        }
    }

    private fun startPionexStreamLocked() {
        if (pionexSocket != null) return
        pionexSocket = quoteClient.newWebSocket(Request.Builder().url("wss://ws.pionex.com/wsPub").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("op", "SUBSCRIBE").put("topic", "TRADE").put("symbol", "BTC_USDT_PERP").toString())
                webSocket.send(JSONObject().put("op", "SUBSCRIBE").put("topic", "ORDERBOOK").put("symbol", "BTC_USDT_PERP").put("limit", 20).toString())
                webSocket.send(JSONObject().put("op", "SUBSCRIBE").put("topic", "INDEX").put("symbol", "BTC_USDT_PERP").toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.optString("op").uppercase(Locale.US) == "PING") {
                        webSocket.send(JSONObject().put("op", "PONG").put("timestamp", System.currentTimeMillis()).toString()); return
                    }
                    when (root.optString("topic").uppercase(Locale.US)) {
                        "TRADE" -> {
                            val rows = root.optJSONArray("data") ?: return
                            val now = System.currentTimeMillis()
                            for (i in 0 until rows.length()) {
                                val row = rows.optJSONObject(i) ?: continue
                                val price = row.optString("price").toDoubleOrNull() ?: continue
                                val size = row.optString("size").toDoubleOrNull() ?: 0.0
                                val tradeSide = row.optString("side").uppercase(Locale.US)
                                synchronized(lock) {
                                    if (size > 0.0 && (tradeSide == "BUY" || tradeSide == "SELL")) {
                                        flowEvents.addLast(now to (if (tradeSide == "BUY") size else -size))
                                        while (flowEvents.size > 5000) flowEvents.removeFirst()
                                    }
                                    if (price > 0) {
                                        pionexQuotes["BTC-USDT"] = price; pionexQuoteAt["BTC-USDT"] = now
                                        bots["BTC-USDT"]?.put("price", price)?.put("priceSource", "PIONEX_TRADE")
                                        if (pionexBookBids.isEmpty() || pionexBookAsks.isEmpty() || now - pionexBookUpdatedAt > 3500L) synthesizeBookFromTradeLocked(price, now)
                                    }
                                }
                            }
                        }
                        "ORDERBOOK" -> {
                            val d = root.optJSONObject("data") ?: return
                            synchronized(lock) {
                                if (d.optString("action").uppercase(Locale.US) == "SNAPSHOT") { pionexBookBids.clear(); pionexBookAsks.clear() }
                                fun apply(rows: JSONArray?, book: TreeMap<Double, Double>) {
                                    if (rows == null) return
                                    for (i in 0 until rows.length()) {
                                        val a = rows.optJSONArray(i) ?: continue
                                        val p = a.optString(0).toDoubleOrNull() ?: continue
                                        val q = a.optString(1).toDoubleOrNull() ?: continue
                                        if (p > 0) { if (q <= 0) book.remove(p) else book[p] = q }
                                    }
                                }
                                apply(d.optJSONArray("bids"), pionexBookBids); apply(d.optJSONArray("asks"), pionexBookAsks)
                                while (pionexBookBids.isNotEmpty() && pionexBookAsks.isNotEmpty() && pionexBookBids.firstKey() >= pionexBookAsks.firstKey()) {
                                    pionexBookBids.pollFirstEntry(); pionexBookAsks.pollFirstEntry()
                                }
                                if (pionexBookBids.isNotEmpty() && pionexBookAsks.isNotEmpty()) {
                                    pionexBookSequence = d.optLong("number"); pionexBookUpdatedAt = System.currentTimeMillis(); pionexBookSource = "Pionex L2"
                                }
                            }
                        }
                        "INDEX" -> {
                            val row = root.optJSONArray("data")?.optJSONObject(0) ?: return
                            synchronized(lock) {
                                pionexNextFundingRate = row.optString("nextFundingRate").toDoubleOrNull() ?: pionexNextFundingRate
                                pionexNextFundingTime = row.optLong("nextFundingTime", pionexNextFundingTime)
                                if (pionexNextFundingTime > 0) pionexFundingForecasts[pionexNextFundingTime] = pionexNextFundingRate
                            }
                            requestPionexFundingRatesIfDue()
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val retry = synchronized(lock) { if (pionexSocket === webSocket) { pionexSocket = null; true } else false }
                if (retry) quoteClient.dispatcher.executorService.execute { try { Thread.sleep(2000) } catch (_: InterruptedException) { return@execute }; synchronized(lock) { startPionexStreamLocked() } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val retry = synchronized(lock) { if (pionexSocket === webSocket) { pionexSocket = null; true } else false }
                if (retry) quoteClient.dispatcher.executorService.execute { try { Thread.sleep(2000) } catch (_: InterruptedException) { return@execute }; synchronized(lock) { startPionexStreamLocked() } }
            }
        })
    }

    private fun synthesizeBookFromTradeLocked(price: Double, now: Long) {
        if (price <= 0.0) return
        while (flowEvents.isNotEmpty() && now - flowEvents.first().first > 20_000L) flowEvents.removeFirst()
        var buyVol = 1.2; var sellVol = 1.2
        for (e in flowEvents) { if (e.second > 0) buyVol += e.second else sellVol -= e.second }
        val halfSpread = max(0.05, price * 0.000025)
        pionexBookBids.clear(); pionexBookAsks.clear()
        for (lvl in 0 until 10) {
            val step = halfSpread * (lvl + 1); val decay = 1.0 / (lvl + 1.0)
            pionexBookBids[price - step] = buyVol * decay; pionexBookAsks[price + step] = sellVol * decay
        }
        pionexBookUpdatedAt = now; pionexBookSource = "Tape L2"
    }

    private fun requestPionexFundingRatesIfDue() {
        val now = System.currentTimeMillis()
        synchronized(lock) { if (now - lastFundingRatesFetchAt < 60_000L) return; lastFundingRatesFetchAt = now }
        chartExecutor.execute {
            try {
                val c = (URL("https://api.pionex.com/api/v1/market/fundingRates?symbol=BTC_USDT_PERP&limit=100").openConnection() as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 2500 }
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val root = JSONObject(body); if (!root.optBoolean("result", false)) return@execute
                val rates = root.optJSONObject("data")?.optJSONArray("rates") ?: return@execute
                synchronized(lock) {
                    for (i in 0 until rates.length()) { val r = rates.optJSONObject(i) ?: continue; val time = r.optLong("fundingTime"); val rate = r.optString("fundingRate").toDoubleOrNull() ?: continue; if (time > 0 && rate.isFinite()) pionexFundingRates[time] = rate }
                    while (pionexFundingRates.size > 500) pionexFundingRates.remove(pionexFundingRates.keys.minOrNull())
                }
            } catch (_: Exception) {}
        }
    }

    private fun fundingCostLocked(notional: Double, side: String, entryTime: Long, exitTime: Long): Double {
        if (notional <= 0 || exitTime <= entryTime) return 0.0
        val crossed = linkedMapOf<Long, Double>()
        pionexFundingRates.forEach { (t, r) -> if (t > entryTime && t <= exitTime) crossed[t] = r }
        pionexFundingForecasts.forEach { (t, r) -> if (t > entryTime && t <= exitTime && !crossed.containsKey(t)) crossed[t] = r }
        val direction = if (side == "LONG") 1.0 else -1.0
        return crossed.values.sumOf { notional * it * direction }
    }

    fun pionexFundingStatus(): String = synchronized(lock) {
        if (pionexNextFundingTime <= 0L) return@synchronized "Pionex funding: sincronizando datos"
        "Funding Pionex estimado: " + String.format(Locale.US, "%.5f", pionexNextFundingRate * 100.0) + "% · próxima liquidación " +
            SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(pionexNextFundingTime))
    }

    fun pionexOrderBookStatus(): String = synchronized(lock) {
        val age = if (pionexBookUpdatedAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - pionexBookUpdatedAt
        if (age > 8_000L || pionexBookBids.isEmpty() || pionexBookAsks.isEmpty()) return@synchronized "Level 2: sincronizando flujo…"
        val bid = pionexBookBids.firstKey(); val ask = pionexBookAsks.firstKey(); val bq = pionexBookBids.values.take(10).sum(); val aq = pionexBookAsks.values.take(10).sum()
        val im = if (bq + aq > 0) (bq - aq) / (bq + aq) * 100 else 0.0
        "$pionexBookSource LIVE · spread " + String.format(Locale.US, "%.2f", (ask - bid) / ((ask + bid) / 2) * 10000) + " bps · imbalance " + String.format(Locale.US, "%+.1f", im) + "% · seq " + pionexBookSequence
    }

    private fun startQuoteStreamsLocked() {
        if (quoteSocketStarted) return
        quoteSocketStarted = true
        startPionexStreamLocked()
        startSingleBybitReconnectLocked()
    }

    private fun startSingleBybitReconnectLocked() {
        if (bybitSocket != null) return
        bybitSocket = quoteClient.newWebSocket(Request.Builder().url("wss://stream.bybit.com/v5/public/linear").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val args = JSONArray().put("tickers.BTCUSDT").put("orderbook.50.BTCUSDT").put("publicTrade.BTCUSDT")
                    webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text); val topic = root.optString("topic"); val now = System.currentTimeMillis()
                        if (topic.startsWith("tickers.")) {
                            val price = root.optJSONObject("data")?.optString("lastPrice")?.toDoubleOrNull() ?: return
                            if (price > 0.0) synchronized(lock) {
                                bybitQuotes["BTC-USDT"] = price; bybitQuoteAt["BTC-USDT"] = now
                                if (now - (pionexQuoteAt["BTC-USDT"] ?: 0L) > 4_000L) bots["BTC-USDT"]?.put("price", price)?.put("priceSource", "BYBIT")
                            }
                        } else if (topic.startsWith("publicTrade.")) {
                            val arr = root.optJSONArray("data") ?: return
                            synchronized(lock) {
                                for (i in 0 until arr.length()) {
                                    val row = arr.optJSONObject(i) ?: continue
                                    val sz = row.optString("v").toDoubleOrNull() ?: 0.0
                                    val s = row.optString("S").uppercase(Locale.US)
                                    if (sz > 0.0 && (s == "BUY" || s == "SELL")) {
                                        flowEvents.addLast(now to (if (s == "BUY") sz else -sz))
                                        while (flowEvents.size > 5000) flowEvents.removeFirst()
                                    }
                                }
                            }
                        } else if (topic.startsWith("orderbook.")) {
                            val data = root.optJSONObject("data") ?: return
                            synchronized(lock) {
                                if (now - pionexBookUpdatedAt > 1_800L || pionexBookSource == "Bybit L2" || pionexBookBids.isEmpty() || pionexBookAsks.isEmpty()) {
                                    if (data.optString("type") == "snapshot") { pionexBookBids.clear(); pionexBookAsks.clear() }
                                    fun applyBybit(rows: JSONArray?, book: TreeMap<Double, Double>) {
                                        if (rows == null) return
                                        for (i in 0 until rows.length()) {
                                            val a = rows.optJSONArray(i) ?: continue
                                            val p = a.optString(0).toDoubleOrNull() ?: continue
                                            val q = a.optString(1).toDoubleOrNull() ?: continue
                                            if (p > 0) { if (q <= 0) book.remove(p) else book[p] = q }
                                        }
                                    }
                                    applyBybit(data.optJSONArray("b"), pionexBookBids); applyBybit(data.optJSONArray("a"), pionexBookAsks)
                                    while (pionexBookBids.isNotEmpty() && pionexBookAsks.isNotEmpty() && pionexBookBids.firstKey() >= pionexBookAsks.firstKey()) {
                                        pionexBookBids.pollFirstEntry(); pionexBookAsks.pollFirstEntry()
                                    }
                                    if (pionexBookBids.isNotEmpty() && pionexBookAsks.isNotEmpty()) {
                                        pionexBookSequence = data.optLong("u", pionexBookSequence + 1); pionexBookUpdatedAt = now; pionexBookSource = "Bybit L2"
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) { if (bybitSocket === webSocket) bybitSocket = null }
                    quoteClient.dispatcher.executorService.execute { try { Thread.sleep(3000) } catch (_: InterruptedException) { return@execute }; synchronized(lock) { startSingleBybitReconnectLocked() } }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) { if (bybitSocket === webSocket) bybitSocket = null }
                    quoteClient.dispatcher.executorService.execute { try { Thread.sleep(3000) } catch (_: InterruptedException) { return@execute }; synchronized(lock) { startSingleBybitReconnectLocked() } }
                }
            })
    }

    fun chartCandles(symbol: String, bar: String): List<CandleSnapshot> = synchronized(lock) { chartCandleCache["$symbol|$bar"]?.toList() ?: emptyList() }

    fun requestChartCandles(symbol: String, bar: String, onLoaded: () -> Unit) {
        val supported = setOf("1m", "5m", "15m", "1H", "4H", "1D")
        if (bar !in supported) return
        val key = "$symbol|$bar"
        if (!chartRequests.add(key)) { try { onLoaded() } catch (_: Exception) {}; return }
        chartExecutor.execute {
            try {
                val parsed = fetchCandles(symbol, bar)?.mapNotNull { row ->
                    if (row.length() < 6) null else CandleSnapshot(row.optLong(0), row.optDouble(1), row.optDouble(2), row.optDouble(3), row.optDouble(4), row.optDouble(5))
                }?.let { closedCandles(it, bar, System.currentTimeMillis()) }
                if (!parsed.isNullOrEmpty()) synchronized(lock) { chartCandleCache[key] = parsed.takeLast(220) }
            } catch (_: Exception) {}
            finally { chartRequests.remove(key); try { onLoaded() } catch (_: Exception) {} }
        }
    }

    fun marketChange24h(symbol: String): Double = synchronized(lock) {
        val c = chartCandleCache["$symbol|1H"]
        if (c == null || c.size < 25 || c[c.size - 25].close == 0.0) return@synchronized 0.0
        (c.last().close / c[c.size - 25].close - 1.0) * 100.0
    }

    fun estimateNetIfClosedNow(): Double = synchronized(lock) {
        val now = System.currentTimeMillis()
        val b = bots["BTC-USDT"] ?: return@synchronized 0.0
        val side = b.optString("side", "FLAT")
        val entry = b.optDouble("entry", 0.0); val mark = b.optDouble("price", 0.0); val qty = b.optDouble("qty", 0.0)
        if (side == "FLAT" || entry <= 0.0 || mark <= 0.0 || qty <= 0.0) return@synchronized 0.0
        val exit = mark * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
        val gross = (if (side == "LONG") exit - entry else entry - exit) * qty
        val fees = (entry * qty + exit * qty) * TAKER_FEE_RATE
        gross - fees - fundingCostLocked(entry * qty, side, b.optLong("entryTime", now), now)
    }

    fun stopAllAndCloseNow(): Double = synchronized(lock) {
        val now = System.currentTimeMillis()
        val b = bots["BTC-USDT"] ?: return@synchronized 0.0
        b.put("enabled", false)
        val side = b.optString("side", "FLAT")
        val entry = b.optDouble("entry", 0.0); val mark = b.optDouble("price", 0.0); val qty = b.optDouble("qty", 0.0)
        if (side == "FLAT" || entry <= 0.0 || mark <= 0.0 || qty <= 0.0) { saveLocked(); return@synchronized 0.0 }
        val exit = mark * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
        val gross = (if (side == "LONG") exit - entry else entry - exit) * qty
        val fees = (entry * qty + exit * qty) * TAKER_FEE_RATE
        val heldMs = max(0L, now - b.optLong("entryTime", now))
        val funding = fundingCostLocked(entry * qty, side, b.optLong("entryTime", now), now)
        val net = gross - fees - funding
        recordExpertActivityLocked(b, "CIERRE MANUAL", "Parada del usuario · neto " + String.format(Locale.US, "%+.2f", net) + " USDT", exit, net)
        val before = b.optDouble("balance", 100.0); val after = max(0.0, before + net)
        b.put("balance", after)
        val history = b.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject().put("type", "TRADE").put("time", now).put("entryTime", b.optLong("entryTime", now)).put("side", side).put("entry", entry).put("exit", exit).put("qty", qty).put("gross", gross).put("fees", fees).put("funding", funding).put("pnl", net).put("balanceBefore", before).put("balanceAfter", after).put("reason", "Parada manual").put("strategy", b.optString("entryStrategy", "SWING_TREND")).put("timeframe", if (b.optString("entryStrategy", "").startsWith("SWING_")) "15m/1h/4h" else "L2").put("durationMs", heldMs))
        b.put("history", history).put("side", "FLAT").put("entry", 0.0).put("qty", 0.0).put("stop", 0.0).put("target", 0.0).put("trailingActive", false).put("trades", b.optInt("trades", 0) + 1)
        if (net > 0) b.put("wins", b.optInt("wins", 0) + 1) else b.put("losses", b.optInt("losses", 0) + 1)
        saveLocked()
        net
    }

    fun lastError(): String = synchronized(lock) { lastError }
    fun storagePath(): String = synchronized(lock) { File(app.filesDir, "CryptoFutures").absolutePath }

    fun setBackupFolder(uri: Uri): String = synchronized(lock) {
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, flags)
            backupTreeUri = uri
            app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).putBoolean("asked", true).apply()
            saveLocked()
            "Carpeta de respaldo configurada correctamente."
        } catch (e: Exception) { "Error de carpeta: " + (e.message ?: "permiso denegado") }
    }
    fun backupFolderLabel(): String = if (backupTreeUri == null) "No seleccionada" else "Configurada · respaldo automático"
    fun shouldRequestBackupFolder(): Boolean = synchronized(lock) { backupTreeUri == null && !app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).getBoolean("prompted", false) }
    fun markBackupPrompted() { app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).edit().putBoolean("prompted", true).apply() }

    fun restoreUserBackup(): String = synchronized(lock) {
        val tree = backupTreeUri ?: return@synchronized "Selecciona primero la carpeta de respaldo."
        try {
            val resolver = app.contentResolver
            fun find(parentId: String, name: String): String? {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
                }
                return null
            }
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val cf = find(rootId, "CryptoFutures")
            val backups = cf?.let { find(it, "backups") }
            val file = find(rootId, "state-v1.json") ?: cf?.let { find(it, "state-v1.json") } ?: backups?.let { find(it, "state-v1.json") }
                ?: return@synchronized "No se encontró state-v1.json."
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file)
            val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@synchronized "No se pudo leer el respaldo."
            val arr = JSONObject(raw).optJSONArray("bots") ?: return@synchronized "Respaldo inválido."
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                if (b.optString("symbol") == "BTC-USDT") bots["BTC-USDT"] = b
            }
            loadBrainLocked(); saveLocked()
            "Restauración completada."
        } catch (e: Exception) { "Error al restaurar: " + (e.message ?: "error") }
    }

    fun importUserBackup(uri:Uri):String=synchronized(lock){
        try{
            val raw=app.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:return@synchronized "No se pudo leer el archivo."
            val root=JSONObject(raw);val arr=root.optJSONArray("bots")?:return@synchronized "Copia inválida: faltan datos del simulador."
            val restored=(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}.firstOrNull{it.optString("symbol")=="BTC-USDT"}?:return@synchronized "La copia no contiene BTC-USDT."
            if(restored.optJSONObject("aiBrain")==null)return@synchronized "La copia no incluye aprendizaje; no se ha cambiado el estado."
            bots["BTC-USDT"]=JSONObject(restored.toString());loadBrainLocked();saveLocked()
            "Copia restaurada: historial, operaciones y aprendizaje recuperados."
        }catch(e:Exception){"No se pudo importar la copia: "+(e.message?:"archivo no válido").take(120)}
    }

    private fun saveUserBackup(root: JSONObject) {
        val tree = backupTreeUri ?: return
        try {
            val resolver = app.contentResolver
            fun child(parentId: String, name: String, mime: String): String? {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
                }
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: return null
                return DocumentsContract.getDocumentId(created)
            }
            val cf = child(DocumentsContract.getTreeDocumentId(tree), "CryptoFutures", DocumentsContract.Document.MIME_TYPE_DIR)
                ?: throw IllegalStateException("No se pudo crear carpeta CryptoFutures")
            val backups = child(cf, "backups", DocumentsContract.Document.MIME_TYPE_DIR)
                ?: throw IllegalStateException("No se pudo crear carpeta backups")
            val file = child(backups, "state-v1.json", "application/json")
                ?: throw IllegalStateException("No se pudo crear state-v1.json")
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file)
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(root.toString()) }
                ?: throw IllegalStateException("No se pudo abrir el archivo de respaldo")
            val readBack = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("No se pudo verificar el archivo de respaldo")
            val verified = JSONObject(readBack)
            if (verified.optLong("savedAt") != root.optLong("savedAt") || verified.optJSONObject("bots") != null) {
                throw IllegalStateException("El respaldo no coincide con el estado guardado")
            }
            val rows = verified.optJSONArray("bots") ?: throw IllegalStateException("Respaldo sin bots")
            if (rows.length() == 0 || rows.optJSONObject(0)?.optJSONObject("aiBrain") == null) {
                throw IllegalStateException("Respaldo sin datos de aprendizaje AI")
            }
        } catch (e: Exception) { lastError = "Respaldo externo no verificado: " + (e.message ?: "error").take(90) }
    }
    // Motor de operaciones Swing: señales de tendencia/ruptura multi-temporal.
    private val brain = LearningBrain()
    private var tradingExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var tradingStatusAt = 0L
    private val midHistory = ArrayDeque<Pair<Long, Double>>()
    private val flowEvents = ArrayDeque<Pair<Long, Double>>()
    private fun costRate(): Double = 2.0 * (TAKER_FEE_RATE + SLIPPAGE_RATE)
    private fun barDurationMs(bar: String): Long = when (bar) { "1m" -> 60_000L; "5m" -> 5L*60_000L; "15m" -> 15L*60_000L; "1H" -> 60L*60_000L; "4H" -> 4L*60L*60_000L; "1D" -> 24L*60L*60_000L; else -> 60_000L }
    private fun closedCandles(candles:List<CandleSnapshot>,bar:String,now:Long):List<CandleSnapshot>{
        val duration=barDurationMs(bar)
        return candles.mapNotNull{c->val t=if(c.time in 1L..999_999_999_999L)c.time*1000L else c.time
            if(t>0L&&t+duration<=now)c.copy(time=t)else null}.distinctBy{it.time}.sortedBy{it.time}
    }
    private fun loadBrainLocked() { brain.load(bots["BTC-USDT"]?.optJSONObject("aiBrain")) }
    private fun startTradingLoopLocked() {
        if (tradingExecutor != null) return
        tradingExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "btc-swing-trading").apply { isDaemon = true } }
        tradingExecutor?.scheduleWithFixedDelay({
            try { tradingTick() } catch (e: Exception) { synchronized(lock) { lastError = "Trading: ${e.message ?: "error"}".take(120) } }
        }, 1500L, 1000L, TimeUnit.MILLISECONDS)
    }
    private fun tradingStatusLocked(b: JSONObject, now: Long, text: String) {
        if (now - tradingStatusAt < 800L) return
        tradingStatusAt = now
        b.put("lastSignal", text)
    }
    private fun tradingTick() {
        synchronized(lock) {
            val b = bots["BTC-USDT"] ?: return
            val now = System.currentTimeMillis()
            val price = b.optDouble("price", 0.0)
            if (price <= 0.0) { tradingStatusLocked(b, now, "Esperando precio de BTC en directo"); return }

            // El motor solicita por sí mismo las tres temporalidades necesarias.
            // No depende de que el usuario abra la pantalla del gráfico.
            val requiredBars = listOf("15m", "1H", "4H")
            requiredBars.forEach { bar ->
                val key = "BTC-USDT|$bar"
                val candles = chartCandleCache[key]
                val lastClosed = candles?.lastOrNull()?.time ?: 0L
                val stale = candles == null || candles.isEmpty() || lastClosed <= 0L || now - lastClosed >= 2L * barDurationMs(bar)
                if (stale && chartRequests.add(key)) {
                    chartExecutor.execute {
                        try {
                            val parsed = fetchCandles("BTC-USDT", bar)?.mapNotNull { row ->
                                if (row.length() < 6) null else CandleSnapshot(row.optLong(0), row.optDouble(1), row.optDouble(2), row.optDouble(3), row.optDouble(4), row.optDouble(5))
                            }?.let { closedCandles(it, bar, System.currentTimeMillis()) }
                            if (!parsed.isNullOrEmpty()) synchronized(lock) { chartCandleCache[key] = parsed.takeLast(220) }
                        } catch (_: Exception) {
                            synchronized(lock) { lastError = "No se pudieron cargar velas cerradas BTC $bar" }
                        } finally { chartRequests.remove(key) }
                    }
                }
            }

            if (b.optString("side", "FLAT") != "FLAT") {
                manageSwingPriceLocked(b, price, now)
                return
            }
            if (!b.optBoolean("enabled", true)) {
                tradingStatusLocked(b, now, "Trading pausado · sin nuevas entradas")
                return
            }
            val c15 = chartCandleCache["BTC-USDT|15m"]
            val c1h = chartCandleCache["BTC-USDT|1H"]
            val c4h = chartCandleCache["BTC-USDT|4H"]
            if(c15==null||c15.size<60||c1h==null||c1h.size<25||c4h==null||c4h.size<15){
                tradingStatusLocked(b,now,"Cargando velas cerradas · 15m ${c15?.size?:0}/60 · 1H ${c1h?.size?:0}/25 · 4H ${c4h?.size?:0}/15");return
            }
            trendEntryLocked(b, now)
            if (b.optString("side", "FLAT") == "FLAT") {
                val last = b.optString("lastSignal", "")
                if (!last.startsWith("Swing filtrado")) tradingStatusLocked(b, now, "Analizando estrategia Swing BTC · 15m/1H/4H")
            }
        }
    }

    private fun trendEntryLocked(b: JSONObject, now: Long) {
        if (!b.optBoolean("enabled", true) || b.optString("side", "FLAT") != "FLAT") return
        if (now - b.optLong("swingLastSignalAt", 0L) < 60_000L) return
        val c15 = chartCandleCache["BTC-USDT|15m"] ?: return
        val c1h = chartCandleCache["BTC-USDT|1H"] ?: return
        val c4h = chartCandleCache["BTC-USDT|4H"] ?: return
        val signal = BitcoinSwingStrategy.evaluate(c15, c1h, c4h)
        if (signal == null) {
            b.put("lastSignal", "Sin señal confirmada · estrategia Swing esperando tendencia/ruptura")
            return
        }
        val price = b.optDouble("price", signal.price); val balance = b.optDouble("balance", 100.0)
        if (price <= 0.0 || balance <= 1.0) return
        val side=signal.side;val sign=if(side=="LONG")1.0 else -1.0
        val bookAgeMs=if(pionexBookUpdatedAt>0L)now-pionexBookUpdatedAt else Long.MAX_VALUE
        val liveBook=if(bookAgeMs in 0L..2500L&&pionexBookSource!="Tape L2")L2Metrics.compute(pionexBookBids,pionexBookAsks)else null
        val flowWindow=flowEvents.filter{now-it.first in 0L..30_000L};val flowAbs=flowWindow.sumOf{abs(it.second)}
        val flowScore=if(flowAbs>0.0)(flowWindow.sumOf{it.second}/flowAbs).coerceIn(-1.0,1.0)else 0.0
        val features=TradeFeatures(side,liveBook?.imbalance?:0.0,liveBook?.micropriceEdgeBps?:0.0,liveBook?.spreadBps?:1.0,
            flowScore,signal.atr/price*10_000.0,java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.HOUR_OF_DAY),
            pionexNextFundingRate,0.0,currentMtfScore)
        val probability=brain.predict(features).coerceIn(0.0,1.0)
        val costs = costRate()
        val expected = signal.targetDistance / price
        val stopRateCandidate = signal.stopDistance / price
        // El filtro evita señales con objetivo inferior al coste total, pero no exige que
        // un modelo sin suficientes cierres tenga ya una ventaja estadística demostrada.
        val expectedNet = probability * (expected - costs) - (1.0 - probability) * (stopRateCandidate + costs)
        if (expected <= costs + 0.0005 || (brain.realSamples >= 15 && expectedNet <= 0.0)) {
            b.put("lastSignal", String.format(Locale.US, "Señal descartada por costes/riesgo · p %.0f%% · EV %.3f%%", probability * 100.0, expectedNet * 100.0))
            return
        }
        val stopRate = (signal.stopDistance / price).coerceIn(0.004, 0.035)
        val targetRate = (signal.targetDistance / price).coerceIn(0.008, 0.08)
        val risk = (b.optDouble("risk", 5.0) / 100.0).coerceIn(0.01, 0.03) * (if (brain.lossStreak >= 3) 0.5 else 1.0)
        val sized = CompoundSizing.size(balance, price, b.optInt("leverage", 100), stopRate, risk, costs) ?: return
        val q1=now-(pionexQuoteAt["BTC-USDT"]?:0L);val q2=now-(bybitQuoteAt["BTC-USDT"]?:0L)
        val quoteAgeMs=listOf(q1,q2).filter{it>=0L}.minOrNull()?:Long.MAX_VALUE
        val executable=when{liveBook!=null->if(side=="LONG")liveBook.ask else liveBook.bid;quoteAgeMs<=5000L->price;else->0.0}
        if(executable<=0.0){b.put("lastSignal","Entrada omitida · cotización/libro sin datos ejecutables recientes");return}
        val entry=executable*(1.0+sign*SLIPPAGE_RATE)
        val stop=entry*(1.0-sign*stopRate);val target=entry*(1.0+sign*targetRate);val liq=entry*(1.0-sign*sized.liquidationDistanceRate)
        val audit=b.optJSONArray("decisionAudit")?:JSONArray()
        audit.put(JSONObject().put("decisionAt",now).put("candleTime",signal.candleTime).put("candleAgeMs",max(0L,now-signal.candleTime))
            .put("quoteAgeMs",quoteAgeMs).put("referencePrice",price).put("bookTimestamp",pionexBookUpdatedAt).put("bookAgeMs",if(pionexBookUpdatedAt>0L)bookAgeMs else -1L)
            .put("bookSequence",pionexBookSequence).put("bookSource",pionexBookSource).put("bookUsed",liveBook!=null)
            .put("bid",liveBook?.bid?:JSONObject.NULL).put("ask",liveBook?.ask?:JSONObject.NULL).put("spreadBps",liveBook?.spreadBps?:JSONObject.NULL)
            .put("imbalance",liveBook?.imbalance?:JSONObject.NULL).put("microprice",liveBook?.microprice?:JSONObject.NULL).put("micropriceEdgeBps",liveBook?.micropriceEdgeBps?:JSONObject.NULL)
            .put("flowScore",flowScore).put("mtfScore",currentMtfScore).put("side",side).put("executableReference",executable).put("assumedFill",entry)
            .put("assumedSlippageRate",SLIPPAGE_RATE).put("feeRatePerSide",TAKER_FEE_RATE))
        while(audit.length()>500)audit.remove(0)
        b.put("decisionAudit",audit).put("side",side).put("entry",entry).put("qty",sized.qty).put("stop",stop).put("target",target)
            .put("liqPrice",liq).put("entryTime",now).put("entryCandle",signal.candleTime)
            .put("entryBookTimestamp",pionexBookUpdatedAt).put("entryBookSequence",pionexBookSequence).put("entryBookSource",pionexBookSource).put("entryBookUsed",liveBook!=null)
            .put("entryBid",liveBook?.bid?:0.0).put("entryAsk",liveBook?.ask?:0.0).put("entrySpreadBps",liveBook?.spreadBps?:0.0)
            .put("entryImbalance",liveBook?.imbalance?:0.0).put("entryMicropriceEdgeBps",liveBook?.micropriceEdgeBps?:0.0)
            .put("entryExecutableReference",executable).put("entrySlippageRate",SLIPPAGE_RATE).put("entryFeeRate",TAKER_FEE_RATE)
            .put("entryStrategy","SWING_"+signal.regime).put("entryEffLeverage",sized.effectiveLeverage).put("entryPredictedWin",probability)
            .put("swingTargetRate",targetRate).put("maxFavorablePrice",entry).put("maxAdversePrice",entry).put("trailingActive",false)
            .put("entryAi",features.toJson()).put("swingLastSignalAt",now)
        recordExpertActivityLocked(b,"APERTURA","Swing "+signal.regime+" · "+side+" · IA "+String.format(Locale.US,"%.0f%%",probability*100.0),entry)
        b.put("lastSignal", "Swing " + signal.regime + " · " + side); saveLocked()
    }
    private fun manageSwingPriceLocked(b: JSONObject, livePrice: Double, now: Long) {
        val side = b.optString("side", "FLAT")
        val entry = b.optDouble("entry", 0.0)
        val qty = b.optDouble("qty", 0.0)
        if (side == "FLAT" || entry <= 0.0 || qty <= 0.0 || livePrice <= 0.0) return
        val sign = if (side == "LONG") 1.0 else -1.0
        val mark = livePrice
        val stop = b.optDouble("stop", 0.0)
        val target = b.optDouble("target", 0.0)
        val liq = b.optDouble("liqPrice", 0.0)
        val oldFav = b.optDouble("maxFavorablePrice", entry)
        val fav = if (side == "LONG") max(oldFav, mark) else min(oldFav, mark)
        b.put("maxFavorablePrice", fav)
        val riskDistance = abs(entry - stop)
        // El trailing no se activa por un pequeño rebote: primero debe avanzar al menos 1R.
        val favorableMove = (mark - entry) * sign
        var activeStop = stop
        if (riskDistance > 0.0 && favorableMove >= riskDistance && !b.optBoolean("trailingActive", false)) {
            val breakEven = entry * (1.0 + sign * (2.0 * (TAKER_FEE_RATE + SLIPPAGE_RATE) + 0.0003))
            if ((side == "LONG" && breakEven > activeStop) || (side == "SHORT" && breakEven < activeStop)) {
                activeStop = breakEven
                b.put("stop", activeStop).put("trailingActive", true)
                recordExpertActivityLocked(b, "PROTECCIÓN", "Swing: stop a break-even tras +1R", mark)
            }
        }
        // Tras 1.5R, seguir el máximo/mínimo favorable dejando margen amplio (0.75R).
        if (riskDistance > 0.0 && favorableMove >= 1.5 * riskDistance) {
            val candidate = fav - sign * (0.75 * riskDistance)
            if ((side == "LONG" && candidate > activeStop) || (side == "SHORT" && candidate < activeStop)) {
                activeStop = candidate
                b.put("stop", activeStop).put("trailingActive", true)
            }
        }
        val liquidated = liq > 0.0 && (if (side == "LONG") mark <= liq else mark >= liq)
        val stopHit = if (side == "LONG") mark <= activeStop else mark >= activeStop
        val targetHit = if (side == "LONG") mark >= target else mark <= target
        val reason = when {
            liquidated -> "Liquidación"
            stopHit -> if (b.optBoolean("trailingActive", false) && favorableMove > 0.0) "Stop en Ganancia (Swing trailing)" else "Stop Swing"
            targetHit -> "Objetivo Swing"
            else -> return
        }
        val rawExit = if (liquidated) liq else if (stopHit) activeStop else target
        closeTradeLocked(b, reason, rawExit, now)
    }

    private fun closeTradeLocked(b: JSONObject, reason: String, rawExit: Double, now: Long) {
        val side = b.optString("side", "FLAT")
        val entry = b.optDouble("entry", 0.0)
        val qty = b.optDouble("qty", 0.0)
        val entryNotional = qty * entry
        val liquidated = reason == "Liquidación"
        val exitPrice = if (liquidated) rawExit else rawExit * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
        val gross = (if (side == "LONG") exitPrice - entry else entry - exitPrice) * qty
        val entryFee=entryNotional*b.optDouble("entryFeeRate",TAKER_FEE_RATE);val exitNotional=qty*exitPrice
        val exitFee=exitNotional*TAKER_FEE_RATE;val fee=entryFee+exitFee
        val entryTime=b.optLong("entryTime",now);val funding=fundingCostLocked(entryNotional,side,entryTime,now)
        val balanceBefore = b.optDouble("balance", 100.0)
        val net = if (liquidated) -balanceBefore else gross - fee - funding
        val balanceAfter = max(0.0, balanceBefore + net)
        b.put("balance", balanceAfter)
        if (net > 0) b.put("wins", b.optInt("wins") + 1) else b.put("losses", b.optInt("losses") + 1)
        val peak = max(b.optDouble("peakBalance", balanceBefore), balanceAfter)
        val drawdown = if (peak > 0.0) 1.0 - balanceAfter / peak else 0.0
        b.put("peakBalance", peak).put("maxDrawdown", max(b.optDouble("maxDrawdown", 0.0), drawdown))
        val entryFeatures = TradeFeatures.fromJson(b.optJSONObject("entryAi"))
        val lesson = if (entryFeatures != null) brain.onRealTrade(entryFeatures, net, entryNotional, reason, max(0L, now - entryTime), costRate()) else ""
        val h = b.optJSONArray("history") ?: JSONArray()
        h.put(JSONObject()
            .put("type", "TRADE").put("symbol", "BTC-USDT").put("time", now).put("entryTime", entryTime)
            .put("side", side).put("entry", entry).put("exit", exitPrice).put("qty", qty)
            .put("entryNotional",entryNotional).put("exitNotional",exitNotional).put("gross",gross).put("entryFee",entryFee).put("exitFee",exitFee).put("fees",fee)
            .put("feeRatePerSide",TAKER_FEE_RATE).put("entryFeeRate",b.optDouble("entryFeeRate",TAKER_FEE_RATE))
            .put("entrySlippageRate",b.optDouble("entrySlippageRate",SLIPPAGE_RATE)).put("exitSlippageRate",SLIPPAGE_RATE)
            .put("funding",funding).put("fundingMethod","tasas históricas disponibles + previsión registrada")
            .put("entryBookTimestamp",b.optLong("entryBookTimestamp",0L)).put("entryBookSequence",b.optLong("entryBookSequence",0L))
            .put("entryBookSource",b.optString("entryBookSource","sin libro")).put("entryBookUsed",b.optBoolean("entryBookUsed",false))
            .put("entryBid",b.optDouble("entryBid",0.0)).put("entryAsk",b.optDouble("entryAsk",0.0))
            .put("entrySpreadBps",b.optDouble("entrySpreadBps",0.0)).put("entryImbalance",b.optDouble("entryImbalance",0.0))
            .put("entryMicropriceEdgeBps",b.optDouble("entryMicropriceEdgeBps",0.0)).put("entryExecutableReference",b.optDouble("entryExecutableReference",0.0))
            .put("pnl",net).put("balanceBefore",balanceBefore)
            .put("balanceAfter", balanceAfter).put("leverage", Math.round(b.optDouble("entryEffLeverage", 1.0)).toInt())
            .put("riskPercent", b.optDouble("risk", 5.0)).put("stop", b.optDouble("stop", 0.0)).put("target", b.optDouble("target", 0.0))
            .put("strategy", b.optString("entryStrategy", "SWING_TREND")).put("timeframe", "15m/1H/4H")
            .put("predictedWin", b.optDouble("entryPredictedWin", 0.5)).put("durationMs", max(0L, now - entryTime))
            .put("reason", reason).put("modelSamples", brain.samples).put("lesson", lesson))
        while (h.length() > 2000) h.remove(0)
        b.put("history", h).put("side", "FLAT").put("entry", 0.0).put("qty", 0.0).put("stop", 0.0).put("target", 0.0)
            .put("liqPrice", 0.0).put("trailingActive", false).put("trades", b.optInt("trades") + 1).put("lastTradeCloseAt", now)
            .remove("entryAi")
        recordExpertActivityLocked(b, "CIERRE", "Swing · $reason · neto " + String.format(Locale.US, "%+.2f", net) + " USDT", exitPrice, net)
        if (lesson.isNotEmpty()) recordExpertActivityLocked(b, "LECCIÓN", lesson, exitPrice)
        if (balanceAfter <= 0.5) {
            val refill = max(1.0, b.optDouble("initial", 100.0))
            b.put("balance", refill).put("injectedCapital", b.optDouble("injectedCapital", 0.0) + refill)
                .put("recharges", b.optInt("recharges", 0) + 1).put("peakBalance", refill)
            h.put(JSONObject().put("type", "RECHARGE").put("time", now).put("amount", refill)
                .put("reason", "Cuenta simulada recargada para continuar entrenamiento").put("balanceAfter", refill).put("pnl", 0.0))
        }
        saveLocked()
    }

    fun compoundingStatus(): String = synchronized(lock) {
        val b = bots["BTC-USDT"] ?: return@synchronized "Compuesto: sin datos"
        val initial = b.optDouble("initial", 100.0)
        val invested = initial + b.optDouble("injectedCapital", 0.0)
        val balance = b.optDouble("balance", initial)
        val trades = b.optInt("trades")
        val winRate = if (trades > 0) b.optInt("wins") * 100.0 / trades else 0.0
        val multiple = if (invested > 0.0) balance / invested else 0.0
        "Compuesto · " + String.format(Locale.US, "%.2f", invested) + " → " + String.format(Locale.US, "%.2f", balance) +
            " USDT (x" + String.format(Locale.US, "%.2f", multiple) + ") · " + trades + " cierres · acierto " +
            String.format(Locale.US, "%.0f", winRate) + "% · DD máx " + String.format(Locale.US, "%.1f", b.optDouble("maxDrawdown", 0.0) * 100.0) + "%"
    }

    fun aiMode(): String = synchronized(lock) { brain.mode }
    fun aiBrainSummary(): List<String> = synchronized(lock) { brain.summaryLines(costRate()) }
    fun aiLessons(n: Int): List<String> = synchronized(lock) { brain.lessonLines(n) }
    fun aiSetMode(name: String): Boolean = synchronized(lock) { val ok = brain.applyMode(name); if (ok) saveLocked(); ok }
    fun aiSetDailyLimit(percent: Double): Double = synchronized(lock) { brain.dailyLossLimit = percent / 100.0; brain.clamp(); saveLocked(); brain.dailyLossLimit * 100.0 }
    fun aiBlockHours(from: Int, to: Int) = synchronized(lock) {
        var h = from.coerceIn(0, 23); val end = to.coerceIn(0, 24) % 24; var guard = 0
        while (h != end && guard < 24) { brain.blockedHoursLocal.add(h); h = (h + 1) % 24; guard++ }
        saveLocked()
    }
    fun aiClearBlockedHours() = synchronized(lock) { brain.blockedHoursLocal.clear(); saveLocked() }
    fun aiChatAdd(role: String, text: String) = synchronized(lock) { brain.addChat(role, text); saveLocked() }
    fun aiChatMessages(): List<Pair<String, String>> = synchronized(lock) { brain.chatMessages() }
    fun aiWarmupLabel(): String = synchronized(lock) {
        if (brain.samples < brain.warmupSamples) "Aprendiendo · " + brain.samples + "/" + brain.warmupSamples
        else if (PixelAi.available) "Gemini Nano Activo · " + brain.samples + " muestras"
        else "Enjambre Activo · " + brain.samples + " muestras"
    }
    fun lastTradeSummary(): String = synchronized(lock) {
        val h = bots["BTC-USDT"]?.optJSONArray("history") ?: JSONArray()
        var i = h.length() - 1
        while (i >= 0) {
            val t = h.optJSONObject(i)
            if (t != null && t.optString("type") == "TRADE") {
                val lesson = t.optString("lesson", "")
                return@synchronized t.optString("side") + " · " + String.format(Locale.US, "%+.2f USDT", t.optDouble("pnl")) + " · " +
                    t.optString("reason") + " · duró " + (t.optLong("durationMs") / 1000) + " s" + (if (lesson.isNotEmpty()) "\n" + lesson else "")
            }
            i--
        }
        "Todavía no hay operaciones cerradas."
    }

    fun tickAll() {
        if (!initialized) return
        requestPionexFundingRatesIfDue()
        NewsIntelligence.pollIfDue()
        try { updateMultiTimeframeSwarm("BTC-USDT") }
        catch (e: Exception) { synchronized(lock) { lastError = "MTF: ${e.message ?: "sin conexión"}".take(120) } }
        synchronized(lock) { saveLocked() }
    }

    /**
     * Todos los bots del enjambre observan todas las temporalidades (1m, 5m, 15m, 1H, 4H) simultáneamente
     */
    private fun updateMultiTimeframeSwarm(symbol: String) {
        val bars = listOf("1m", "5m", "15m", "1H", "4H")
        val weights = mapOf("1m" to 0.30, "5m" to 0.25, "15m" to 0.20, "1H" to 0.15, "4H" to 0.10)
        val futures = bars.associateWith { bar -> ensembleExecutor.submit<List<JSONArray>?> { fetchCandles(symbol, bar) } }
        var weightedScore = 0.0
        var totalWeight = 0.0
        val details = mutableListOf<String>()

        for (bar in bars) {
            val candles = try { futures[bar]?.get(3500, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
            if (candles == null || candles.size < 30) {
                details.add("$bar:—")
                continue
            }
            val parsed=closedCandles(candles.mapNotNull{r->if(r.length()<6)null else CandleSnapshot(r.optLong(0),r.optDouble(1),r.optDouble(2),r.optDouble(3),r.optDouble(4),r.optDouble(5))},bar,System.currentTimeMillis())
            if(parsed.size<30){details.add("$bar:velas cerradas insuficientes");continue}
            synchronized(lock){chartCandleCache["$symbol|$bar"]=parsed.takeLast(220)}
            val closes = parsed.map { it.close }
            val close = closes.lastOrNull() ?: continue
            val ema9 = ema(closes, 9); val ema21 = ema(closes, 21); val rsiVal = rsi(closes, 14)
            val dir = when {
                close > ema9 && ema9 > ema21 && rsiVal > 51.0 -> 1.0
                close < ema9 && ema9 < ema21 && rsiVal < 49.0 -> -1.0
                close > ema21 -> 0.4
                close < ema21 -> -0.4
                else -> 0.0
            }
            val w = weights[bar] ?: 0.2
            weightedScore += dir * w
            totalWeight += w
            details.add("$bar:${if (dir > 0.2) "↗" else if (dir < -0.2) "↘" else "→"}")
        }
        val finalScore = if (totalWeight > 0.0) (weightedScore / totalWeight).coerceIn(-1.0, 1.0) else 0.0
        currentMtfScore = finalScore
        synchronized(lock) {
            bots[symbol]?.put("expertConsensusDetail", details.joinToString(" · ") + String.format(Locale.US, " (MTF %+.2f)", finalScore))
        }
    }

    fun strategyName(symbol: String): String = "Enjambre Multi-Temporal + Order Flow L2 + Gemini Nano"

    private fun fetchCandles(symbol: String, bar: String = "1H"): List<JSONArray>? {
        val interval = when (bar) { "1m" -> "1"; "5m" -> "5"; "15m" -> "15"; "1H" -> "60"; "4H" -> "240"; "1D" -> "D"; else -> "60" }
        try {
            val url = URL("https://api.bybit.com/v5/market/kline?category=linear&symbol=" + symbol.replace("-", "") + "&interval=" + interval + "&limit=220")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 2500; requestMethod = "GET" }
            try {
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = root.optJSONObject("result")?.optJSONArray("list")
                if (root.optInt("retCode", -1) == 0 && arr != null && arr.length() > 0) return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.reversed()
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
        try {
            val url = URL("https://www.okx.com/api/v5/market/candles?instId=$symbol&bar=$bar&limit=220")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 2500; requestMethod = "GET" }
            try { val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }); if (root.optString("code") != "0") return null; val arr = root.optJSONArray("data") ?: return null; return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.reversed() } finally { conn.disconnect() }
        } catch (_: Exception) { return null }
    }

    private fun ema(v: List<Double>, n: Int): Double {
        if (v.isEmpty()) return 0.0
        val k = 2.0 / (n + 1); var e = v.first()
        v.drop(1).forEach { e = it * k + e * (1 - k) }; return e
    }
    private fun rsi(v: List<Double>, n: Int): Double {
        if (v.size <= n) return 50.0
        var up = 0.0; var down = 0.0
        for (i in v.size - n until v.size) { val d = v[i] - v[i - 1]; if (d > 0) up += d else down -= d }
        if (down == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + up / down)
    }
    private fun floatingPnl(b: JSONObject): Double {
        val entry = b.optDouble("entry", 0.0); val price = b.optDouble("price", 0.0)
        val side = b.optString("side", "FLAT"); val qty = b.optDouble("qty", 0.0)
        return if (entry > 0 && price > 0 && qty > 0 && side != "FLAT") (if (side == "LONG") price - entry else entry - price) * qty else 0.0
    }

    private fun saveLocked() {
        if (!::app.isInitialized) return
        try {
            val savedAt = System.currentTimeMillis()
            bots["BTC-USDT"]?.put("aiBrain", brain.toJson())?.put("learned", brain.samples)
            val arr = JSONArray(); bots.values.forEach { arr.put(it) }
            val root = JSONObject().put("schemaVersion", 4).put("savedAt", savedAt).put("appVersion", "0.21-swing-ai-backup").put("bots", arr)
            val backup = File(app.filesDir, "CryptoFutures"); backup.mkdirs()
            File(backup, "state-v1.json.tmp").writeText(root.toString())
            File(backup, "state-v1.json.tmp").renameTo(File(backup, "state-v1.json"))
            saveUserBackup(root)
        } catch (e: Exception) { lastError = "No se pudo guardar: ${e.message}".take(120) }
    }
}
