package com.joapetis.bitcoinfuturos

import android.content.Context
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
import kotlin.math.sqrt

data class CandleSnapshot(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

data class BotSnapshot(
    val symbol: String, val balance: Double, val price: Double, val pnl: Double,
    val position: String, val entry: Double, val trades: Int, val wins: Int,
    val status: String, val leverage: Int, val risk: Double, val learned: Int
)

object NativeTradingEngine {
    // Conservative assumptions for a simulated taker order; verify the account's actual fee tier.
    private const val TAKER_FEE_RATE = 0.0005       // Binance USDⓈ-M Futures standard user VIP 0 taker: 0.05% per side
    private const val MAKER_FEE_RATE = 0.0002       // Binance USDⓈ-M Futures standard user VIP 0 maker: 0.02% per side
    private const val SLIPPAGE_RATE = 0.0002        // modelled adverse fill, not a Binance commission
    private const val FUNDING_RATE_PER_8H = 0.0001  // assumed 0.01% per 8h; actual funding varies
    private const val MIN_REWARD_TO_COST = 2.0
    private const val MIN_LEARNED_TRADES = 20
    private const val MIN_PREDICTED_WIN = 0.53
    private const val HOUR_MS = 3_600_000L

    private val symbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "DOGE-USDT", "BNB-USDT", "LINK-USDT", "ADA-USDT", "AVAX-USDT", "SUI-USDT")
    private val lock = Any()
    private val bots = linkedMapOf<String, JSONObject>()
    private val candleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartCandleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val chartRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val quoteClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var bybitSocket: WebSocket? = null
    private var binanceSocket: WebSocket? = null
    private val bybitQuotes = linkedMapOf<String, Double>()
    private val binanceQuotes = linkedMapOf<String, Double>()
    private val bybitQuoteAt = linkedMapOf<String, Long>()
    private val binanceQuoteAt = linkedMapOf<String, Long>()
    private var bybitConnectedAt = 0L
    private var binanceConnectedAt = 0L
    private var quoteSocketStarted = false
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
                .put("lastCandle", 0L).put("botBar", "1H").put("nnBias", 0.0).put("nnTrend", 0.0).put("nnMomentum", 0.0)
                .put("lastSignal", "Analizando mercado").put("history", JSONArray())
        }
        // Keep model state and trade ledger intact across code/version upgrades.
        saveLocked()
        initialized = true
        startQuoteStreamsLocked()
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
                (if (side == "LONG") p-entry else entry-p) * b.optDouble("qty", 0.0) else 0.0
            BotSnapshot(s, b.optDouble("balance",100.0), p, b.optDouble("balance",100.0)-b.optDouble("initial",100.0)+floating,
                side, entry, b.optInt("trades"), b.optInt("wins"), b.optString("lastSignal"),
                b.optInt("leverage",100), b.optDouble("risk",5.0), b.optInt("learned"))
        }
    }
    fun candles(symbol: String): List<CandleSnapshot> = synchronized(lock) { candleCache[symbol]?.toList() ?: emptyList() }

    // Bybit linear futures is the primary live quote feed; Binance Futures is an independent
    // secondary reference. OKX REST remains the fallback when both streams are unavailable.
    fun liveQuoteSource(): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        when {
            bybitQuoteAt.values.any { now - it < 15_000L } -> "Bybit Futures · principal"
            binanceQuoteAt.values.any { now - it < 15_000L } -> "Binance Futures · respaldo"
            else -> "OKX REST · respaldo"
        }
    }

    private fun startQuoteStreamsLocked() {
        if (quoteSocketStarted) return
        quoteSocketStarted = true
        val bybitRequest = Request.Builder().url("wss://stream.bybit.com/v5/public/linear").build()
        bybitSocket = quoteClient.newWebSocket(bybitRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                bybitConnectedAt = System.currentTimeMillis()
                val args = JSONArray()
                symbols.forEach { args.put("tickers." + it.replace("-", "")) }
                webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val topic = root.optString("topic")
                    if (!topic.startsWith("tickers.")) return
                    val data = root.optJSONObject("data") ?: return
                    val symbol = data.optString("symbol").replace("USDT", "-USDT")
                    val price = data.optString("lastPrice").toDoubleOrNull() ?: return
                    if (symbol in symbols && price > 0.0) synchronized(lock) {
                        bybitQuotes[symbol] = price
                        bybitQuoteAt[symbol] = System.currentTimeMillis()
                        bots[symbol]?.put("price", price)?.put("priceSource", "BYBIT")
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) { if (bybitSocket === webSocket) bybitSocket = null }
                quoteClient.dispatcher.executorService.execute {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return@execute }
                    synchronized(lock) { bybitSocket = null; startSingleBybitReconnectLocked() }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) { if (bybitSocket === webSocket) bybitSocket = null }
            }
        })
        val streams = symbols.joinToString("/") { it.replace("-", "").lowercase(Locale.US) + "@ticker" }
        val binanceRequest = Request.Builder().url("wss://fstream.binance.com/stream?streams=$streams").build()
        binanceSocket = quoteClient.newWebSocket(binanceRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { binanceConnectedAt = System.currentTimeMillis() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text).optJSONObject("data") ?: return
                    val symbol = data.optString("s").replace("USDT", "-USDT")
                    val price = data.optString("c").toDoubleOrNull() ?: return
                    if (symbol in symbols && price > 0.0) synchronized(lock) {
                        binanceQuotes[symbol] = price
                        binanceQuoteAt[symbol] = System.currentTimeMillis()
                        // Binance only takes over if this symbol has no recent Bybit ticks.
                        if (System.currentTimeMillis() - (bybitQuoteAt[symbol] ?: 0L) > 15_000L) {
                            bots[symbol]?.put("price", price)?.put("priceSource", "BINANCE")
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) { if (binanceSocket === webSocket) binanceSocket = null }
                quoteClient.dispatcher.executorService.execute {
                    try { Thread.sleep(4000) } catch (_: InterruptedException) { return@execute }
                    synchronized(lock) { binanceSocket = null; startBinanceReconnectLocked() }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) { if (binanceSocket === webSocket) binanceSocket = null }
            }
        })
    }

    private fun startSingleBybitReconnectLocked() {
        if (bybitSocket != null) return
        bybitSocket = quoteClient.newWebSocket(Request.Builder().url("wss://stream.bybit.com/v5/public/linear").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    bybitConnectedAt = System.currentTimeMillis()
                    val args = JSONArray(); symbols.forEach { args.put("tickers." + it.replace("-", "")) }
                    webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text); if (!root.optString("topic").startsWith("tickers.")) return
                        val data = root.optJSONObject("data") ?: return
                        val symbol = data.optString("symbol").replace("USDT", "-USDT")
                        val price = data.optString("lastPrice").toDoubleOrNull() ?: return
                        if (symbol in symbols && price > 0.0) synchronized(lock) {
                            bybitQuotes[symbol] = price; bybitQuoteAt[symbol] = System.currentTimeMillis(); bots[symbol]?.put("price", price)?.put("priceSource", "BYBIT")
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) { if (bybitSocket === webSocket) bybitSocket = null }
                    quoteClient.dispatcher.executorService.execute {
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { return@execute }
                        synchronized(lock) { startSingleBybitReconnectLocked() }
                    }
                }
            })
    }

    private fun startBinanceReconnectLocked() {
        if (binanceSocket != null) return
        val streams = symbols.joinToString("/") { it.replace("-", "").lowercase(Locale.US) + "@ticker" }
        binanceSocket = quoteClient.newWebSocket(Request.Builder().url("wss://fstream.binance.com/stream?streams=$streams").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { binanceConnectedAt = System.currentTimeMillis() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val data = JSONObject(text).optJSONObject("data") ?: return
                        val symbol = data.optString("s").replace("USDT", "-USDT")
                        val price = data.optString("c").toDoubleOrNull() ?: return
                        if (symbol in symbols && price > 0.0) synchronized(lock) {
                            binanceQuotes[symbol] = price
                            binanceQuoteAt[symbol] = System.currentTimeMillis()
                            if (System.currentTimeMillis() - (bybitQuoteAt[symbol] ?: 0L) > 15_000L)
                                bots[symbol]?.put("price", price)?.put("priceSource", "BINANCE")
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) { if (binanceSocket === webSocket) binanceSocket = null }
                    quoteClient.dispatcher.executorService.execute {
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { return@execute }
                        synchronized(lock) { startBinanceReconnectLocked() }
                    }
                }
            })
    }

    // Chart intervals are display-only: separate cache from bot strategy candles.
    fun chartCandles(symbol: String, bar: String): List<CandleSnapshot> = synchronized(lock) {
        chartCandleCache["$symbol|$bar"]?.toList() ?: emptyList()
    }

    fun requestChartCandles(symbol: String, bar: String, onLoaded: () -> Unit) {
        val supported = setOf("1m", "5m", "15m", "1H", "4H", "1D")
        if (bar !in supported) return
        val key = "$symbol|$bar"
        if (!chartRequests.add(key)) { try { onLoaded() } catch (_: Exception) {}; return }
        chartExecutor.execute {
            try {
                val parsed = fetchCandles(symbol, bar)?.mapNotNull { row ->
                    if (row.length() < 6) null else CandleSnapshot(row.optLong(0), row.optDouble(1), row.optDouble(2), row.optDouble(3), row.optDouble(4), row.optDouble(5))
                }
                if (!parsed.isNullOrEmpty()) synchronized(lock) { chartCandleCache[key] = parsed.takeLast(220) }
            } catch (e: Exception) { synchronized(lock) { lastError = "Gráfico $symbol $bar: ${e.message ?: "sin conexión"}".take(120) } }
            finally { chartRequests.remove(key); try { onLoaded() } catch (_: Exception) {} }
        }
    }
    fun marketChange24h(symbol: String): Double = synchronized(lock) {
        val c = candleCache[symbol] ?: return@synchronized 0.0
        if (c.size < 25 || c[c.size-25].close == 0.0) 0.0 else (c.last().close / c[c.size-25].close - 1.0) * 100.0
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
        // Price updates are independent from strategy candles: a candle request must never freeze the quote.
        val streamPrice = synchronized(lock) {
            val now = System.currentTimeMillis()
            when {
                now - (bybitQuoteAt[symbol] ?: 0L) < 15_000L -> bybitQuotes[symbol]
                now - (binanceQuoteAt[symbol] ?: 0L) < 15_000L -> binanceQuotes[symbol]
                else -> null
            }
        }
        val streamUnavailable = synchronized(lock) {
            val now = System.currentTimeMillis()
            now - (bybitQuoteAt[symbol] ?: 0L) >= 15_000L &&
                now - (binanceQuoteAt[symbol] ?: 0L) >= 15_000L
        }
        // Avoid serial REST calls while either public futures stream is healthy.
        val livePrice = streamPrice ?: if (streamUnavailable) fetchLivePrice(symbol) else null
        if (livePrice != null && livePrice > 0.0) {
            synchronized(lock) {
                val b = bots[symbol]
                if (streamPrice != null || streamUnavailable || b?.optDouble("price", 0.0) == 0.0)
                    b?.put("price", livePrice)
            }
        }
        val chosenBar = synchronized(lock) { bots[symbol]?.optString("botBar", "1H") ?: "1H" }
        val candles = fetchCandles(symbol, chosenBar) ?: return
        if (candles.size < 60) return
        val closes = candles.map { it.optDouble(4) }
        val highs = candles.map { it.optDouble(2) }
        val lows = candles.map { it.optDouble(3) }
        synchronized(lock) {
            candleCache[symbol] = candles.takeLast(80).mapNotNull { row ->
                if (row.length() < 6) null else CandleSnapshot(row.optLong(0), row.optDouble(1), row.optDouble(2), row.optDouble(3), row.optDouble(4), row.optDouble(5))
            }
        }
        val last = candles.last()
        val close = closes.last()
        val prevCandleTime = last.optLong(0)
        synchronized(lock) {
            val b = bots[symbol] ?: return
            // Keep the last valid live ticker; only use candle close before the first ticker succeeds.
            if (b.optDouble("price", 0.0) <= 0.0) b.put("price", close)
            val entry = b.optDouble("entry")
            val side = b.optString("side")
            val atr = atr(highs, lows, closes, 14)
            val ema9 = ema(closes, 9); val ema21 = ema(closes, 21)
            val ema50 = ema(closes, 50); val ema200 = ema(closes, min(200, closes.size))
            val rsi = rsi(closes, 14)
            val returns3 = returnOver(closes, 3)
            val returns12 = returnOver(closes, 12)
            val returns48 = returnOver(closes, 48)
            val returns = returns12
            val trend = ((ema9-ema21)/close*100.0).coerceIn(-3.0,3.0)
            val regime = if (abs(ema50-ema200)/close > 0.004) "TENDENCIA" else "RANGO"
            val volumes = candles.map { it.optDouble(5) }
            val volNow = volumes.lastOrNull() ?: 0.0
            val volAvg = volumes.takeLast(21).dropLast(1).average().takeIf { it > 0.0 } ?: volNow
            val volumeRatio = if (volAvg > 0.0) volNow / volAvg else 1.0
            val atrPct = if (close > 0.0) atr / close else 0.0
            val rsiFeature = ((rsi - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            val emaAlignment = ((if (ema9 > ema21) 1.0 else -1.0) +
                (if (ema21 > ema50) 1.0 else -1.0) +
                (if (ema50 > ema200) 1.0 else -1.0)) / 3.0
            val efficiency = directionalEfficiency(closes, 12)
            val lastRange = max(1e-12, highs.last() - lows.last())
            val candleBody = ((close - last.optDouble(1)) / lastRange).coerceIn(-1.0, 1.0)
            val features = modelFeatures(trend, returns3 * 100.0, returns12 * 100.0, returns48 * 100.0,
                atrPct, volumeRatio, rsiFeature, emaAlignment, efficiency, candleBody,
                ((ema50 - ema200) / close * 100.0).coerceIn(-3.0, 3.0))
            val strategy = strategyName(symbol)
            val signal = strategySignal(symbol, candles, closes, highs, lows, ema9, ema21, ema50, ema200, rsi, atr, regime, returns)
            b.put("strategy", strategy)
            b.put("lastSignal", "$strategy · $regime · RSI ${rsi.toInt()} · Señal $signal")
            val newCandle = prevCandleTime > b.optLong("lastCandle", 0L)
            if (newCandle) b.put("lastCandle", prevCandleTime)
            val adaptiveBar = chooseBotTimeframe(closes, highs, lows, ema9, ema21, ema50, ema200, atr, close)
            if (adaptiveBar != chosenBar) {
                b.put("botBar", adaptiveBar).put("lastCandle", 0L)
                b.put("lastSignal", strategy + " · temporalidad adaptativa " + chosenBar + " → " + adaptiveBar)
            }
            if (side != "FLAT" && entry > 0) {
                val stop = b.optDouble("stop")
                val target = b.optDouble("target")
                val candleHigh = highs.last()
                val candleLow = lows.last()
                // Ignore the pre-entry part of this hourly candle to avoid look-ahead exits.
                val afterEntryCandle = prevCandleTime > b.optLong("entryCandle", prevCandleTime)
                val stopHit = afterEntryCandle && (if (side == "LONG") candleLow <= stop else candleHigh >= stop)
                val targetHit = afterEntryCandle && (if (side == "LONG") candleHigh >= target else candleLow <= target)
                if (stopHit || targetHit || (newCandle && signal != "FLAT" && signal != side)) {
                    val rawExit = if (stopHit) stop else if (targetHit) target else close
                    // Simulated market execution: taker commission + adverse slippage on both sides.
                    val qty = b.optDouble("qty")
                    val entryNotional = qty * entry
                    val exitPrice = rawExit * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
                    val gross = (if (side == "LONG") exitPrice-entry else entry-exitPrice) * qty
                    val fee = (entryNotional + qty * exitPrice) * TAKER_FEE_RATE
                    val barsHeld = max(1L, (prevCandleTime - b.optLong("entryCandle", prevCandleTime)) / when (chosenBar) { "15m" -> 900_000L; "4H" -> 14_400_000L; "1D" -> 86_400_000L; else -> HOUR_MS })
                    val funding = entryNotional * FUNDING_RATE_PER_8H * (barsHeld / 8.0)
                    val net = gross - fee - funding
                    b.put("balance", max(0.0, b.optDouble("balance")+net))
                    if (net > 0) b.put("wins", b.optInt("wins")+1) else b.put("losses", b.optInt("losses")+1)
                    learn(b, net > 0, features)
                    val h = b.optJSONArray("history") ?: JSONArray()
                    h.put(JSONObject().put("time", System.currentTimeMillis()).put("side", side).put("entry",entry).put("exit",exitPrice).put("pnl",net).put("reason",if(stopHit) "Stop ATR" else if(targetHit) "Objetivo ATR" else "Señal contraria"))
                    while (h.length()>300) h.remove(0)
                    b.put("history",h).put("side","FLAT").put("entry",0.0).put("qty",0.0).put("stop",0.0).put("target",0.0).put("trades",b.optInt("trades")+1)
                }
            } else if (side == "FLAT" && newCandle && signal != "FLAT" && b.optDouble("balance") > 0.5 && b.optLong("lastEntryCandle", -1L) != prevCandleTime) {
                val riskCash = b.optDouble("balance") * b.optDouble("risk",5.0)/100.0
                val stopDistance = max(atr*1.8, close*0.001)
                val targetDistance = atr*2.7
                val estimatedRoundTripCostRate = 2.0 * (TAKER_FEE_RATE + SLIPPAGE_RATE) + FUNDING_RATE_PER_8H
                val expectedMoveRate = targetDistance / close
                val predictedWin = predictedWinProbability(b, features)
                val sampleCount = b.optInt("learned", 0)
                // Avoid trades whose target cannot reasonably cover fees/slippage/funding.
                // Once enough closes exist, the on-device online learner can veto weak setups.
                val costsCovered = expectedMoveRate >= estimatedRoundTripCostRate * MIN_REWARD_TO_COST
                val learnerAllows = sampleCount < MIN_LEARNED_TRADES || predictedWin >= MIN_PREDICTED_WIN
                val maxNotional = b.optDouble("balance") * b.optInt("leverage",100)
                val qty = min(riskCash / stopDistance, maxNotional / close)
                if (costsCovered && learnerAllows && qty > 0 && qty.isFinite()) {
                    val executedEntry = close * (if (signal == "LONG") 1.0 + SLIPPAGE_RATE else 1.0 - SLIPPAGE_RATE)
                    val stop = if(signal=="LONG") executedEntry-stopDistance else executedEntry+stopDistance
                    val target = if(signal=="LONG") executedEntry+targetDistance else executedEntry-targetDistance
                    b.put("side",signal).put("entry",executedEntry).put("qty",qty).put("stop",stop).put("target",target)
                        .put("lastEntryCandle",prevCandleTime).put("entryCandle",prevCandleTime)
                } else {
                    b.put("lastSignal", "$strategy · Entrada filtrada por costes/IA (${(predictedWin*100).toInt()}%)")
                }
            }
        }
    }

    private fun chooseBotTimeframe(
        closes: List<Double>, highs: List<Double>, lows: List<Double>,
        ema9: Double, ema21: Double, ema50: Double, ema200: Double,
        atr: Double, close: Double
    ): String {
        if (closes.size < 80 || close <= 0.0) return "1H"
        val atrPct = atr / close
        val trendStrength = abs(ema9 - ema21) / close
        val longTrend = abs(ema50 - ema200) / close
        val lastReturns = if (closes.size > 12 && closes[closes.lastIndex - 12] > 0.0)
            abs(close / closes[closes.lastIndex - 12] - 1.0) else 0.0
        return when {
            atrPct >= 0.012 || lastReturns >= 0.035 -> "15m"
            longTrend >= 0.012 && trendStrength >= 0.003 -> "4H"
            atrPct < 0.0025 && trendStrength < 0.001 -> "4H"
            else -> "1H"
        }
    }

    // Cada símbolo tiene una familia de señales propia; no se comparte un score genérico.
    fun strategyName(symbol: String): String = when(symbol) {
        "BTC-USDT" -> "Tendencia EMA + breakout"
        "ETH-USDT" -> "Cruce EMA + momentum RSI"
        "SOL-USDT" -> "Ruptura de volatilidad ATR"
        "XRP-USDT" -> "Reversión a la media"
        "DOGE-USDT" -> "Impulso con filtro de volumen"
        "BNB-USDT" -> "Retroceso en tendencia"
        "LINK-USDT" -> "Ruptura Donchian"
        "ADA-USDT" -> "Reversión Bollinger/RSI"
        "AVAX-USDT" -> "Tendencia con filtro ATR"
        "SUI-USDT" -> "Momentum multi-ventana"
        else -> "Estrategia conservadora"
    }

    private fun strategySignal(
        symbol:String, candles:List<JSONArray>, closes:List<Double>, highs:List<Double>, lows:List<Double>,
        ema9:Double, ema21:Double, ema50:Double, ema200:Double, rsi:Double, atr:Double,
        regime:String, returns:Double
    ):String {
        if(closes.size<60) return "FLAT"
        val close=closes.last()
        val prev=closes[closes.lastIndex-1]
        val recentHigh=highs.takeLast(21).dropLast(1).maxOrNull() ?: close
        val recentLow=lows.takeLast(21).dropLast(1).minOrNull() ?: close
        val range=closes.takeLast(20)
        val mean=range.average()
        val sd=sqrt(range.sumOf{(it-mean)*(it-mean)}/range.size)
        val upper=mean+2.0*sd
        val lower=mean-2.0*sd
        val volumes=candles.map{it.optDouble(5)}
        val volNow=volumes.lastOrNull() ?: 0.0
        val volAvg=volumes.takeLast(20).dropLast(1).average().takeIf{it>0} ?: volNow
        val volRatio=if(volAvg>0) volNow/volAvg else 1.0
        val atrPct=if(close>0) atr/close else 0.0
        return when(symbol) {
            // Trend + breakout, confirmation by longer EMA and RSI; no signal in flat markets.
            "BTC-USDT" -> when {
                close>recentHigh && ema50>ema200 && rsi in 52.0..72.0 -> "LONG"
                close<recentLow && ema50<ema200 && rsi in 28.0..48.0 -> "SHORT"
                else -> "FLAT"
            }
            // EMA cross direction with momentum confirmation.
            "ETH-USDT" -> when {
                ema9>ema21 && prev<=ema9 && returns>0.001 && rsi in 50.0..70.0 -> "LONG"
                ema9<ema21 && prev>=ema9 && returns< -0.001 && rsi in 30.0..50.0 -> "SHORT"
                else -> "FLAT"
            }
            // High-volatility breakout with ATR expansion.
            "SOL-USDT" -> when {
                close>recentHigh && atrPct>0.006 && volRatio>1.05 -> "LONG"
                close<recentLow && atrPct>0.006 && volRatio>1.05 -> "SHORT"
                else -> "FLAT"
            }
            // Range-only RSI reversal, deliberately blocked in a trend regime.
            "XRP-USDT" -> if(regime=="RANGO") when {
                rsi<27 && close<lower -> "LONG"
                rsi>73 && close>upper -> "SHORT"
                else -> "FLAT"
            } else "FLAT"
            // DOGE requires unusual volume and directional short-term impulse.
            "DOGE-USDT" -> when {
                volRatio>1.8 && returns>0.008 && ema9>ema21 && rsi in 52.0..78.0 -> "LONG"
                volRatio>1.8 && returns< -0.008 && ema9<ema21 && rsi in 22.0..48.0 -> "SHORT"
                else -> "FLAT"
            }
            // BNB enters a pullback only when the larger trend remains intact.
            "BNB-USDT" -> when {
                ema50>ema200 && close>ema50 && close<ema21 && rsi in 38.0..52.0 -> "LONG"
                ema50<ema200 && close<ema50 && close>ema21 && rsi in 48.0..62.0 -> "SHORT"
                else -> "FLAT"
            }
            // LINK uses a wider Donchian breakout window.
            "LINK-USDT" -> {
                val hi=highs.takeLast(56).dropLast(1).maxOrNull() ?: recentHigh
                val lo=lows.takeLast(56).dropLast(1).minOrNull() ?: recentLow
                when { close>hi && ema50>ema200 -> "LONG"; close<lo && ema50<ema200 -> "SHORT"; else -> "FLAT" }
            }
            // ADA uses Bollinger-style mean reversion with RSI confirmation.
            "ADA-USDT" -> if(regime=="RANGO") when {
                close<lower && rsi<32 -> "LONG"
                close>upper && rsi>68 -> "SHORT"
                else -> "FLAT"
            } else "FLAT"
            // AVAX only trades a clear trend and requires movement larger than noise.
            "AVAX-USDT" -> when {
                ema9>ema21 && ema21>ema50 && ema50>ema200 && returns>0.003 && atrPct>0.004 -> "LONG"
                ema9<ema21 && ema21<ema50 && ema50<ema200 && returns< -0.003 && atrPct>0.004 -> "SHORT"
                else -> "FLAT"
            }
            // SUI requires both 4h-ish and 12h momentum to agree with EMA direction.
            "SUI-USDT" -> {
                val m4=if(closes[closes.lastIndex-4]!=0.0) close/closes[closes.lastIndex-4]-1 else 0.0
                when {
                    m4>0.006 && returns>0.01 && ema9>ema21 && rsi in 55.0..78.0 -> "LONG"
                    m4< -0.006 && returns< -0.01 && ema9<ema21 && rsi in 22.0..45.0 -> "SHORT"
                    else -> "FLAT"
                }
            }
            else -> "FLAT"
        }
    }

    private fun returnOver(closes: List<Double>, bars: Int): Double {
        if (closes.size <= bars) return 0.0
        val base = closes[closes.lastIndex - bars]
        return if (base > 0.0) (closes.last() / base - 1.0).coerceIn(-1.0, 1.0) else 0.0
    }

    private fun directionalEfficiency(closes: List<Double>, bars: Int): Double {
        if (closes.size <= bars) return 0.0
        val start = closes.size - bars - 1
        var path = 0.0
        for (i in start + 1 until closes.size) path += abs(closes[i] - closes[i - 1])
        val net = closes.last() - closes[start]
        return if (path > 0.0) (net / path).coerceIn(-1.0, 1.0) else 0.0
    }

    // 11 market features + bias: trend, 3 momentum horizons, ATR volatility,
    // relative volume, RSI, EMA alignment, directional efficiency, candle body and long trend.
    private fun modelFeatures(
        trend: Double, momentum3: Double, momentum12: Double, momentum48: Double,
        atrPct: Double, volumeRatio: Double, rsiCentered: Double, emaAlignment: Double,
        efficiency: Double, candleBody: Double, longTrend: Double
    ): DoubleArray = doubleArrayOf(
        1.0,
        (trend / 3.0).coerceIn(-1.0, 1.0),
        (momentum3 / 2.0).coerceIn(-1.0, 1.0),
        (momentum12 / 5.0).coerceIn(-1.0, 1.0),
        (momentum48 / 12.0).coerceIn(-1.0, 1.0),
        (atrPct / 0.02).coerceIn(0.0, 1.0),
        (kotlin.math.ln(max(0.1, volumeRatio)) / 2.0).coerceIn(-1.0, 1.0),
        rsiCentered.coerceIn(-1.0, 1.0),
        emaAlignment.coerceIn(-1.0, 1.0),
        efficiency.coerceIn(-1.0, 1.0),
        candleBody.coerceIn(-1.0, 1.0),
        (longTrend / 3.0).coerceIn(-1.0, 1.0)
    )

    // Local 12-10-1 neural network. Inputs and weights persist per bot; inference needs no internet.
    // V2 keys intentionally keep legacy weights intact while allowing the richer model to initialize.
    private fun modelWeights(b: JSONObject, key: String, count: Int, scale: Double): DoubleArray {
        val saved = b.optJSONArray(key)
        if (saved != null && saved.length() == count) return DoubleArray(count) { saved.optDouble(it) }
        return DoubleArray(count) { i ->
            val v = kotlin.math.sin((i + 1) * 12.9898 + key.hashCode()) * 43758.5453
            ((v - kotlin.math.floor(v)) * 2.0 - 1.0) * scale
        }
    }

    private fun saveWeights(b: JSONObject, key: String, values: DoubleArray) {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        b.put(key, arr)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + Math.exp(-x.coerceIn(-20.0, 20.0)))

    private fun forward(features: DoubleArray, w1: DoubleArray, w2: DoubleArray): Pair<DoubleArray, Double> {
        val hidden = DoubleArray(10)
        for (h in 0 until 10) {
            var z = 0.0
            for (i in features.indices) z += features[i] * w1[h * features.size + i]
            hidden[h] = kotlin.math.tanh(z.coerceIn(-10.0, 10.0))
        }
        var out = w2[0]
        for (h in 0 until 10) out += hidden[h] * w2[h + 1]
        return hidden to sigmoid(out)
    }

    private fun predictedWinProbability(b: JSONObject, features: DoubleArray): Double {
        val w1 = modelWeights(b, "mlpV2W1", 120, 0.08)
        val w2 = modelWeights(b, "mlpV2W2", 11, 0.08)
        return forward(features, w1, w2).second
    }

    private fun learn(b: JSONObject, won: Boolean, features: DoubleArray) {
        val y = if (won) 1.0 else 0.0
        val w1 = modelWeights(b, "mlpV2W1", 120, 0.08)
        val w2 = modelWeights(b, "mlpV2W2", 11, 0.08)
        val (hidden, prediction) = forward(features, w1, w2)
        val lr = 0.003
        val error = y - prediction
        val outputDelta = error * prediction * (1.0 - prediction)
        val oldW2 = w2.copyOf()
        w2[0] = (w2[0] + lr * error).coerceIn(-2.0, 2.0)
        for (h in 0 until 10) {
            val hiddenDelta = outputDelta * oldW2[h + 1] * (1.0 - hidden[h] * hidden[h])
            w2[h + 1] = (w2[h + 1] + lr * error * hidden[h]).coerceIn(-2.0, 2.0)
            for (i in features.indices) {
                val idx = h * features.size + i
                w1[idx] = (w1[idx] + lr * hiddenDelta * features[i]).coerceIn(-2.0, 2.0)
            }
        }
        saveWeights(b, "mlpV2W1", w1)
        saveWeights(b, "mlpV2W2", w2)
        b.put("learned", b.optInt("learned") + 1).put("modelVersion", 2)
    }

    private fun fetchLivePrice(symbol: String): Double? {
        val conn = URL("https://www.okx.com/api/v5/market/ticker?instId=$symbol").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optString("code") != "0") return null
            root.optJSONArray("data")?.optJSONObject(0)?.optString("last")?.toDoubleOrNull()
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchCandles(symbol: String, bar: String = "1H"): List<JSONArray>? {
        // Bybit first (primary live source), Binance Futures second, OKX as final fallback.
        val interval = when (bar) { "1m" -> "1"; "5m" -> "5"; "15m" -> "15"; "1H" -> "60"; "4H" -> "240"; "1D" -> "D"; else -> "60" }
        try {
            val url = URL("https://api.bybit.com/v5/market/kline?category=linear&symbol=" + symbol.replace("-", "") + "&interval=" + interval + "&limit=220")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout=2500; readTimeout=2500; requestMethod="GET" }
            try {
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = root.optJSONObject("result")?.optJSONArray("list")
                if (root.optInt("retCode", -1) == 0 && arr != null && arr.length() > 0) return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.reversed()
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
        try {
            val binanceInterval = when (bar) { "1H" -> "1h"; "4H" -> "4h"; "1D" -> "1d"; else -> bar }
            val url = URL("https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol.replace("-", "") + "&interval=" + binanceInterval + "&limit=220")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout=2500; readTimeout=2500; requestMethod="GET" }
            try { val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() }); if (arr.length() > 0) return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) } } finally { conn.disconnect() }
        } catch (_: Exception) {}
        try {
            val url = URL("https://www.okx.com/api/v5/market/candles?instId=$symbol&bar=$bar&limit=220")
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout=2500; readTimeout=2500; requestMethod="GET" }
            try { val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }); if (root.optString("code") != "0") return null; val arr = root.optJSONArray("data") ?: return null; return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.reversed() } finally { conn.disconnect() }
        } catch (_: Exception) { return null }
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
