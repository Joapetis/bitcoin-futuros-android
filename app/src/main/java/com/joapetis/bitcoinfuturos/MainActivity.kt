package com.joapetis.bitcoinfuturos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val bg = Color.rgb(5, 10, 17); private val card = Color.rgb(13, 23, 36); private val line = Color.rgb(24, 43, 66)
    private val blue = Color.rgb(30, 132, 255); private val green = Color.rgb(0, 220, 153); private val red = Color.rgb(255, 77, 90)
    private val muted = Color.rgb(137, 154, 174); private val orange = Color.rgb(247, 147, 26)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout; private lateinit var content: LinearLayout; private lateinit var mainScroll: ScrollView
    private var statusPill: TextView? = null
    private var liveChart: View? = null
    private var homePnlText: TextView? = null; private var homeCapitalText: TextView? = null; private var homePercentText: TextView? = null
    private var heroPriceText: TextView? = null; private var heroChangeText: TextView? = null
    private var tileTrades: TextView? = null; private var tileWins: TextView? = null; private var tileLosses: TextView? = null; private var tileRate: TextView? = null
    private var aiBannerTitle: TextView? = null; private var aiBannerSub: TextView? = null
    private var operationStatusView: TextView? = null; private var operationPositionView: TextView? = null; private var operationExpertsView: TextView? = null; private var operationMarketDataView: TextView? = null
    private var operationActivityContainer: LinearLayout? = null; private var operationActivitySignature = ""
    private var lastOperationRefreshAt = 0L; private var lastAiRefreshAt = 0L
    private var aiChatBox: LinearLayout? = null; private var aiInput: EditText? = null
    private var aiStatusView: TextView? = null; private var aiBrainView: TextView? = null; private var aiLessonsView: TextView? = null
    private var historySortBest = false
    private var page = "Inicio"; private var selected = "BTC-USDT"; private var chartBar = "1m"
    private val backupPicker = 4102; private val speechRequest = 4103; private val backupFilePicker = 4104
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun money(v: Double) = String.format(Locale.US, "%,.2f USDT", v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.statusBarColor = bg; window.navigationBarColor = bg
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        NativeTradingEngine.init(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 410)
        if (getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("bg_service", true)) startBotService(true)
        PixelAi.prepare { handler.post { if (page == "IA") aiStatusView?.text = PixelAi.statusText } }
        render(); handler.post(refreshRunnable)
        handler.postDelayed({
            if (NativeTradingEngine.shouldRequestBackupFolder()) {
                NativeTradingEngine.markBackupPrompted()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Configurar copia de seguridad")
                    .setMessage("Selecciona la carpeta Documentos para guardar automáticamente el estado y aprendizaje de la IA en Documentos/CryptoFutures.")
                    .setNegativeButton("Ahora no", null)
                    .setPositiveButton("Elegir Documentos") { _, _ -> openBackupFolderPicker() }
                    .show()
            }
        }, 700)
    }

    private fun startBotService(start: Boolean) {
        val service = Intent(this, BotKeepAliveService::class.java).setAction(if (start) BotKeepAliveService.ACTION_START else BotKeepAliveService.ACTION_STOP)
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::root.isInitialized) {
                val snapshots = NativeTradingEngine.snapshots()
                updateStatusPill()
                if (page == "Inicio") updateHomeTotals(snapshots)
                val now = System.currentTimeMillis()
                if ((page == "Inicio" || page == "Operaciones") && now - lastOperationRefreshAt >= 1000L) { lastOperationRefreshAt = now; updateLiveOperationPanel() }
                if (page == "IA" && now - lastAiRefreshAt >= 1500L) { lastAiRefreshAt = now; updateAiPanels() }
                liveChart?.invalidate()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8)) }
        val logo = TextView(this).apply {
            text = "₿"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(orange) }
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brand.addView(text("Bitcoin Futures", 19f, Color.WHITE, true).apply { setPadding(0, 0, 0, 0) })
        brand.addView(text("Simulador Swing MTF + Gemini Nano · Alpha 0.1", 11f, muted, false).apply { setPadding(0, 0, 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = pill("", green, Color.argb(40, 0, 220, 153)); header.addView(statusPill)
        root.addView(header)
        mainScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(14)) }; mainScroll.addView(content)
        root.addView(mainScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { tag = "bottom_navigation"; orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(5), dp(4), dp(5)); setBackgroundColor(Color.rgb(7, 14, 23)) }
        listOf("Inicio", "Operaciones", "IA", "Historial", "Ajustes").forEach { name ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(1), dp(5), dp(1), dp(3))
                setOnClickListener { page = name; if (name == "Historial") historySortBest = false; render() }
            }
            item.addView(text(when (name) { "Inicio" -> "⌂"; "Operaciones" -> "▣"; "IA" -> "✦"; "Historial" -> "◷"; else -> "⚙" }, 20f, if (page == name) blue else muted, true))
            item.addView(text(name, 9f, if (page == name) blue else muted, false)); nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = insets.getInsets(WindowInsets.Type.systemBars()); val ime = insets.getInsets(WindowInsets.Type.ime())
                val imeOpen = insets.isVisible(WindowInsets.Type.ime())
                view.setPadding(0, sys.top, 0, if (imeOpen) ime.bottom else 0)
                nav.visibility = if (imeOpen) View.GONE else View.VISIBLE
                nav.setPadding(dp(4), dp(5), dp(4), sys.bottom + dp(5))
            } else {
                @Suppress("DEPRECATION") view.setPadding(0, insets.systemWindowInsetTop, 0, 0)
                @Suppress("DEPRECATION") nav.setPadding(dp(4), dp(5), dp(4), insets.systemWindowInsetBottom + dp(5))
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        updateScreen()
    }

    private fun updateStatusPill() {
        val running = NativeTradingEngine.isRunning()
        statusPill?.let {
            it.text = if (running) "● ACTIVO" else "■ PAUSADO"
            it.setTextColor(if (running) green else Color.YELLOW)
            it.background = rounded(if (running) Color.argb(40, 0, 220, 153) else Color.argb(40, 255, 214, 0), 20, Color.TRANSPARENT)
        }
    }

    private fun updateHomeTotals(snapshots: List<BotSnapshot>) {
        val pnl = snapshots.sumOf { it.pnl }; val capital = snapshots.sumOf { it.equity }
        val col = if (pnl >= 0) green else red
        homePnlText?.let { it.text = (if (pnl >= 0) "+" else "") + money(pnl); it.setTextColor(col) }
        homeCapitalText?.text = "Capital total: " + money(capital)
        homePercentText?.let { it.text = String.format(Locale.US, "%+.2f%%", if (capital > 0) pnl / capital * 100.0 else 0.0); it.setTextColor(col) }
        val s = snapshots.firstOrNull()
        if (s != null) {
            heroPriceText?.text = if (s.price > 0) String.format(Locale.US, "%,.2f", s.price) else "—"
            val ch = NativeTradingEngine.marketChange24h("BTC-USDT")
            heroChangeText?.let { it.text = String.format(Locale.US, "%+.2f%% 24h", ch); it.setTextColor(if (ch >= 0) green else red) }
            tileTrades?.text = s.trades.toString(); tileWins?.text = s.wins.toString(); tileLosses?.text = (s.trades - s.wins).coerceAtLeast(0).toString()
            tileRate?.text = if (s.trades > 0) String.format(Locale.US, "%.0f%%", s.wins * 100.0 / s.trades) else "—"
            aiBannerTitle?.text = "✦ IA · " + NativeTradingEngine.aiWarmupLabel()
            aiBannerSub?.text = s.status
        }
    }

    private fun updateScreen() {
        if (!::content.isInitialized) return
        val bots = NativeTradingEngine.snapshots()
        updateStatusPill()
        content.removeAllViews()
        liveChart = null; homePnlText = null; heroPriceText = null; aiBannerTitle = null; aiChatBox = null; aiInput = null; aiStatusView = null; aiBrainView = null; aiLessonsView = null
        operationStatusView = null; operationPositionView = null; operationExpertsView = null; operationMarketDataView = null; operationActivityContainer = null
        when (page) {
            "Inicio" -> pageHome(bots)
            "Operaciones" -> pageOperations()
            "IA" -> pageAi()
            "Historial" -> pageHistory(bots)
            else -> pageSettings(bots)
        }
    }

    private fun pageHome(bots: List<BotSnapshot>) {
        val pnl = bots.sumOf { it.pnl }; val capital = bots.sumOf { it.equity }
        overviewCard(pnl, capital)
        startStopButton()
        heroCard()
        statTiles()
        aiBanner()
        val chartCard = cardBox()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("1m", "5m", "15m", "1H", "4H", "1D").forEach { b ->
            val sel = b == chartBar
            val chip = pill(b, if (sel) Color.WHITE else muted, if (sel) blue else Color.TRANSPARENT).apply {
                setOnClickListener { if (chartBar != b) { chartBar = b; updateScreen() } }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
        }
        chartCard.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        chart(chartCard)
        chartCard.addView(text("Bandas de Bollinger (20,2) · EMA 20 · RSI 14 · volumen · velas " + chartBar, 10f, muted, false))
        addCard(chartCard)
        NativeTradingEngine.requestChartCandles("BTC-USDT", chartBar) { handler.post { liveChart?.invalidate() } }
        if (chartBar != "1H") NativeTradingEngine.requestChartCandles("BTC-USDT", "1H") { }
        val live = cardBox()
        live.addView(text("Posición abierta y Enjambre", 15f, Color.WHITE, true))
        operationStatusView = text("", 13f, Color.WHITE, true); live.addView(operationStatusView)
        operationPositionView = text("", 12f, muted, false); live.addView(operationPositionView)
        operationExpertsView = text("", 11f, muted, false); live.addView(operationExpertsView)
        addCard(live)
        val market = cardBox(); market.addView(text("Datos de mercado y Order Flow", 15f, Color.WHITE, true))
        operationMarketDataView = text("", 11f, muted, false); market.addView(operationMarketDataView); addCard(market)
        content.addView(text("Actividad del bot", 17f, Color.WHITE, true))
        operationActivityContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(operationActivityContainer)
        operationActivitySignature = ""
        updateLiveOperationPanel(forceActivity = true)
        updateHomeTotals(bots)
    }

    private fun pageOperations() {
        panel("Operación en vivo", "Bitcoin · Enjambre Multi-Temporal + Variantes en Sombra + Gemini Nano")
        val c1 = cardBox()
        operationStatusView = text("", 13f, Color.WHITE, true); c1.addView(operationStatusView)
        operationPositionView = text("", 12f, muted, false); c1.addView(operationPositionView); addCard(c1)
        val c2 = cardBox(); c2.addView(text("Decisiones de la IA y Enjambre Multi-Temporal", 15f, Color.WHITE, true))
        operationExpertsView = text("", 11f, muted, false); c2.addView(operationExpertsView)
        operationMarketDataView = text("", 11f, muted, false); c2.addView(operationMarketDataView); addCard(c2)
        content.addView(text("Actividad del bot", 17f, Color.WHITE, true))
        content.addView(text("Entradas, variantes en sombra, protección break-even, cierres y lecciones", 11f, muted, false))
        operationActivityContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(operationActivityContainer)
        operationActivitySignature = ""
        updateLiveOperationPanel(forceActivity = true)
    }

    private fun pageAi() {
        panel("IA del Pixel 10", "Gemini Nano · estrategia Swing multi-temporal y Noticias RSS")
        val statusCard = cardBox(); statusCard.addView(text("Modelo Gemini Nano en el dispositivo (AICore)", 15f, Color.WHITE, true))
        aiStatusView = text(PixelAi.statusText, 12f, if (PixelAi.available) green else muted, false); statusCard.addView(aiStatusView)
        statusCard.addView(smallButton("Sincronizar / Activar Gemini Nano ahora") {
            aiStatusView?.text = "Sincronizando con Android AICore…"; PixelAi.forceRetry { handler.post { aiStatusView?.text = PixelAi.statusText; aiStatusView?.setTextColor(if (PixelAi.available) green else muted) } }
        }); addCard(statusCard)
        val brainCard = cardBox(); brainCard.addView(text("Cerebro Colectivo (MTF + Variantes Sombra + RSS)", 15f, Color.WHITE, true))
        aiBrainView = text("", 12f, muted, false); brainCard.addView(aiBrainView); addCard(brainCard)

        val chatCard = cardBox(); chatCard.addView(text("Órdenes a la IA", 15f, Color.WHITE, true))
        chatCard.addView(text("Ejemplos: «resumen», «modo agresivo», «riesgo 3%», «no operes entre las 0 y las 6».", 11f, muted, false))
        aiChatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatCard.addView(aiChatBox)
        val history = NativeTradingEngine.aiChatMessages()
        if (history.isEmpty()) addBubble("ai", "Hola. Soy la IA del enjambre. Todos los bots vigilan 1m, 5m, 15m, 1H y 4H simultáneamente, crean variantes en sombra de cada operación real y Gemini Nano analiza las noticias RSS en vivo.")
        else history.takeLast(20).forEach { addBubble(it.first, it.second) }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Dale una orden a la IA…"; setHintTextColor(muted); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; maxLines = 3
            background = rounded(Color.rgb(8, 16, 26), 14, line); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        aiInput = input
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(smallButton("🎤") { startVoiceInput() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, 0, 0) })
        inputRow.addView(smallButton("Enviar") { sendAi(input.text.toString()) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, 0, 0) })
        chatCard.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) })
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Resumen", "¿Qué has aprendido?", "Explica la última operación", "Modo conservador", "Modo normal", "Modo agresivo", "Pausa", "Reanuda").forEach { q ->
            quick.addView(pill(q, Color.WHITE, Color.rgb(20, 40, 64)).apply { setOnClickListener { sendAi(q) } },
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
        }
        chatCard.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick); setPadding(0, dp(8), 0, 0) })
        addCard(chatCard)
        val lessons = cardBox(); lessons.addView(text("Lecciones aprendidas en vivo", 15f, Color.WHITE, true))
        aiLessonsView = text("", 11f, muted, false); lessons.addView(aiLessonsView); addCard(lessons)
        updateAiPanels()
    }

    private fun updateAiPanels() {
        aiStatusView?.let { it.text = PixelAi.statusText; it.setTextColor(if (PixelAi.available) green else muted) }
        aiBrainView?.text = NativeTradingEngine.aiBrainSummary().joinToString("\n")
        val l = NativeTradingEngine.aiLessons(10)
        aiLessonsView?.text = if (l.isEmpty()) "Recopilando muestras del enjambre en sombra…" else l.joinToString("\n\n")
    }

    private fun addBubble(role: String, msg: String): TextView {
        val user = role == "user"
        val bubble = TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(if (user) Color.rgb(20, 70, 140) else Color.rgb(8, 16, 26), 14, line)
        }
        aiChatBox?.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(if (user) dp(40) else 0, dp(4), if (user) 0 else dp(40), dp(4))
        })
        mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun sendAi(raw: String) {
        val msg = raw.trim(); if (msg.isEmpty()) return
        addBubble("user", msg); aiInput?.setText("")
        val thinking = addBubble("ai", "…")
        AiCommander.handle(msg) { reply ->
            runOnUiThread {
                thinking.text = reply; lastAiRefreshAt = 0L; updateStatusPill(); updateAiPanels()
                mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime una orden para la IA")
        try { startActivityForResult(intent, speechRequest) }
        catch (_: Exception) { Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_LONG).show() }
    }

    private fun pageHistory(bots: List<BotSnapshot>) {
        panel("Historial detallado", "Resultados y cada operación cerrada")
        bots.forEach { bot ->
            val history = NativeTradingEngine.tradeHistory(bot.symbol)
            val trades = history.filter { it.optString("type", "TRADE") == "TRADE" }
            val wins = trades.count { it.optDouble("pnl") > 0 }
            val losses = trades.count { it.optDouble("pnl") <= 0 }
            cardBlock("BTC/USDT · " + NativeTradingEngine.strategyName(bot.symbol),
                bot.trades.toString() + " cierres · " + wins + " ganancias · " + losses + " pérdidas\nBalance " + money(bot.balance) + " · P/L neto " + money(bot.pnl) +
                    "\n" + NativeTradingEngine.aiWarmupLabel() + " · Recargas " + history.count { it.optString("type") == "RECHARGE" })
        }
        val all = NativeTradingEngine.tradeHistory().take(150)
        if (all.isEmpty()) content.addView(text("Todavía no hay operaciones cerradas.", 13f, muted, false))
        all.forEach { item ->
            val recharge = item.optString("type") == "RECHARGE"
            val profit = item.optDouble("pnl", 0.0)
            val time = java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(java.util.Date(item.optLong("time")))
            val margin = item.optDouble("entryNotional", 0.0) / item.optInt("leverage", 100).coerceAtLeast(1)
            val roi = if (margin > 0.0) profit / margin * 100.0 else 0.0
            val title = if (recharge) "RECARGA " + money(item.optDouble("amount")) else "BTC/USDT · " + item.optString("side") + " · " + (if (profit >= 0) "+" else "") + money(profit) + " · " + String.format(Locale.US, "%+.2f%%", roi)
            var details = if (recharge) time + "\n" + item.optString("reason") + "\nSaldo tras recarga: " + money(item.optDouble("balanceAfter"))
            else time + " · " + item.optString("strategy") + " · " + item.optString("timeframe") + "\nEntrada: " + String.format(Locale.US, "%,.2f", item.optDouble("entry")) + " → Salida: " + String.format(Locale.US, "%,.2f", item.optDouble("exit")) +
                "\nCantidad: " + String.format(Locale.US, "%.6f", item.optDouble("qty")) + " · Nocional: " + money(item.optDouble("entryNotional")) +
                "\nBruto: " + money(item.optDouble("gross")) + " · Comisiones: " + money(item.optDouble("fees")) + " · Financiación: " + money(item.optDouble("funding")) +
                "\nStop: " + String.format(Locale.US, "%,.2f", item.optDouble("stop")) + " · Objetivo: " + String.format(Locale.US, "%,.2f", item.optDouble("target")) +
                "\nCierre: " + item.optString("reason") + " · Duración: " + (item.optLong("durationMs") / 1000) + " s" +
                "\nSaldo: " + money(item.optDouble("balanceBefore")) + " → " + money(item.optDouble("balanceAfter"))
            val lesson = item.optString("lesson", "")
            if (lesson.isNotEmpty()) details += "\nLección: $lesson"
            val color = if (recharge) blue else if (profit >= 0) green else red
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(dp(11), dp(10), dp(11), dp(10)); background = rounded(card, 14, line) }
            row.addView(text(if (recharge) "↻" else if (item.optString("side") == "LONG") "↗" else "↘", 22f, color, true), LinearLayout.LayoutParams(dp(34), -2))
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(text(title, 13f, color, true)); body.addView(text(details, 11f, muted, false))
            row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
    }

    private fun pageSettings(bots: List<BotSnapshot>) {
        val btc = bots.firstOrNull { it.symbol == "BTC-USDT" } ?: bots.first()
        panel("Ajustes", "Configuración del Enjambre y la IA")
        val app = cardBox(); app.addView(text("Configuración de la app", 15f, Color.WHITE, true))
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        app.addView(switchRow("Ejecutar en segundo plano", "Mantiene el enjambre y Gemini Nano activos", prefs.getBoolean("bg_service", true)) { on ->
            prefs.edit().putBoolean("bg_service", on).apply(); startBotService(on)
        }); addCard(app)
        val bot = cardBox(); bot.addView(text("Configuración del bot", 15f, Color.WHITE, true))
        bot.addView(text("Riesgo por operación (% del balance actual)", 12f, Color.WHITE, true))
        val risks = listOf(1.0, 2.0, 3.0, 5.0, 7.0, 10.0)
        bot.addView(chipRow(risks.map { String.format(Locale.US, "%.0f%%", it) }, risks.indexOfFirst { abs(it - btc.risk) < 0.05 }) { NativeTradingEngine.setRisk("BTC-USDT", risks[it]); updateScreen() })
        bot.addView(text("Apalancamiento máximo", 12f, Color.WHITE, true))
        val levs = listOf(3, 5, 10, 20, 50, 75, 100)
        bot.addView(chipRow(levs.map { "x$it" }, levs.indexOf(btc.leverage)) { NativeTradingEngine.setLeverage("BTC-USDT", levs[it]); updateScreen() })
        bot.addView(text("Modo de la IA", 12f, Color.WHITE, true))
        val modes = listOf("conservador", "normal", "agresivo")
        bot.addView(chipRow(modes.map { it.replaceFirstChar { c -> c.uppercase() } }, modes.indexOf(NativeTradingEngine.aiMode())) { NativeTradingEngine.aiSetMode(modes[it]); updateScreen() })
        addCard(bot)
        actionButton("Seleccionar carpeta de respaldo (conservar aprendizaje)") { openBackupFolderPicker() }
        actionButton("Restaurar último respaldo de la carpeta") {
            Toast.makeText(this, NativeTradingEngine.restoreUserBackup(), Toast.LENGTH_LONG).show(); updateScreen()
        }
        actionButton("Importar copia JSON (tras reinstalar)") {
            val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/json";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            startActivityForResult(intent,backupFilePicker)
        }
    }

    private fun overviewCard(pnl: Double, capital: Double) {
        val box = cardBox(16)
        box.addView(text("Ganancia/Pérdida total", 13f, muted, false))
        val profit = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        homePnlText = text((if (pnl >= 0) "+" else "") + money(pnl), 26f, if (pnl >= 0) green else red, true)
        profit.addView(homePnlText)
        homePercentText = pill(String.format(Locale.US, "%+.2f%%", if (capital > 0) pnl / capital * 100.0 else 0.0), if (pnl >= 0) green else red, Color.argb(40, 0, 220, 153))
        profit.addView(homePercentText, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(10), 0, 0, 0) })
        box.addView(profit)
        homeCapitalText = text("Capital total: " + money(capital), 12f, muted, false); box.addView(homeCapitalText)
        addCard(box, 4, 8)
    }

    private fun startStopButton() {
        val running = NativeTradingEngine.isRunning()
        val btn = Button(this).apply {
            text = if (running) "■  PARAR OPERACIONES Y CERRAR" else "▶  REANUDAR OPERACIONES"
            isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            background = rounded(if (running) Color.rgb(190, 35, 48) else Color.rgb(0, 130, 90), 14, Color.TRANSPARENT)
            setOnClickListener {
                if (!NativeTradingEngine.isRunning()) {
                    NativeTradingEngine.resumeAll(); updateScreen()
                } else {
                    val result = NativeTradingEngine.stopAllAndCloseNow(); updateScreen()
                    Toast.makeText(this@MainActivity, String.format(Locale.US, "Pausado · Neto: %+.2f USDT", result), Toast.LENGTH_LONG).show()
                }
            }
        }
        content.addView(btn, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, dp(2), 0, dp(10)) })
    }

    private fun heroCard() {
        val box = cardBox(14)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = TextView(this).apply {
            text = "₿"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(orange) }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val name = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        name.addView(text("BTC/USDT", 16f, Color.WHITE, true).apply { setPadding(0, 0, 0, 0) })
        name.addView(text("Bitcoin · Enjambre 1m/5m/15m/1H/4H + L2", 11f, muted, false).apply { setPadding(0, 0, 0, 0) })
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        heroPriceText = text("—", 22f, Color.WHITE, true).apply { setPadding(0, 0, 0, 0); gravity = Gravity.END }
        heroChangeText = text("", 12f, green, true).apply { setPadding(0, 0, 0, 0); gravity = Gravity.END }
        right.addView(heroPriceText); right.addView(heroChangeText)
        row.addView(right)
        box.addView(row); addCard(box)
    }

    private fun statTiles() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, color: Int): TextView {
            val t = cardBox(10); val v = text("—", 20f, color, true).apply { setPadding(0, 0, 0, 0) }
            t.addView(v); t.addView(text(label, 10f, muted, false).apply { setPadding(0, 0, 0, 0) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            return v
        }
        tileTrades = tile("Cierres", Color.WHITE); tileWins = tile("Ganadas", green); tileLosses = tile("Perdidas", red); tileRate = tile("Acierto", blue)
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(6)) })
    }

    private fun aiBanner() {
        val box = cardBox(14).apply { isClickable = true; setOnClickListener { page = "IA"; render() } }
        aiBannerTitle = text("✦ IA", 14f, blue, true).apply { setPadding(0, 0, 0, 0) }
        aiBannerSub = text("", 11f, muted, false).apply { setPadding(0, dp(2), 0, 0); maxLines = 2 }
        box.addView(aiBannerTitle); box.addView(aiBannerSub)
        box.addView(text("Toca para ver a Gemini Nano, las variantes en sombra y dar órdenes ›", 10f, muted, false).apply { setPadding(0, dp(2), 0, 0) })
        addCard(box)
    }

    private fun updateLiveOperationPanel(forceActivity: Boolean = false) {
        val state = NativeTradingEngine.jointOperationState()
        val running = state.optBoolean("enabled", true)
        val side = state.optString("side", "FLAT")
        val price = state.optDouble("price", 0.0)
        val entry = state.optDouble("entry", 0.0)
        val qty = state.optDouble("qty", 0.0)
        val floating = state.optDouble("floatingPnl", 0.0)
        val status = if (!running) "PAUSADO · pulsa Reanudar en Inicio" else if (side == "FLAT") "ACTIVO · Enjambre MTF + Gemini Nano vigilando L2" else "OPERACIÓN ABIERTA · $side (+4 variantes en sombra)"
        operationStatusView?.text = status + "\nBTC/USDT · " + (if (price > 0) String.format(Locale.US, "%,.2f USDT", price) else "esperando cotización")
        operationStatusView?.setTextColor(if (!running) Color.YELLOW else if (side == "FLAT") muted else green)
        operationPositionView?.text = if (side == "FLAT") {
            "Sin posición abierta\nCapital: " + money(state.optDouble("balance", 100.0)) + "\n" + state.optString("lastSignal", "Esperando análisis") +
                "\nEnjambre MTF: " + state.optString("expertConsensusDetail", "Sincronizando 1m/5m/15m/1H/4H")
        } else {
            "Dirección: $side · Cantidad: " + String.format(Locale.US, "%.6f BTC", qty) +
                "\nEntrada: " + String.format(Locale.US, "%,.2f", entry) + " · Actual: " + String.format(Locale.US, "%,.2f", price) +
                "\nP/L flotante bruto: " + String.format(Locale.US, "%+.2f USDT", floating) +
                "\nStop: " + String.format(Locale.US, "%,.2f", state.optDouble("stop", 0.0)) + " · Objetivo: " + String.format(Locale.US, "%,.2f", state.optDouble("target", 0.0)) +
                "\nProtección Break-Even / Trailing: " + if (state.optBoolean("trailingActive", false)) "ACTIVA" else "vigilando recorrido"
        }
        operationExpertsView?.text = NativeTradingEngine.aiBrainSummary().joinToString("\n")
        operationMarketDataView?.text = "Precio: " + NativeTradingEngine.liveQuoteSource() + "\n" + NativeTradingEngine.pionexFundingStatus() + "\n" + NativeTradingEngine.pionexOrderBookStatus() + "\n" + NativeTradingEngine.compoundingStatus()
        val activity = NativeTradingEngine.jointOperationActivity().take(40)
        val signature = activity.joinToString("|") { it.optLong("time").toString() + ":" + it.optString("type") + ":" + it.optString("message") }
        if (forceActivity || signature != operationActivitySignature) {
            operationActivitySignature = signature
            operationActivityContainer?.let { parent ->
                parent.removeAllViews()
                if (activity.isEmpty()) parent.addView(text("Todavía no hay movimientos registrados.", 12f, muted, false))
                activity.forEach { event ->
                    val time = java.text.SimpleDateFormat("HH:mm:ss · dd/MM/yyyy", Locale.getDefault()).format(java.util.Date(event.optLong("time")))
                    val type = event.optString("type", "EVENTO")
                    val color = when (type) { "APERTURA" -> green; "CIERRE", "CIERRE MANUAL" -> if (event.optDouble("pnl", 0.0) >= 0) green else red; "PROTECCIÓN" -> blue; "LECCIÓN", "NOTICIAS IA" -> orange; "FILTRO" -> Color.YELLOW; else -> Color.WHITE }
                    val box = cardBox(10)
                    box.addView(text(type + " · " + time, 12f, color, true))
                    box.addView(text(event.optString("message", ""), 11f, muted, false))
                    val eventPrice = event.optDouble("price", 0.0)
                    if (eventPrice > 0) box.addView(text("BTC: " + String.format(Locale.US, "%,.2f USDT", eventPrice), 10f, muted, false))
                    parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(2)) })
                }
            }
        }
    }

    private fun chart(parent: LinearLayout) {
        val v = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val baseCandles = NativeTradingEngine.chartCandles(selected, chartBar)
                val quote = NativeTradingEngine.snapshots().firstOrNull { it.symbol == selected }?.price ?: 0.0
                val all = (if (baseCandles.isNotEmpty() && quote > 0.0) {
                    val last = baseCandles.last()
                    baseCandles.dropLast(1) + last.copy(high = maxOf(last.high, quote), low = minOf(last.low, quote), close = quote)
                } else baseCandles).takeLast(120)
                val w = width.toFloat(); val h = height.toFloat()
                if (w <= 0f || h <= 0f) return
                p.style = Paint.Style.FILL; p.pathEffect = null
                p.color = Color.rgb(7, 14, 23); c.drawRect(0f, 0f, w, h, p)
                if (all.size < 25) { p.color = muted; p.textSize = dp(12).toFloat(); c.drawText("Cargando velas $chartBar…", dp(12).toFloat(), h / 2, p); return }
                val n = all.size; val shown = minOf(60, n); val off = n - shown
                val closes = all.map { it.close }
                val mid = DoubleArray(n) { Double.NaN }; val up = DoubleArray(n) { Double.NaN }; val lo = DoubleArray(n) { Double.NaN }; val ema = DoubleArray(n) { Double.NaN }
                val k = 2.0 / 21.0; var e = closes[0]
                for (i in 0 until n) {
                    e = if (i == 0) closes[0] else closes[i] * k + e * (1 - k); ema[i] = e
                    if (i >= 19) {
                        var s = 0.0; for (j in i - 19..i) s += closes[j]
                        val m = s / 20.0; var sq = 0.0; for (j in i - 19..i) sq += (closes[j] - m) * (closes[j] - m)
                        val sd = sqrt(sq / 20.0); mid[i] = m; up[i] = m + 2 * sd; lo[i] = m - 2 * sd
                    }
                }
                val rsi = DoubleArray(n) { Double.NaN }
                var ag = 0.0; var al = 0.0
                for (i in 1 until n) {
                    val d = closes[i] - closes[i - 1]; val g = if (d > 0) d else 0.0; val l = if (d < 0) -d else 0.0
                    if (i <= 14) { ag += g / 14.0; al += l / 14.0 } else { ag = (ag * 13 + g) / 14.0; al = (al * 13 + l) / 14.0 }
                    if (i >= 14) rsi[i] = if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al)
                }
                var minP = Double.MAX_VALUE; var maxP = -Double.MAX_VALUE
                for (i in off until n) {
                    minP = minOf(minP, all[i].low); maxP = maxOf(maxP, all[i].high)
                    if (!lo[i].isNaN()) { minP = minOf(minP, lo[i]); maxP = maxOf(maxP, up[i]) }
                }
                val range = (maxP - minP).takeIf { it > 0 } ?: 1.0
                val top = dp(14).toFloat(); val priceBottom = h * 0.60f
                val volTop = h * 0.64f; val volBottom = h * 0.74f; val rsiTop = h * 0.80f; val rsiBottom = h * 0.96f
                val plotRight = w - dp(62)
                val slot = plotRight / shown; val bodyW = (slot * 0.58f).coerceAtLeast(dp(2).toFloat())
                fun yP(x: Double) = priceBottom - ((x - minP) / range).toFloat() * (priceBottom - top)
                fun xI(i: Int) = slot * (i - off + 0.5f)
                p.color = Color.rgb(25, 39, 53); p.strokeWidth = dp(1).toFloat()
                for (i in 0..3) c.drawLine(0f, top + (priceBottom - top) * i / 3, w, top + (priceBottom - top) * i / 3, p)
                val band = Path(); var started = false
                for (i in off until n) if (!up[i].isNaN()) { if (!started) { band.moveTo(xI(i), yP(up[i])); started = true } else band.lineTo(xI(i), yP(up[i])) }
                for (i in n - 1 downTo off) if (!lo[i].isNaN()) band.lineTo(xI(i), yP(lo[i]))
                band.close(); p.style = Paint.Style.FILL; p.color = Color.argb(34, 30, 132, 255); c.drawPath(band, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat(); p.color = Color.argb(160, 30, 132, 255)
                for (arr in listOf(up, lo)) {
                    val path = Path(); var s = false
                    for (i in off until n) if (!arr[i].isNaN()) { if (!s) { path.moveTo(xI(i), yP(arr[i])); s = true } else path.lineTo(xI(i), yP(arr[i])) }
                    c.drawPath(path, p)
                }
                p.color = orange; p.strokeWidth = dp(1).toFloat() * 1.3f
                val emaPath = Path()
                for (i in off until n) if (i == off) emaPath.moveTo(xI(i), yP(ema[i])) else emaPath.lineTo(xI(i), yP(ema[i]))
                c.drawPath(emaPath, p); p.style = Paint.Style.FILL
                val maxVol = all.drop(off).maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
                for (i in off until n) {
                    val kd = all[i]; val x = xI(i); val upC = kd.close >= kd.open
                    p.color = if (upC) green else red; p.strokeWidth = dp(1).toFloat()
                    c.drawLine(x, yP(kd.high), x, yP(kd.low), p)
                    val yo = yP(kd.open); val yc = yP(kd.close)
                    c.drawRect(x - bodyW / 2, minOf(yo, yc), x + bodyW / 2, maxOf(yo, yc).coerceAtLeast(minOf(yo, yc) + dp(1)), p)
                    p.alpha = 120
                    val vh = ((kd.volume / maxVol) * (volBottom - volTop)).toFloat()
                    c.drawRect(x - bodyW / 2, volBottom - vh, x + bodyW / 2, volBottom, p); p.alpha = 255
                }
                val live = NativeTradingEngine.jointOperationState()
                if (live.optString("side", "FLAT") != "FLAT") {
                    listOf(Triple(live.optDouble("entry", 0.0), blue, "ENTRADA"), Triple(live.optDouble("stop", 0.0), red, "STOP"), Triple(live.optDouble("target", 0.0), green, "OBJETIVO"))
                        .filter { it.first > minP && it.first < maxP }.forEach { (price, color, label) ->
                            val y = yP(price)
                            p.color = color; p.strokeWidth = dp(1).toFloat(); p.style = Paint.Style.STROKE
                            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
                            c.drawLine(0f, y, plotRight, y, p)
                            p.pathEffect = null; p.style = Paint.Style.FILL; p.textSize = dp(9).toFloat()
                            c.drawText(label + " " + String.format(Locale.US, "%,.2f", price), dp(5).toFloat(), (y - dp(3)).coerceAtLeast(dp(10).toFloat()), p)
                        }
                }
                val lastClose = closes.last(); val ly = yP(lastClose)
                p.color = if (all.last().close >= all.last().open) green else red
                val tag = RectF(plotRight + dp(2), ly - dp(9), w - dp(2), ly + dp(9)); c.drawRoundRect(tag, dp(4).toFloat(), dp(4).toFloat(), p)
                p.color = Color.WHITE; p.textSize = dp(10).toFloat(); c.drawText(String.format(Locale.US, "%,.0f", lastClose), tag.left + dp(3), ly + dp(4), p)
                p.color = muted; p.textSize = dp(9).toFloat()
                c.drawText(String.format(Locale.US, "%,.0f", maxP), plotRight + dp(4), top + dp(8), p)
                c.drawText(String.format(Locale.US, "%,.0f", minP), plotRight + dp(4), priceBottom - dp(2), p)
                p.color = Color.rgb(25, 39, 53); p.strokeWidth = dp(1).toFloat()
                fun yR(x: Double) = rsiBottom - (x / 100.0).toFloat() * (rsiBottom - rsiTop)
                c.drawLine(0f, yR(70.0), w, yR(70.0), p); c.drawLine(0f, yR(30.0), w, yR(30.0), p)
                p.style = Paint.Style.STROKE; p.color = Color.rgb(160, 110, 255); p.strokeWidth = dp(1).toFloat() * 1.2f
                val rp = Path(); var rs = false
                for (i in off until n) if (!rsi[i].isNaN()) { if (!rs) { rp.moveTo(xI(i), yR(rsi[i])); rs = true } else rp.lineTo(xI(i), yR(rsi[i])) }
                c.drawPath(rp, p); p.style = Paint.Style.FILL
                p.color = muted; p.textSize = dp(9).toFloat()
                c.drawText("RSI(14) " + (if (rsi[n - 1].isNaN()) "—" else String.format(Locale.US, "%.1f", rsi[n - 1])), dp(4).toFloat(), rsiTop - dp(2), p)
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(-1, dp(300))
        liveChart = v
        parent.addView(v)
    }

    private fun rounded(color: Int, radiusDp: Int = 16, strokeColor: Int = line): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor) }
    private fun cardBox(pad: Int = 14): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad - 3), dp(pad), dp(pad - 3)); background = rounded(card)
    }
    private fun addCard(box: View, top: Int = 5, bottom: Int = 5) {
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(top), 0, dp(bottom)) })
    }
    private fun pill(label: String, fg: Int, bgc: Int): TextView = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(12), dp(5), dp(12), dp(5)); background = rounded(bgc, 20, Color.TRANSPARENT)
    }
    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE); minHeight = 0; minimumHeight = 0
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(blue, 12, Color.TRANSPARENT); setOnClickListener { action() }
    }
    private fun chipRow(options: List<String>, selectedIdx: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(8)) }
        options.forEachIndexed { i, label ->
            val sel = i == selectedIdx
            row.addView(pill(label, if (sel) Color.WHITE else muted, if (sel) blue else Color.rgb(20, 40, 64)).apply { setOnClickListener { onPick(i) } },
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), 0) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }
    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 13f, Color.WHITE, true).apply { setPadding(0, 0, 0, 0) })
        labels.addView(text(subtitle, 11f, muted, false).apply { setPadding(0, 0, 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> onChange(on) } })
        return row
    }
    private fun cardBlock(title: String, body: String) {
        val box = cardBox(); box.addView(text(title, 14f, Color.WHITE, true)); box.addView(text(body, 12f, muted, false)); addCard(box)
    }
    private fun panel(title: String, subtitle: String) {
        content.addView(text(title, 21f, Color.WHITE, true)); content.addView(text(subtitle, 11f, muted, false)); content.addView(View(this), LinearLayout.LayoutParams(1, dp(7)))
    }
    private fun actionButton(label: String, action: () -> Unit) {
        content.addView(Button(this).apply { text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE); background = rounded(blue, 12, Color.TRANSPARENT); setOnClickListener { action() } },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) })
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4))
    }

    @Deprecated("Deprecated in Android API")
    private fun openBackupFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")) } catch (_: Exception) {}
        startActivityForResult(intent, backupPicker)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == backupPicker && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, NativeTradingEngine.setBackupFolder(uri), Toast.LENGTH_LONG).show(); updateScreen()
        }
        if(requestCode==backupFilePicker&&resultCode==RESULT_OK){
            val uri=data?.data?:return
            Toast.makeText(this,NativeTradingEngine.importUserBackup(uri),Toast.LENGTH_LONG).show();updateScreen()
        }
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) aiInput?.setText(spoken)
        }
    }

    override fun onDestroy() { handler.removeCallbacks(refreshRunnable); super.onDestroy() }
}
