package com.joapetis.bitcoinfuturos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(7, 11, 17)
    private val card = Color.rgb(17, 25, 37)
    private val accent = Color.rgb(42, 214, 170)
    private val muted = Color.rgb(142, 157, 177)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private var page = "Inicio"
    private var selected = "BTC-USDT"
    private val money = { v: Double -> String.format(Locale.US, "%.2f USDT", v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        NativeTradingEngine.init(this)
        requestNotificationPermission()
        startService(Intent(this, BotKeepAliveService::class.java).setAction(BotKeepAliveService.ACTION_START))
        render()
        handler.post(refreshRunnable)
    }
    private val refreshRunnable = object : Runnable {
        override fun run() { if (::root.isInitialized) updateScreen(); handler.postDelayed(this, 3000) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 410)
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val top = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,18,20,10) }
        top.addView(text("CRYPTO FUTURES", 22f, Color.WHITE, true))
        top.addView(text("ALPHA 0.1  ·  SIMULADOR NATIVO", 11f, muted, true))
        summary = text("Preparando los 10 bots…", 13f, accent, true)
        top.addView(summary)
        root.addView(top)
        val nav = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(8,6,8,6) }
        listOf("Inicio","Mercados","Operaciones","Historial","Ajustes").forEach { name ->
            val b=Button(this).apply { text=name; textSize=10f; isAllCaps=false; setTextColor(if(page==name) accent else Color.WHITE); setBackgroundColor(card); setOnClickListener { page=name; render() } }
            nav.addView(b, LinearLayout.LayoutParams(0,48,1f))
        }
        root.addView(nav)
        val scroll=ScrollView(this).apply { isFillViewport=true }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,8,16,28) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        updateScreen()
    }

    private fun updateScreen() {
        if (!::content.isInitialized) return
        val bots=NativeTradingEngine.snapshots()
        val pnl=bots.sumOf { it.pnl }
        val equity=1000.0+pnl
        summary.text="P/L simulado: ${money(pnl)}   ·   Capital: ${money(equity)}"
        content.removeAllViews()
        when(page) {
            "Inicio" -> {
                panel("Resumen de cartera", "10 bots independientes · 100 USDT iniciales por bot · solo simulación")
                content.addView(text("Resultado total: ${money(pnl)}",20f,if(pnl>=0) accent else Color.RED,true))
                content.addView(text("Capital base: 1.000 USDT",14f,Color.WHITE,false))
                content.addView(text("Ejecución: servicio Android en segundo plano",13f,muted,false))
                content.addView(text("Estrategia: tendencia EMA, momentum, RSI contrarian en rango, ATR y aprendizaje online conservador.",13f,muted,false))
                bots.forEach { botCard(it) }
            }
            "Mercados" -> {
                panel("Mercados", "Toca un activo para abrir su ficha")
                bots.forEach { b ->
                    val row=button("${b.symbol}    ${if(b.price>0) String.format(Locale.US,"%,.4f",b.price) else "Cargando precio…"}    ${b.position}")
                    row.setOnClickListener { selected=b.symbol; page="Detalle"; render() }
                    content.addView(row)
                }
            }
            "Detalle" -> {
                val b=bots.firstOrNull { it.symbol==selected } ?: bots.first()
                val back=button("‹  Volver a mercados"); back.setOnClickListener { page="Mercados"; render() }; content.addView(back)
                panel(b.symbol, b.status)
                content.addView(text("Precio: ${if(b.price>0) String.format(Locale.US,"%,.6f",b.price) else "sin datos"}",18f,Color.WHITE,true))
                content.addView(text("Balance: ${money(b.balance)}  ·  P/L: ${money(b.pnl)}",15f,accent,true))
                content.addView(text("Posición: ${b.position}  ·  Entrada: ${if(b.entry>0) String.format(Locale.US,"%.6f",b.entry) else "—"}",14f,Color.WHITE,false))
                content.addView(text("Operaciones cerradas: ${b.trades}  ·  Aciertos: ${b.wins}  ·  Aprendizajes: ${b.learned}",13f,muted,false))
                controls(b.symbol,b.risk,b.leverage)
            }
            "Operaciones" -> {
                panel("Operaciones abiertas", "Posiciones simuladas, stop y objetivo calculados con ATR")
                bots.filter { it.position!="FLAT" }.forEach { botCard(it) }
                if(bots.none { it.position!="FLAT" }) content.addView(text("No hay posiciones abiertas.",14f,muted,false))
            }
            "Historial" -> {
                panel("Historial y aprendizaje", "Los cierres, pesos del modelo y balances se guardan en el dispositivo.")
                bots.forEach { content.addView(text("${it.symbol}: ${it.trades} cierres · ${it.learned} actualizaciones · balance ${money(it.balance)}",14f,Color.WHITE,false)) }
                content.addView(text("Carpeta interna: ${NativeTradingEngine.storagePath()}",11f,muted,false))
                content.addView(text("También se escribe copia y registro CSV en Android/data/com.joapetis.bitcoinfuturos/files/CryptoFutures cuando Android lo permite.",11f,muted,false))
                content.addView(text(NativeTradingEngine.lastError(),12f,Color.YELLOW,false))
            }
            "Ajustes" -> {
                panel("Ajustes", "Los cambios se guardan inmediatamente y no reinician el aprendizaje.")
                val spinner=Spinner(this)
                val opts=listOf("BTC-USDT","ETH-USDT","SOL-USDT","XRP-USDT","DOGE-USDT","BNB-USDT","LINK-USDT","ADA-USDT","AVAX-USDT","SUI-USDT")
                spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,opts)
                spinner.setSelection(opts.indexOf(selected).coerceAtLeast(0))
                spinner.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: View?,position:Int,id:Long) { selected=opts[position] }
                }
                content.addView(spinner)
                val b=bots.firstOrNull { it.symbol==selected } ?: bots.first()
                controls(b.symbol,b.risk,b.leverage)
                content.addView(text("El simulador usa velas públicas de OKX, intervalo 1H. El procesamiento y aprendizaje se ejecutan en el teléfono; no se usan claves privadas ni se envían órdenes reales.",13f,muted,false))
                content.addView(text("No se puede garantizar ejecución continua si Android fuerza la detención, se agota la batería o se pierde Internet. Desactiva la optimización de batería para la app si quieres mayor continuidad.",12f,muted,false))
            }
        }
    }

    private fun controls(symbol:String,risk:Double,leverage:Int) {
        content.addView(text("Riesgo por operación",13f,Color.WHITE,true))
        val riskBar=SeekBar(this).apply { max=100; progress=risk.toInt().coerceIn(1,100) }
        val riskLabel=text("${risk.toInt()}% del balance como riesgo teórico",12f,muted,false)
        content.addView(riskBar); content.addView(riskLabel)
        riskBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean) { riskLabel.text="${p.coerceAtLeast(1)}% del balance como riesgo teórico"; if(u) NativeTradingEngine.setRisk(symbol,p.coerceAtLeast(1).toDouble()) }
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
        })
        content.addView(text("Apalancamiento simulado",13f,Color.WHITE,true))
        val levs=listOf(1,2,3,5,10,20,50,75,100)
        val spinner=Spinner(this)
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,levs.map { "x$it" })
        spinner.setSelection(levs.indexOf(leverage).coerceAtLeast(0))
        spinner.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) { NativeTradingEngine.setLeverage(symbol,levs[position]) }
        }
        content.addView(spinner)
    }

    private fun botCard(b:BotSnapshot) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12); setBackgroundColor(card) }
        val lp=LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,6,0,6)
        box.addView(text("${b.symbol}     ${b.position}",16f,Color.WHITE,true))
        box.addView(text(if(b.price>0) String.format(Locale.US,"Precio  %, .6f",b.price).replace(" ","") else "Precio esperando conexión",13f,muted,false))
        box.addView(text("Balance ${money(b.balance)}    P/L ${money(b.pnl)}",14f,if(b.pnl>=0) accent else Color.RED,true))
        box.addView(text("${b.status}  ·  ${b.trades} cierres  ·  IA: ${b.learned}",11f,muted,false))
        box.setOnClickListener { selected=b.symbol; page="Detalle"; render() }
        content.addView(box,lp)
    }
    private fun panel(title:String,subtitle:String) {
        content.addView(text(title,20f,Color.WHITE,true))
        content.addView(text(subtitle,12f,muted,false))
        val spacer=View(this); content.addView(spacer,LinearLayout.LayoutParams(1,8))
    }
    private fun text(value:String,size:Float,color:Int,bold:Boolean):TextView = TextView(this).apply {
        text=value; textSize=size; setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); setPadding(0,5,0,5)
    }
    private fun button(value:String):Button = Button(this).apply { text=value; textSize=13f; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(card) }
    override fun onDestroy() { handler.removeCallbacks(refreshRunnable); super.onDestroy() }
}
