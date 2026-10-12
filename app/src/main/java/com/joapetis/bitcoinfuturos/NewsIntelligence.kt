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

/**
 * Public-RSS news context for BTC. Observation-only by design: it never changes entry signals.
 * Stores compact metadata and sentiment scores locally so context survives app updates.
 */
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
    @Volatile private var summary = Summary(0, 0.0, "Aún no hay noticias RSS", 0L)
    private const val POLL_INTERVAL_MS = 15 * 60 * 1000L
    private const val MAX_ITEMS = 300

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
                    try {
                        for (item in fetch(feed)) byKey.putIfAbsent(item.optString("key"), item)
                    } catch (_: Exception) { /* A single unavailable publisher must not block the others. */ }
                }
                val items = byKey.values.sortedByDescending { it.optLong("publishedAt", 0L) }.take(MAX_ITEMS)
                val arr = JSONArray()
                items.forEach { arr.put(it) }
                val relevant = items.filter { it.optBoolean("btcRelevant", false) }
                val avg = if (relevant.isEmpty()) 0.0 else relevant.take(40).map { it.optDouble("sentiment", 0.0) }.average()
                val latest = relevant.firstOrNull()?.optString("title").orEmpty().ifBlank { "Sin titulares RSS relevantes para BTC" }
                val nowSaved = System.currentTimeMillis()
                val root = JSONObject().put("schemaVersion", 1).put("updatedAt", nowSaved).put("items", arr)
                context?.let { ctx ->
                    val dir = File(ctx.filesDir, "CryptoFutures")
                    dir.mkdirs()
                    val tmp = File(dir, "news-v1.json.tmp")
                    val dst = File(dir, "news-v1.json")
                    tmp.writeText(root.toString())
                    if (!tmp.renameTo(dst)) { dst.writeText(root.toString()); tmp.delete() }
                }
                summary = Summary(items.size, avg.coerceIn(-1.0, 1.0), latest, nowSaved)
            } finally {
                polling.set(false)
            }
        }
    }

    fun summary(): Summary = summary
    fun recentHeadlines(limit: Int = 20): List<JSONObject> = loadItems().let { arr ->
        (0 until minOf(limit, arr.length())).mapNotNull { arr.optJSONObject(it)?.let { obj -> JSONObject(obj.toString()) } }
    }

    private fun fetch(feed: Feed): List<JSONObject> {
        val conn = URL(feed.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "CryptoFuturesAndroid/0.9 RSS reader")
        conn.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
        return try {
            conn.inputStream.use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)
                val out = mutableListOf<JSONObject>()
                var event = parser.eventType
                var inItem = false
                var title = ""; var link = ""; var dateText = ""; var description = ""
                var tag = ""
                while (event != XmlPullParser.END_DOCUMENT && out.size < 80) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            tag = parser.name.lowercase(Locale.US)
                            if (tag == "item" || tag == "entry") {
                                inItem = true; title = ""; link = ""; dateText = ""; description = ""
                            } else if (inItem && tag == "link" && link.isBlank()) {
                                link = parser.getAttributeValue(null, "href").orEmpty()
                            }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                            val text = parser.text.orEmpty().trim()
                            when (tag) {
                                "title" -> if (title.isBlank()) title = text
                                "link" -> if (link.isBlank()) link = text
                                "pubdate", "published", "updated", "dc:date" -> if (dateText.isBlank()) dateText = text
                                "description", "summary", "content:encoded" -> if (description.isBlank()) description = text
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val end = parser.name.lowercase(Locale.US)
                            if (inItem && (end == "item" || end == "entry")) {
                                val cleanTitle = title.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                                if (cleanTitle.isNotBlank()) {
                                    val published = parseDate(dateText)
                                    val text = "$cleanTitle $description".lowercase(Locale.US)
                                    val score = sentiment(text)
                                    val normalizedTitle = cleanTitle.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
                                    val key = normalizedTitle.hashCode().toString()
                                    out.add(JSONObject().put("key", key).put("source", feed.name).put("title", cleanTitle.take(240))
                                        .put("link", link.take(1000)).put("publishedAt", published).put("fetchedAt", System.currentTimeMillis())
                                        .put("sentiment", score).put("btcRelevant", isBtcRelevant(text)))
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
        val positive = listOf("surge", "rally", "record high", "breakout", "bullish", "approval", "approved", "inflow", "adoption", "buy", "gains", "rise", "rises", "rallying", "optimism", "increased demand", "all-time high", "sube", "alcista", "aprobación", "adopción")
        val negative = listOf("crash", "plunge", "hack", "exploit", "lawsuit", "ban", "liquidation", "outflow", "bearish", "sell-off", "selloff", "falls", "drop", "decline", "risk", "fraud", "warning", "breach", "cuts", "cae", "bajista", "estafa", "prohibición")
        val p = positive.count { text.contains(it) }
        val n = negative.count { text.contains(it) }
        return if (p + n == 0) 0.0 else ((p - n).toDouble() / (p + n)).coerceIn(-1.0, 1.0)
    }

    private fun isBtcRelevant(text: String): Boolean =
        listOf("bitcoin", "btc", "crypto", "cryptocurrency", "federal reserve", "fed ", "inflation", "interest rate", "etf", "sec ").any { text.contains(it) }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        try { return java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return java.time.Instant.parse(raw).toEpochMilli() } catch (_: Exception) {}
        return 0L
    }

    private fun loadItems(): JSONArray {
        return try {
            val ctx = context ?: return JSONArray()
            val f = File(ctx.filesDir, "CryptoFutures/news-v1.json")
            if (!f.exists()) JSONArray() else JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    private fun load() {
        val arr = loadItems()
        val relevant = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("btcRelevant", false) }
        val avg = if (relevant.isEmpty()) 0.0 else relevant.take(40).map { it.optDouble("sentiment", 0.0) }.average()
        summary = Summary(relevant.size, avg.coerceIn(-1.0, 1.0), relevant.firstOrNull()?.optString("title").orEmpty().ifBlank { "Aún no hay noticias RSS relevantes para BTC" }, 0L)
    }
}
