package com.joapetis.bitcoinfuturos

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NewsIntelligence {
    data class Summary(val articles: Int, val sentiment: Double, val latestTitle: String, val updatedAt: Long)

    private data class Feed(val name: String, val url: String)
    private val feeds = listOf(
        Feed("Cointelegraph", "https://cointelegraph.com/rss"),
        Feed("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/?outputType=xml"),
        Feed("The Block", "https://www.theblock.co/rss.xml"),
        Feed("Bitcoin.com", "https://news.bitcoin.com/feed/")
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val polling = AtomicBoolean(false)
    @Volatile private var context: Context? = null
    @Volatile private var lastPollAt = 0L
    @Volatile private var summary = Summary(0, 0.0, "Sincronizando noticias RSS…", 0L)
    private const val POLL_INTERVAL_MS = 10 * 60 * 1000L
    private const val MAX_ITEMS = 200

    fun init(context: Context) {
        this.context = context.applicationContext
        load()
        pollIfDue()
    }

    fun pollIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPollAt < POLL_INTERVAL_MS || !polling.compareAndSet(false, true)) return
        lastPollAt = now
        executor.execute {
            try {
                val existing = loadItems()
                val byKey = LinkedHashMap<String, JSONObject>()
                for (i in 0 until existing.length()) {
                    val item = existing.optJSONObject(i) ?: continue
                    byKey[item.optString("key")] = item
                }
                for (feed in feeds) {
                    try { for (item in fetch(feed)) byKey.putIfAbsent(item.optString("key"), item) } catch (_: Exception) {}
                }
                val items = byKey.values.sortedByDescending { it.optLong("publishedAt", 0L) }.take(MAX_ITEMS)
                val arr = JSONArray(); items.forEach { arr.put(it) }
                val relevant = items.filter { it.optBoolean("btcRelevant", false) }
                val baseAvg = if (relevant.isEmpty()) 0.0 else relevant.take(25).map { it.optDouble("sentiment", 0.0) }.average()
                val topTitles = relevant.take(5).map { it.optString("title") }
                val latest = topTitles.firstOrNull().orEmpty().ifBlank { "Sin titulares RSS recientes" }
                val nowSaved = System.currentTimeMillis()

                // Gemini Nano analiza en segundo plano los 5 titulares más recientes para ajustar el sesgo del enjambre
                PixelAi.analyzeNewsHeadlinesAsync(topTitles) { nanoSentiment, nanoSummary ->
                    val blended = if (nanoSentiment != null) (nanoSentiment * 0.70 + baseAvg * 0.30).coerceIn(-1.0, 1.0) else baseAvg.coerceIn(-1.0, 1.0)
                    val headlineLabel = if (!nanoSummary.isNullOrBlank()) "$nanoSummary ($latest)" else latest
                    summary = Summary(items.size, blended, headlineLabel, nowSaved)
                    if (!nanoSummary.isNullOrBlank()) {
                        NativeTradingEngine.recordNewsEvent("Gemini Nano RSS (${String.format(Locale.US, "%+.2f", blended)}): $nanoSummary")
                    }
                }

                val root = JSONObject().put("schemaVersion", 1).put("updatedAt", nowSaved).put("items", arr)
                context?.let { ctx ->
                    val dir = File(ctx.filesDir, "CryptoFutures"); dir.mkdirs()
                    File(dir, "news-v1.json").writeText(root.toString())
                }
                summary = Summary(items.size, baseAvg.coerceIn(-1.0, 1.0), latest, nowSaved)
            } finally {
                polling.set(false)
            }
        }
    }

    fun summary(): Summary = summary

    private fun fetch(feed: Feed): List<JSONObject> {
        val conn = URL(feed.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "CryptoFuturesAndroid/0.20 RSS reader")
        return try {
            conn.inputStream.use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)
                val out = mutableListOf<JSONObject>()
                var event = parser.eventType
                var inItem = false; var title = ""; var link = ""; var dateText = ""; var description = ""; var tag = ""
                while (event != XmlPullParser.END_DOCUMENT && out.size < 50) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            tag = parser.name.lowercase(Locale.US)
                            if (tag == "item" || tag == "entry") { inItem = true; title = ""; link = ""; dateText = ""; description = "" }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                            val text = parser.text.orEmpty().trim()
                            when (tag) {
                                "title" -> if (title.isBlank()) title = text
                                "link" -> if (link.isBlank()) link = text
                                "pubdate", "published", "updated" -> if (dateText.isBlank()) dateText = text
                                "description", "summary" -> if (description.isBlank()) description = text
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val end = parser.name.lowercase(Locale.US)
                            if (inItem && (end == "item" || end == "entry")) {
                                val cleanTitle = title.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                                if (cleanTitle.isNotBlank()) {
                                    val text = "$cleanTitle $description".lowercase(Locale.US)
                                    val key = cleanTitle.lowercase(Locale.US).hashCode().toString()
                                    out.add(JSONObject().put("key", key).put("source", feed.name).put("title", cleanTitle.take(200))
                                        .put("publishedAt", System.currentTimeMillis()).put("sentiment", sentiment(text))
                                        .put("btcRelevant", isBtcRelevant(text)))
                                }
                                inItem = false
                            }
                        }
                    }
                    event = parser.nextToken()
                }
                out
            }
        } finally { conn.disconnect() }
    }

    private fun sentiment(text: String): Double {
        val positive = listOf("surge", "rally", "record high", "breakout", "bullish", "approval", "approved", "inflow", "adoption", "buy", "gains", "rise", "all-time high", "sube", "alcista")
        val negative = listOf("crash", "plunge", "hack", "exploit", "lawsuit", "ban", "liquidation", "outflow", "bearish", "sell-off", "drop", "decline", "fraud", "cae", "bajista")
        val p = positive.count { text.contains(it) }; val n = negative.count { text.contains(it) }
        return if (p + n == 0) 0.0 else ((p - n).toDouble() / (p + n)).coerceIn(-1.0, 1.0)
    }

    private fun isBtcRelevant(text: String): Boolean =
        listOf("bitcoin", "btc", "crypto", "federal reserve", "fed ", "inflation", "etf", "sec ").any { text.contains(it) }

    private fun loadItems(): JSONArray = try {
        val f = File(context?.filesDir, "CryptoFutures/news-v1.json")
        if (!f.exists()) JSONArray() else JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
    } catch (_: Exception) { JSONArray() }

    private fun load() {
        val arr = loadItems()
        val relevant = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("btcRelevant", false) }
        val avg = if (relevant.isEmpty()) 0.0 else relevant.take(25).map { it.optDouble("sentiment", 0.0) }.average()
        summary = Summary(relevant.size, avg.coerceIn(-1.0, 1.0), relevant.firstOrNull()?.optString("title").orEmpty().ifBlank { "Sin noticias RSS aún" }, 0L)
    }
}
