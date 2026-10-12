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
import kotlin.math.sqrt

data class CandleSnapshot(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

private data class CooperativeDecision(val direction: String, val detail: String, val expertFeatures: List<DoubleArray>)

data class BotSnapshot(
    val symbol: String, val balance: Double, val price: Double, val pnl: Double,
    val position: String, val entry: Double, val trades: Int, val wins: Int,
    val status: String, val leverage: Int, val risk: Double, val learned: Int,
    val equity: Double = balance
)

object NativeTradingEngine {
    // Conservative assumptions for a simulated taker order; verify the account's actual fee tier.
    private const val TAKER_FEE_RATE = 0.0005       // Simulated taker fee assumption: 0.05% per side
    private const val MAKER_FEE_RATE = 0.0002       // Reference-only maker fee assumption: 0.02% per side
    private const val SLIPPAGE_RATE = 0.0002        // Modelled adverse fill, not a commission
    private const val FUNDING_RATE_PER_8H = 0.0001  // assumed 0.01% per 8h; actual funding varies
    private const val MIN_REWARD_TO_COST = 2.0
    private const val MIN_LEARNED_TRADES = 20
    private const val MIN_PREDICTED_WIN = 0.53
    private const val HOUR_MS = 3_600_000L

    private val legacySymbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "DOGE-USDT", "BNB-USDT", "LINK-USDT", "ADA-USDT", "AVAX-USDT", "SUI-USDT")
    private val symbols = listOf("BTC-USDT")
    private val lock = Any()
    private val bots = linkedMapOf<String, JSONObject>()
    private val candleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartCandleCache = linkedMapOf<String, List<CandleSnapshot>>()
    private val chartExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val ensembleExecutor = java.util.concurrent.Executors.newFixedThreadPool(5)
    private val chartRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val quoteClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var bybitSocket: WebSocket? = null
    private val bybitQuotes = linkedMapOf<String, Double>()
    private val bybitQuoteAt = linkedMapOf<String, Long>()
    private var bybitConnectedAt = 0L
    private var quoteSocketStarted = false
    private lateinit var app: Context
    private var lastError = ""
    private var initialized = false
    private var backupTreeUri: Uri? = null
    private val levOptions = listOf(1,2,3,5,10,20,50,75,100)

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return
        app = context.applicationContext
        backupTreeUri = app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).getString("tree_uri", null)?.let { Uri.parse(it) }
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
                    if (sym in legacySymbols) bots[sym] = b
                }
            } catch (_: Exception) { lastError = "Estado guardado dañado; se inicia recuperación" }
        }
        symbols.forEach { sym ->
            if (!bots.containsKey(sym)) bots[sym] = JSONObject()
                .put("symbol", sym).put("balance", 100.0).put("initial", 100.0)
                .put("price", 0.0).put("entry", 0.0).put("qty", 0.0).put("side", "FLAT")
                .put("stop", 0.0).put("target", 0.0).put("trades", 0).put("wins", 0)
                .put("losses", 0).put("learned", 0).put("risk", 5.0).put("leverage", 100).put("enabled", sym != "SUI-USDT")
                .put("injectedCapital", 0.0).put("recharges", 0).put("learningSamples", JSONArray()).put("lastBatchTrained", 0)
                .put("lastCandle", 0L).put("botBar", "1H").put("nnBias", 0.0).put("nnTrend", 0.0).put("nnMomentum", 0.0)
                .put("lastSignal", "Analizando mercado").put("history", JSONArray())
        }
        // Keep model state and trade ledger intact across code/version upgrades.
        saveLocked()
        initialized = true
        startQuoteStreamsLocked()
        NewsIntelligence.init(app)
    }

    fun isBotEnabled(symbol: String): Boolean = synchronized(lock) { bots[symbol]?.optBoolean("enabled", symbol != "SUI-USDT") ?: false }
    fun isRunning(): Boolean = synchronized(lock) {
        bots["BTC-USDT"]?.optBoolean("enabled", true) == true
    }

    fun resumeAll() = synchronized(lock) {
        val btc = bots["BTC-USDT"] ?: return@synchronized
        btc.put("enabled", true)
        btc.put("lastSignal", "Reanudado · motor cooperativo BTC")
        saveLocked()
    }
    fun setBotEnabled(symbol: String, enabled: Boolean) = synchronized(lock) {
        bots[symbol]?.put("enabled", enabled)
        saveLocked()
    }
    fun enabledSymbols(): List<String> = synchronized(lock) { symbols.filter { bots[it]?.optBoolean("enabled", it != "SUI-USDT") == true } }
    private fun sharedCapitalLocked(): Double = bots["BTC-USDT"]?.optDouble("balance", 100.0) ?: 100.0
    fun setRisk(symbol: String, risk: Double) = synchronized(lock) {
        bots[symbol]?.put("risk", risk.coerceIn(1.0, 100.0)); saveLocked()
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
            val balance = b.optDouble("balance",100.0)
            BotSnapshot(s, balance, p, balance-b.optDouble("initial",100.0)-b.optDouble("injectedCapital",0.0)+floating,
                side, entry, b.optInt("trades"), b.optInt("wins"), b.optString("lastSignal"),
                b.optInt("leverage",100), b.optDouble("risk",5.0), b.optInt("learned"),
                balance + floating)
        }
    }
    fun candles(symbol: String): List<CandleSnapshot> = synchronized(lock) { candleCache[symbol]?.toList() ?: emptyList() }

    fun jointOperationState(): JSONObject = synchronized(lock) {
        val b = bots["BTC-USDT"] ?: JSONObject()
        val price = b.optDouble("price", 0.0)
        val entry = b.optDouble("entry", 0.0)
        val qty = b.optDouble("qty", 0.0)
        val side = b.optString("side", "FLAT")
        val floating = if (side == "LONG") (price - entry) * qty else if (side == "SHORT") (entry - price) * qty else 0.0
        JSONObject().put("symbol", "BTC-USDT")
            .put("enabled", b.optBoolean("enabled", true)).put("side", side)
            .put("price", price).put("entry", entry).put("qty", qty)
            .put("stop", b.optDouble("stop", 0.0)).put("target", b.optDouble("target", 0.0))
            .put("trailingActive", b.optBoolean("trailingActive", false)).put("timeframe", b.optString("botBar", "1H"))
            .put("balance", b.optDouble("balance", 100.0)).put("floatingPnl", floating)
            .put("entryPredictedWin", b.optDouble("entryPredictedWin", 0.5))
            .put("lastSignal", b.optString("lastSignal", "Esperando análisis"))
            .put("expertDirection", b.optString("expertDirection", "MIXTO"))
            .put("expertConsensusDetail", b.optString("expertConsensusDetail", "Esperando datos de temporalidades"))
    }

    fun jointOperationActivity(): List<JSONObject> = synchronized(lock) {
        val arr = bots["BTC-USDT"]?.optJSONArray("expertActivity") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { JSONObject(it.toString()) } }
            .sortedByDescending { it.optLong("time", 0L) }
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

    // Bybit linear futures is the primary quote feed; OKX REST is the only fallback.
    fun liveQuoteSource(): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        when {
            bybitQuoteAt["BTC-USDT"]?.let { now - it < 15_000L } == true -> "Bybit Futures · principal"
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
    fun estimateNetIfClosedNow(): Double = synchronized(lock) {
        val now = System.currentTimeMillis()
        symbols.sumOf { symbol ->
            val b = bots[symbol] ?: return@sumOf 0.0
            val side = b.optString("side", "FLAT")
            val entry = b.optDouble("entry", 0.0); val mark = b.optDouble("price", 0.0); val qty = b.optDouble("qty", 0.0)
            if (side == "FLAT" || entry <= 0.0 || mark <= 0.0 || qty <= 0.0) return@sumOf 0.0
            val exit = mark * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
            val gross = (if (side == "LONG") exit - entry else entry - exit) * qty
            val fees = (entry * qty + exit * qty) * TAKER_FEE_RATE
            val heldMs = max(0L, now - b.optLong("entryTime", now))
            val funding = entry * qty * FUNDING_RATE_PER_8H * (heldMs / 28_800_000.0)
            gross - fees - funding
        }
    }
    fun stopAllAndCloseNow(): Double = synchronized(lock) {
        val now = System.currentTimeMillis()
        var totalNet = 0.0
        symbols.forEach { symbol ->
            val b = bots[symbol] ?: return@forEach
            b.put("enabled", false)
            val side = b.optString("side", "FLAT")
            val entry = b.optDouble("entry", 0.0)
            val mark = b.optDouble("price", 0.0)
            val qty = b.optDouble("qty", 0.0)
            if (side == "FLAT" || entry <= 0.0 || mark <= 0.0 || qty <= 0.0) return@forEach
            val exit = mark * (if (side == "LONG") 1.0 - SLIPPAGE_RATE else 1.0 + SLIPPAGE_RATE)
            val gross = (if (side == "LONG") exit - entry else entry - exit) * qty
            val fees = (entry * qty + exit * qty) * TAKER_FEE_RATE
            val heldMs = max(0L, now - b.optLong("entryTime", now))
            val funding = entry * qty * FUNDING_RATE_PER_8H * (heldMs / 28_800_000.0)
            val net = gross - fees - funding
            recordExpertActivityLocked(b, "CIERRE MANUAL", "Parada del usuario · neto estimado " + String.format(Locale.US, "%+.2f", net) + " USDT", exit, net)
            val before = b.optDouble("balance", 100.0)
            val after = max(0.0, before + net)
            b.put("balance", after)
            val history = b.optJSONArray("history") ?: JSONArray()
            history.put(JSONObject().put("type","TRADE").put("time",now).put("entryTime",b.optLong("entryTime",now)).put("side",side).put("entry",entry).put("exit",exit).put("qty",qty).put("gross",gross).put("fees",fees).put("funding",funding).put("pnl",net).put("balanceBefore",before).put("balanceAfter",after).put("reason","Parada de emergencia").put("strategy",b.optString("entryStrategy","")).put("timeframe",b.optString("botBar","5m")).put("durationMs",heldMs))
            b.put("history",history).put("side","FLAT").put("entry",0.0).put("qty",0.0).put("stop",0.0).put("target",0.0).put("entryFeatures",JSONArray()).put("entryExpertFeatures",JSONArray()).put("trailingActive", false).put("trades",b.optInt("trades",0)+1)
            if (net > 0) b.put("wins",b.optInt("wins",0)+1) else b.put("losses",b.optInt("losses",0)+1)
            totalNet += net
        }
        saveLocked()
        totalNet
    }
    fun totalPnl() = snapshots().sumOf { it.pnl }
    fun lastError(): String = synchronized(lock) { lastError }
    fun storagePath(): String = synchronized(lock) { File(app.filesDir, "CryptoFutures").absolutePath }

    fun setBackupFolder(uri: Uri): String = synchronized(lock) {
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, flags)
            backupTreeUri = uri
            app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).putBoolean("asked", true).apply()
            // Critical: archive and restore the old backup BEFORE any save can overwrite it.
            archiveCurrentExternalBackupLocked()
            val restored = restoreUserBackup()
            if (restored.startsWith("Restauración completada")) {
                "Respaldo antiguo restaurado automáticamente. " + restored
            } else {
                saveLocked()
                "Carpeta configurada. No se encontró un respaldo válido; se creó uno nuevo. " + restored
            }
        } catch (e: Exception) { "No se pudo acceder a la carpeta: " + (e.message ?: "permiso denegado") }
    }
    fun backupFolderLabel(): String = if (backupTreeUri == null) "No seleccionada · se solicitará acceso" else "Configurada · respaldo automático"
    fun shouldRequestBackupFolder(): Boolean = synchronized(lock) {
        backupTreeUri == null && !app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).getBoolean("prompted", false)
    }
    fun markBackupPrompted() {
        app.getSharedPreferences("backup_settings", Context.MODE_PRIVATE).edit().putBoolean("prompted", true).apply()
    }

    private fun archiveCurrentExternalBackupLocked() {
        val tree = backupTreeUri ?: return
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
            val cf = find(rootId, "CryptoFutures") ?: return
            val backups = find(cf, "backups") ?: return
            val latest = find(backups, "state-v1.json") ?: return
            val latestUri = DocumentsContract.buildDocumentUriUsingTree(tree, latest)
            val raw = resolver.openInputStream(latestUri)?.bufferedReader()?.use { it.readText() } ?: return
            val stamp = JSONObject(raw).optLong("savedAt", System.currentTimeMillis())
            val archiveDirId = find(backups, "archive") ?: run {
                val dir = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, backups), DocumentsContract.Document.MIME_TYPE_DIR, "archive") ?: return
                DocumentsContract.getDocumentId(dir)
            }
            val name = "backup-$stamp.json"
            if (find(archiveDirId, name) == null) {
                val out = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, archiveDirId), "application/json", name) ?: return
                resolver.openOutputStream(out, "wt")?.bufferedWriter()?.use { it.write(raw) }
            }
        } catch (e: Exception) { lastError = "No se pudo archivar respaldo previo: " + (e.message ?: "error") }
    }

    fun restoreUserBackup(): String = synchronized(lock) {
        val tree = backupTreeUri ?: return@synchronized "Selecciona primero la carpeta que contiene el respaldo."
        try {
            val resolver = app.contentResolver
            fun find(parentId: String, name: String): String? {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
                }
                return null
            }
            val cf = find(DocumentsContract.getTreeDocumentId(tree), "CryptoFutures") ?: return@synchronized "No encuentro CryptoFutures."
            val backups = find(cf, "backups") ?: return@synchronized "No encuentro la carpeta backups."
            val file = find(backups, "state-v1.json") ?: return@synchronized "No hay archivo state-v1.json."
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file)
            val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@synchronized "No se pudo leer el archivo."
            val arr = JSONObject(raw).optJSONArray("bots") ?: return@synchronized "El respaldo no es válido."
            var count = 0
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val symbol = b.optString("symbol")
                if (symbol in symbols) { bots[symbol] = b; count++ }
            }
            if (count == 0) return@synchronized "No se encontraron bots compatibles."
            saveLocked()
            "Restauración completada: $count bots recuperados."
        } catch (e: Exception) { "No se pudo restaurar: " + (e.message ?: "error de lectura") }
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
            val cf = child(DocumentsContract.getTreeDocumentId(tree), "CryptoFutures", DocumentsContract.Document.MIME_TYPE_DIR) ?: return
            val backups = child(cf, "backups", DocumentsContract.Document.MIME_TYPE_DIR) ?: return
            val file = child(backups, "state-v1.json", "application/json") ?: return
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file)
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(root.toString()) }
        } catch (e: Exception) { lastError = "Respaldo externo: " + (e.message ?: "fallo de escritura") }
    }

    fun tickAll() {
        if (!initialized) return
        NewsIntelligence.pollIfDue()
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
            if (now - (bybitQuoteAt[symbol] ?: 0L) < 15_000L) bybitQuotes[symbol] else null
        }
        val streamUnavailable = synchronized(lock) {
            val now = System.currentTimeMillis()
            System.currentTimeMillis() - (bybitQuoteAt[symbol] ?: 0L) >= 15_000L
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
        val cooperative = cooperativeDecision(symbol, chosenBar, candles)
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
            val baseSignal = strategySignal(symbol, candles, closes, highs, lows, ema9, ema21, ema50, ema200, rsi, atr, regime, returns)
            // Prefer the proven v0.5 signal. If it is neutral, allow a strong 10-agent consensus to propose a shared BTC entry.
            val signal = when {
                baseSignal == "LONG" || baseSignal == "SHORT" -> baseSignal
                cooperative.direction == "LONG" || cooperative.direction == "SHORT" -> cooperative.direction
                else -> "FLAT"
            }
            b.put("strategy", strategy)
            val news = NewsIntelligence.summary()
            val newsLabel = if (news.articles == 0) "RSS pendiente" else "RSS ${if (news.sentiment > 0.15) "positivo" else if (news.sentiment < -0.15) "negativo" else "neutro"} ${news.articles}"
            b.put("newsSentiment", news.sentiment).put("newsUpdatedAt", news.updatedAt)
            // Observation only: RSS context never changes the BTC entry signal.
            val previousExpertDetail = b.optString("expertConsensusDetail", "")
            b.put("expertDirection", cooperative.direction).put("expertConsensusDetail", cooperative.detail)
            if (cooperative.detail != previousExpertDetail) recordExpertActivityLocked(b, "VOTOS", cooperative.detail, close)
            val newStatus = "$strategy · base v0.5=$baseSignal · señal conjunta=$signal · expertos ${cooperative.direction} · $regime · RSI ${rsi.toInt()} · $newsLabel"
            val oldStatus = b.optString("lastSignal", "")
            b.put("lastSignal", newStatus)
            if (newStatus != oldStatus) recordExpertActivityLocked(b, "ANÁLISIS", "Base=$signal · consenso=${cooperative.direction} · $regime · RSI ${rsi.toInt()}", close)
            val newCandle = prevCandleTime > b.optLong("lastCandle", 0L)
            if (newCandle) b.put("lastCandle", prevCandleTime)
            val adaptiveBar = chooseBotTimeframe(closes, highs, lows, ema9, ema21, ema50, ema200, atr, close)
            if (adaptiveBar != chosenBar) {
                b.put("botBar", adaptiveBar).put("lastCandle", 0L)
                b.put("lastSignal", strategy + " · temporalidad adaptativa " + chosenBar + " → " + adaptiveBar)
            }
            if (side != "FLAT" && entry > 0) {
                val markNow = b.optDouble("price", close).takeIf { it > 0.0 } ?: close
                val qtyNow = b.optDouble("qty", 0.0)
                if (qtyNow > 0.0) {
                    val oldFavorable = b.optDouble("maxFavorablePrice", entry).takeIf { it > 0.0 } ?: entry
                    val oldAdverse = b.optDouble("maxAdversePrice", entry).takeIf { it > 0.0 } ?: entry
                    val favorable = if (side == "LONG") max(oldFavorable, markNow) else min(oldFavorable, markNow)
                    val adverse = if (side == "LONG") min(oldAdverse, markNow) else max(oldAdverse, markNow)
                    b.put("maxFavorablePrice", favorable).put("maxAdversePrice", adverse)
                    val heldHours = max(0L, System.currentTimeMillis() - b.optLong("entryTime", System.currentTimeMillis())) / 3_600_000.0
                    val estimatedCosts = (entry + markNow) * qtyNow * (TAKER_FEE_RATE + SLIPPAGE_RATE) +
                        entry * qtyNow * FUNDING_RATE_PER_8H * (heldHours / 8.0)
                    val unrealizedGross = (if (side == "LONG") markNow - entry else entry - markNow) * qtyNow
                    if (unrealizedGross > estimatedCosts + atr * qtyNow * 0.35) {
                        val multiplier = b.optDouble("exitTrailAtr", 1.8).coerceIn(1.5, 2.2)
                        val distance = max(atr * multiplier, entry * 0.001)
                        val costPerUnit = estimatedCosts / qtyNow
                        val costProtectedStop = if (side == "LONG") entry + costPerUnit else entry - costPerUnit
                        val candidate = if (side == "LONG") max(costProtectedStop, markNow - distance)
                            else min(costProtectedStop, markNow + distance)
                        val priorStop = b.optDouble("stop", if (side == "LONG") entry - atr * 1.4 else entry + atr * 1.4)
                        val tightened = if (side == "LONG") max(priorStop, candidate) else min(priorStop, candidate)
                        if ((side == "LONG" && tightened > priorStop) || (side == "SHORT" && tightened < priorStop)) {
                            b.put("stop", tightened).put("trailingActive", true)
                            recordExpertActivityLocked(b, "PROTECCIÓN", "Stop dinámico avanzado a " + String.format(Locale.US, "%.2f", tightened) + " · trailing activo", markNow)
                        }
                    }
                }
                val stop = b.optDouble("stop")
                val target = b.optDouble("target")
                val candleHigh = highs.last()
                val candleLow = lows.last()
                // Ignore the pre-entry part of this hourly candle to avoid look-ahead exits.
                val afterEntryCandle = prevCandleTime > b.optLong("entryCandle", prevCandleTime)
                // A live quote crossing the stop can close the simulated position immediately;
                // candle extrema remain a fallback for gaps/missed quote updates.
                val liveStopHit = if (side == "LONG") markNow <= stop else markNow >= stop
                val stopHit = liveStopHit || (afterEntryCandle && (if (side == "LONG") candleLow <= stop else candleHigh >= stop))
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
                    val balanceBefore = b.optDouble("balance")
                    val portfolioBefore = sharedCapitalLocked()
                    val balanceAfter = max(0.0, balanceBefore + net)
                    b.put("balance", balanceAfter)
                    if (net > 0) b.put("wins", b.optInt("wins")+1) else b.put("losses", b.optInt("losses")+1)
                    val entryFeaturesJson = b.optJSONArray("entryFeatures") ?: JSONArray()
                    val entryFeatures = DoubleArray(entryFeaturesJson.length()) { entryFeaturesJson.optDouble(it) }
                    if (entryFeatures.size == 12) {
                        val entryExpertJson = b.optJSONArray("entryExpertFeatures") ?: JSONArray()
                        val expertFs = (0 until 10).map { expert ->
                            val arr = entryExpertJson.optJSONArray(expert)
                            if (arr != null && arr.length() == 12) DoubleArray(12) { arr.optDouble(it) } else entryFeatures
                        }
                        learnExperts(b, net > 0, expertFs)
                        val samples = b.optJSONArray("learningSamples") ?: JSONArray()
                        samples.put(JSONObject().put("won", net > 0).put("pnl", net).put("time", System.currentTimeMillis())
                            .put("features", entryFeaturesJson).put("expertFeatures", entryExpertJson).put("strategy", b.optString("entryStrategy", strategy)))
                        b.put("learningSamples", samples)
                        val sampleCount = samples.length()
                        if (sampleCount >= 10 && sampleCount / 10 > b.optInt("lastBatchTrained", 0)) {
                            val learnedBefore = b.optInt("learned", 0)
                            val start = max(0, sampleCount - 50)
                            repeat(2) {
                                for (i in start until sampleCount) {
                                    val s = samples.optJSONObject(i) ?: continue
                                    val fs = s.optJSONArray("features") ?: continue
                                    if (fs.length() != 12) continue
                                    val storedExpertFeatures = s.optJSONArray("expertFeatures") ?: JSONArray()
                                    val replayFeatures = DoubleArray(12) { fs.optDouble(it) }
                                    val replayExpertFs = (0 until 10).map { expert ->
                                        val expertArray = storedExpertFeatures.optJSONArray(expert)
                                        if (expertArray != null && expertArray.length() == 12) DoubleArray(12) { expertArray.optDouble(it) } else replayFeatures
                                    }
                                    learnExperts(b, s.optBoolean("won"), replayExpertFs)
                                }
                            }
                            b.put("learned", learnedBefore).put("lastBatchTrained", sampleCount / 10)
                        }
                    }
                    val maxFavorablePrice = b.optDouble("maxFavorablePrice", entry)
                    val maxAdversePrice = b.optDouble("maxAdversePrice", entry)
                    val maxFavorablePnl = (if (side == "LONG") maxFavorablePrice - entry else entry - maxFavorablePrice) * qty
                    val maxAdversePnl = min(0.0, (if (side == "LONG") maxAdversePrice - entry else entry - maxAdversePrice) * qty)
                    val trailWasActive = b.optBoolean("trailingActive", false)
                    val oldTrailAtr = b.optDouble("exitTrailAtr", 1.8)
                    val updatedTrailAtr = when {
                        trailWasActive && net > 0.0 && maxFavorablePnl > 0.0 && net / maxFavorablePnl < 0.55 -> oldTrailAtr - 0.08
                        trailWasActive && net <= 0.0 && maxFavorablePnl > fee -> oldTrailAtr - 0.05
                        trailWasActive && net > 0.0 && maxFavorablePnl > 0.0 && net / maxFavorablePnl > 0.80 -> oldTrailAtr + 0.04
                        else -> oldTrailAtr
                    }.coerceIn(1.5, 2.2)
                    b.put("exitTrailAtr", updatedTrailAtr)
                    recordExpertActivityLocked(b, "CIERRE", "Cierre $side · " + (if (stopHit) "stop" else if (targetHit) "objetivo" else "señal contraria") + " · neto " + String.format(Locale.US, "%+.2f", net) + " USDT", exitPrice, net)
                    val h = b.optJSONArray("history") ?: JSONArray()
                    val entryTime = b.optLong("entryTime", System.currentTimeMillis())
                    h.put(JSONObject()
                        .put("type", "TRADE").put("time", System.currentTimeMillis()).put("entryTime", entryTime)
                        .put("side", side).put("entry", entry).put("exit", exitPrice).put("qty", qty)
                        .put("entryNotional", entryNotional).put("gross", gross).put("fees", fee)
                        .put("funding", funding).put("pnl", net).put("balanceBefore", balanceBefore)
                        .put("balanceAfter", balanceAfter).put("portfolioBefore", portfolioBefore).put("portfolioAfter", portfolioBefore + net).put("leverage", b.optInt("leverage", 1))
                        .put("riskPercent", b.optDouble("risk", 5.0)).put("stop", stop).put("target", target)
                        .put("strategy", b.optString("entryStrategy", strategy)).put("timeframe", chosenBar)
                        .put("predictedWin", b.optDouble("entryPredictedWin", 0.5))
                        .put("durationMs", max(0L, System.currentTimeMillis() - entryTime))
                        .put("reason", if(stopHit) "Stop ATR" else if(targetHit) "Objetivo ATR" else "Señal contraria")
                        .put("modelSamples", b.optInt("learned", 0)).put("maxFavorablePnl", maxFavorablePnl).put("maxAdversePnl", maxAdversePnl).put("trailingActive", trailWasActive).put("exitTrailAtrAfter", updatedTrailAtr))
                    b.put("history",h).put("side","FLAT").put("entry",0.0).put("qty",0.0).put("stop",0.0).put("target",0.0)
                        .put("entryFeatures", JSONArray()).put("entryExpertFeatures", JSONArray()).put("trailingActive", false).put("trades",b.optInt("trades")+1)
                    if (balanceAfter <= 0.000001) {
                        val refill = max(1.0, b.optDouble("initial", 100.0))
                        b.put("balance", refill).put("injectedCapital", b.optDouble("injectedCapital", 0.0) + refill)
                            .put("recharges", b.optInt("recharges", 0) + 1)
                        h.put(JSONObject().put("type", "RECHARGE").put("time", System.currentTimeMillis())
                            .put("amount", refill).put("reason", "Recarga automática de capital simulado para continuar el entrenamiento")
                            .put("balanceAfter", refill).put("pnl", 0.0))
                    }
                }
            } else if (side == "FLAT" && b.optBoolean("enabled", symbol != "SUI-USDT") && signal != "FLAT" && b.optDouble("balance") > 0.5 && b.optLong("lastEntryCandle", -1L) != prevCandleTime) {
                // Parallel positions use a separate share of the common portfolio per enabled bot.
                val allocatedCapital = sharedCapitalLocked()
                val riskCash = allocatedCapital * b.optDouble("risk",5.0)/100.0
                val stopDistance = max(atr*1.8, close*0.001)
                val targetDistance = atr*2.7
                val estimatedRoundTripCostRate = 2.0 * (TAKER_FEE_RATE + SLIPPAGE_RATE) + FUNDING_RATE_PER_8H
                val expectedMoveRate = targetDistance / close
                val predictedWin = predictedWinProbability(b, features, cooperative.expertFeatures)
                val sampleCount = b.optInt("learned", 0)
                // Avoid trades whose target cannot reasonably cover fees/slippage/funding.
                // Once enough closes exist, the on-device online learner can veto weak setups.
                val costsCovered = expectedMoveRate >= estimatedRoundTripCostRate * MIN_REWARD_TO_COST
                val learnerAllows = sampleCount < MIN_LEARNED_TRADES || predictedWin >= MIN_PREDICTED_WIN
                val maxNotional = allocatedCapital * b.optInt("leverage",100)
                val qty = min(riskCash / stopDistance, maxNotional / close)
                if (costsCovered && learnerAllows && qty > 0 && qty.isFinite()) {
                    val executedEntry = close * (if (signal == "LONG") 1.0 + SLIPPAGE_RATE else 1.0 - SLIPPAGE_RATE)
                    val stop = if(signal=="LONG") executedEntry-stopDistance else executedEntry+stopDistance
                    val target = if(signal=="LONG") executedEntry+targetDistance else executedEntry-targetDistance
                    b.put("side",signal).put("entry",executedEntry).put("qty",qty).put("stop",stop).put("target",target)
                        .put("lastEntryCandle",prevCandleTime).put("entryCandle",prevCandleTime)
                        .put("entryTime", System.currentTimeMillis()).put("entryStrategy", strategy)
                        .put("maxFavorablePrice", executedEntry).put("maxAdversePrice", executedEntry)
                        .put("trailingActive", false).put("entryAtr", atr)
                        .put("entryPredictedWin", predictedWin)
                        .put("entryFeatures", JSONArray().also { arr -> features.forEach { arr.put(it) } })
                        .put("entryExpertFeatures", JSONArray().also { outer -> cooperative.expertFeatures.forEach { fs -> outer.put(JSONArray().also { inner -> fs.forEach { inner.put(it) } }) } })
                    recordExpertActivityLocked(b, "APERTURA", "Entrada $signal · estrategia base v0.5 · diez expertos comparten una sola posición; consenso=${cooperative.direction}", executedEntry)
                } else {
                    b.put("lastSignal", "$strategy · Señal base $signal, entrada filtrada por costes/IA (${(predictedWin*100).toInt()}%)"); recordExpertActivityLocked(b, "FILTRO", "Señal base $signal no ejecutada: costesCubiertos=$costsCovered · probabilidad estimada=${(predictedWin*100).toInt()}%", close)
                }
            }
        }
    }

    /**
     * Ten cooperative agents: two independently score each timeframe (1m, 5m, 15m, 1H, 4H).
     * They share the same BTC position and learn from its single realized outcome.
     * A direction is accepted only when at least 6/10 votes agree and it matches the
     * unchanged legacy strategy's signal on the bot's active timeframe.
     */
    /**
     * Ten distinct cooperative roles: structure and momentum on five BTC timeframes.
     * The base v0.5 signal controls entries; experts provide collaboration, telemetry and learning.
     */
    private fun cooperativeDecision(symbol: String, primaryBar: String, primaryCandles: List<JSONArray>): CooperativeDecision {
        val bars = listOf("1m", "5m", "15m", "1H", "4H")
        val futures = bars.associateWith { bar ->
            if (bar == primaryBar) null else ensembleExecutor.submit<List<JSONArray>?> { fetchCandles(symbol, bar) }
        }
        val signals = mutableListOf<String>()
        val details = mutableListOf<String>()
        val featureSets = mutableListOf<DoubleArray>()
        var loadedLongs = 0
        var loadedShorts = 0
        for (bar in bars) {
            val candles = if (bar == primaryBar) primaryCandles else try {
                futures[bar]?.get(3200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) { null }
            if (candles == null || candles.size < 60) {
                signals.add("FLAT"); signals.add("FLAT")
                details.add("$bar: sin datos")
                featureSets.add(DoubleArray(12)); featureSets.add(DoubleArray(12))
                continue
            }
            val closes = candles.map { it.optDouble(4) }
            val highs = candles.map { it.optDouble(2) }
            val lows = candles.map { it.optDouble(3) }
            val close = closes.lastOrNull() ?: 0.0
            if (close <= 0.0) {
                signals.add("FLAT"); signals.add("FLAT")
                details.add("$bar: precio inválido")
                featureSets.add(DoubleArray(12)); featureSets.add(DoubleArray(12))
                continue
            }
            val ema9 = ema(closes, 9); val ema21 = ema(closes, 21)
            val ema50 = ema(closes, 50); val ema200 = ema(closes, min(200, closes.size))
            val rsiValue = rsi(closes, 14)
            val atrValue = atr(highs, lows, closes, 14)
            val returns3 = returnOver(closes, 3)
            val returns12 = returnOver(closes, 12)
            val returns48 = returnOver(closes, 48)
            val regimeValue = if (abs(ema50 - ema200) / close > 0.004) "TENDENCIA" else "RANGO"
            val structure = strategySignal(symbol, candles, closes, highs, lows, ema9, ema21, ema50, ema200,
                rsiValue, atrValue, regimeValue, returns12)
            val momentum = when {
                ema9 > ema21 && ema21 > ema50 && returns3 > 0.0005 && returns12 > 0.001 && rsiValue in 50.0..80.0 -> "LONG"
                ema9 < ema21 && ema21 < ema50 && returns3 < -0.0005 && returns12 < -0.001 && rsiValue in 20.0..50.0 -> "SHORT"
                else -> "FLAT"
            }
            val volumes = candles.map { it.optDouble(5) }
            val volNow = volumes.lastOrNull() ?: 0.0
            val volAvg = volumes.takeLast(21).dropLast(1).average().takeIf { it > 0.0 } ?: volNow
            val volumeRatio = if (volAvg > 0.0) volNow / volAvg else 1.0
            val alignment = ((if (ema9 > ema21) 1.0 else -1.0) +
                (if (ema21 > ema50) 1.0 else -1.0) +
                (if (ema50 > ema200) 1.0 else -1.0)) / 3.0
            val lastRange = max(1e-12, highs.last() - lows.last())
            val body = ((closes.last() - candles.last().optDouble(1)) / lastRange).coerceIn(-1.0, 1.0)
            val features = modelFeatures(((ema9 - ema21) / close * 100.0).coerceIn(-3.0, 3.0),
                returns3 * 100.0, returns12 * 100.0, returns48 * 100.0,
                atrValue / close, volumeRatio, ((rsiValue - 50.0) / 50.0).coerceIn(-1.0, 1.0),
                alignment, directionalEfficiency(closes, 12), body,
                ((ema50 - ema200) / close * 100.0).coerceIn(-3.0, 3.0))
            signals.add(structure); signals.add(momentum)
            featureSets.add(features); featureSets.add(features.copyOf())
            if (structure == "LONG") loadedLongs++ else if (structure == "SHORT") loadedShorts++
            if (momentum == "LONG") loadedLongs++ else if (momentum == "SHORT") loadedShorts++
            details.add("$bar: estructura=$structure · impulso=$momentum")
        }
        val chosen = when {
            loadedLongs >= 6 && loadedLongs > loadedShorts -> "LONG"
            loadedShorts >= 6 && loadedShorts > loadedLongs -> "SHORT"
            else -> "MIXTO"
        }
        return CooperativeDecision(chosen, details.joinToString(" | ") + " | votos LONG=$loadedLongs SHORT=$loadedShorts",
            featureSets.take(10).map { if (it.size == 12) it else DoubleArray(12) })
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
    // Estrategia de scalping inspirada en el vídeo: estructura/fractalidad, impulso-pullback-continuación,
    // giros por doble techo/suelo confirmado y filtro para no operar mercados sin estructura.
    fun strategyName(symbol: String): String = when (symbol) {
        "AVAX-USDT" -> "Tendencia con filtro ATR · preservada"
        "SUI-USDT" -> "Desactivado por pérdidas acumuladas"
        else -> "Scalping de estructura · continuación y giro"
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

    private fun predictedWinProbability(b: JSONObject, features: DoubleArray, expertFeatures: List<DoubleArray>): Double {
        var total = 0.0
        for (expert in 0 until 10) {
            val ownFeatures = expertFeatures.getOrNull(expert)?.takeIf { it.size == 12 } ?: features
            val suffix = if (expert == 0) "" else "Expert$expert"
            val w1 = modelWeights(b, "mlpV2W1$suffix", 120, 0.08)
            val w2 = modelWeights(b, "mlpV2W2$suffix", 11, 0.08)
            total += forward(ownFeatures, w1, w2).second
        }
        return (total / 10.0).coerceIn(0.0, 1.0)
    }

    private fun learn(b: JSONObject, won: Boolean, features: DoubleArray) {
        learnExperts(b, won, List(10) { features })
    }

    private fun learnExperts(b: JSONObject, won: Boolean, featuresByExpert: List<DoubleArray>) {
        val y = if (won) 1.0 else 0.0
        for (expert in 0 until 10) {
            val features = featuresByExpert.getOrNull(expert)?.takeIf { it.size == 12 } ?: DoubleArray(12)
            val suffix = if (expert == 0) "" else "Expert$expert"
            val w1 = modelWeights(b, "mlpV2W1$suffix", 120, 0.08)
            val w2 = modelWeights(b, "mlpV2W2$suffix", 11, 0.08)
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
            saveWeights(b, "mlpV2W1$suffix", w1)
            saveWeights(b, "mlpV2W2$suffix", w2)
        }
        b.put("learned", b.optInt("learned") + 1).put("modelVersion", 3).put("ensembleExperts", 10)
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
        // Bybit first; OKX REST is the only fallback.
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
    private fun floatingPnl(b: JSONObject): Double {
        val entry=b.optDouble("entry",0.0); val price=b.optDouble("price",0.0)
        val side=b.optString("side","FLAT"); val qty=b.optDouble("qty",0.0)
        return if(entry>0 && price>0 && qty>0 && side!="FLAT") (if(side=="LONG") price-entry else entry-price)*qty else 0.0
    }

    private fun saveLocked() {
        if (!::app.isInitialized) return
        try {
            val savedAt = System.currentTimeMillis()
            val arr=JSONArray(); bots.values.forEach { arr.put(it) }
            val totalCapital = bots.values.sumOf { it.optDouble("balance",100.0) + floatingPnl(it) }
            val totalPnl = bots.values.sumOf { it.optDouble("balance",100.0) - it.optDouble("initial",100.0) - it.optDouble("injectedCapital",0.0) + floatingPnl(it) }
            val totalTrades = bots.values.sumOf { it.optInt("trades",0) }
            val totalWins = bots.values.sumOf { it.optInt("wins",0) }
            val totalLosses = bots.values.sumOf { it.optInt("losses",0) }
            val root=JSONObject().put("schemaVersion",2).put("savedAt",savedAt)
                .put("appVersion","0.9").put("totalCapital",totalCapital).put("totalNetPnl",totalPnl)
                .put("totalTrades",totalTrades).put("totalWins",totalWins).put("totalLosses",totalLosses)
                .put("bots",arr)
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
            saveUserBackup(root)
        } catch(e: Exception) { lastError="No se pudo guardar: ${e.message}".take(120) }
    }
}
