package com.joapetis.bitcoinfuturos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import kotlin.math.sin

class MainActivity : Activity() {
    private val bg=Color.rgb(5,10,17); private val card=Color.rgb(13,23,36)
    private val blue=Color.rgb(30,132,255); private val green=Color.rgb(0,220,153); private val muted=Color.rgb(137,154,174)
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var root:LinearLayout; private lateinit var content:LinearLayout; private lateinit var summary:TextView
    private var detailPrice:TextView? = null; private var liveChart:View? = null
    private var page="Inicio"; private var selected="BTC-USDT"
    private val symbols=listOf("BTC-USDT","ETH-USDT","SOL-USDT","XRP-USDT","DOGE-USDT","BNB-USDT","LINK-USDT","ADA-USDT","AVAX-USDT","SUI-USDT")
    private fun dp(value:Int):Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun money(v:Double)=String.format(Locale.US,"%,.2f USDT",v)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); window.statusBarColor=bg; window.navigationBarColor=bg
        NativeTradingEngine.init(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),410)
        val service=Intent(this,BotKeepAliveService::class.java).setAction(BotKeepAliveService.ACTION_START)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
        render(); handler.post(refreshRunnable)
    }
    private val refreshRunnable=object:Runnable { override fun run(){ if(::root.isInitialized){ updateSummary(); detailPrice?.let { priceView -> NativeTradingEngine.snapshots().firstOrNull{it.symbol==selected}?.let { b -> priceView.text=if(b.price>0)String.format(Locale.US,"%,.2f",b.price) else "Esperando precio…" } }; liveChart?.invalidate() }; handler.postDelayed(this,4000) } }

    private fun render() {
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(8))}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(text("🔷  Crypto Futures",20f,Color.WHITE,true)); brand.addView(text("       Simulador de Futuros",11f,muted,false))
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f)); header.addView(text("♧  ◉",20f,Color.WHITE,true)); root.addView(header)
        summary=text("",11f,green,true).apply{setPadding(dp(12),dp(2),dp(12),dp(8));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}; root.addView(summary)
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(6),dp(14),dp(14))}; scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply{tag="bottom_navigation";orientation=LinearLayout.HORIZONTAL;setPadding(dp(4),dp(5),dp(4),dp(5));setBackgroundColor(Color.rgb(7,14,23))}
        listOf("Inicio","Mercados","Operaciones","Historial","Ajustes").forEach { name ->
            val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(1),dp(5),dp(1),dp(3));setOnClickListener{page=name;render()}}
            item.addView(text(when(name){"Inicio"->"⌂";"Mercados"->"▥";"Operaciones"->"▣";"Historial"->"◷";else->"⚙"},20f,if(page==name)blue else muted,true))
            item.addView(text(name,9f,if(page==name)blue else muted,false)); nav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(nav)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            nav.setPadding(dp(4), dp(5), dp(4), insets.systemWindowInsetBottom + dp(5))
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        updateScreen()
    }
    private fun updateSummary() {
        if(!::summary.isInitialized)return
        val pnl=NativeTradingEngine.snapshots().sumOf{it.pnl}
        summary.text="P/L SIMULADO  "+money(pnl)+"     ·     CAPITAL "+money(1000.0+pnl)
    }
    private fun updateScreen() {
        if(!::content.isInitialized)return
        val bots=NativeTradingEngine.snapshots(); val pnl=bots.sumOf{it.pnl}
        updateSummary()
        content.removeAllViews()
        when(page) {
            "Inicio" -> {
                overviewCard(pnl, 1000.0 + pnl)
                val best=bots.maxByOrNull{it.pnl}
                bestCard(best?.symbol ?: "BTC-USDT", best?.pnl ?: 0.0)
                val stats=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                listOf(bots.size.toString()+"|Bots activos",bots.count{it.pnl>0}.toString()+"|Ganadores",bots.count{it.pnl<0}.toString()+"|Perdedores").forEachIndexed{i,s0->
                    val parts=s0.split("|"); val c=if(i==1)green else if(i==2)Color.rgb(255,64,75) else Color.WHITE
                    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(10),dp(6),dp(10));setBackgroundColor(card);addView(text(parts[0],20f,c,true));addView(text(parts[1],10f,muted,false))}
                    stats.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(2),dp(4),dp(2),dp(4))})
                }
                content.addView(stats)
                panel("Mercados","Toca una criptomoneda para abrir su ficha")
                bots.take(5).forEach{marketRow(it)}
                actionButton("Ver todos los mercados"){page="Mercados";render()}
            }
            "Mercados" -> {
                panel("Mercados","Todo     Futuros     Top     DeFi     Memes")
                val search=EditText(this).apply{hint="Buscar criptomoneda…";setHintTextColor(muted);setTextColor(Color.WHITE);textSize=14f;setSingleLine();setPadding(dp(14),dp(8),dp(14),dp(8));setBackgroundColor(card)}
                content.addView(search); val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; content.addView(list)
                fun fill(q:String){list.removeAllViews();bots.filter{it.symbol.contains(q,true)}.forEach{marketRow(it,list)}}
                fill(""); search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){fill(s.toString())};override fun afterTextChanged(s:android.text.Editable?){}})
            }
            "Detalle" -> {
                val b=bots.firstOrNull{it.symbol==selected}?:bots.first()
                content.addView(text("‹   "+b.symbol.replace("-","/")+"     ★   ⋯",18f,Color.WHITE,true))
                detailPrice=text(if(b.price>0)String.format(Locale.US,"%,.2f",b.price) else "Cargando precio…",27f,green,true); content.addView(detailPrice)
                content.addView(text("Precio de mercado · intervalo 1H",11f,muted,false)); chart()
                panel("Estrategia independiente",NativeTradingEngine.strategyName(b.symbol)+"\n"+b.status); cardBlock("Balance del bot",money(b.balance)+"    P/L "+money(b.pnl))
                cardBlock("Operación actual","Entrada "+(if(b.entry>0)String.format(Locale.US,"%.5f",b.entry)else "—")+"    ·    "+b.trades+" cierres")
                controls(b.symbol,b.risk,b.leverage); actionButton("Volver a mercados"){page="Mercados";render()}
            }
            "Operaciones" -> {
                panel("Operaciones abiertas","Posiciones abiertas     ·     Historial")
                val open=bots.filter{it.position!="FLAT"}; cardBlock("Vista general","P/L: "+money(pnl)+"\nBalance: "+money(bots.sumOf{it.balance})+"\nPosiciones abiertas: "+open.size)
                if(open.isEmpty())content.addView(text("No hay posiciones abiertas ahora mismo.",14f,muted,false))
                open.forEach{cardBlock(it.symbol.replace("-","/"),it.position+"    "+money(it.pnl)+"\nEntrada: "+(if(it.entry>0)String.format(Locale.US,"%.5f",it.entry)else "—")+"\n"+it.status)}
            }
            "Historial" -> {
                panel("Historial","Operaciones simuladas y aprendizaje")
                bots.forEach{cardBlock(it.symbol+" · "+NativeTradingEngine.strategyName(it.symbol),it.trades.toString()+" cierres · "+it.wins+" aciertos\nBalance "+money(it.balance)+" · P/L "+money(it.pnl)+" · Aprendizaje "+it.learned)}
                content.addView(text("Datos guardados en: "+NativeTradingEngine.storagePath(),10f,muted,false)); content.addView(text(NativeTradingEngine.lastError(),12f,Color.YELLOW,false))
            }
            "Ajustes" -> {
                panel("Ajustes","Configuración de la aplicación")
                cardBlock("Simulación en segundo plano","Mantiene activos los bots mediante el servicio Android")
                content.addView(text("Bot a configurar",13f,Color.WHITE,true))
                val spinner=Spinner(this); spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,symbols); spinner.setSelection(symbols.indexOf(selected).coerceAtLeast(0))
                spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){selected=symbols[pos]}}
                content.addView(spinner); val b=bots.firstOrNull{it.symbol==selected}?:bots.first(); controls(b.symbol,b.risk,b.leverage)
                cardBlock("General","Modo oscuro · Español\nDatos públicos de mercado · Sin claves privadas\nSolo simulación: no se envían órdenes reales.")
                cardBlock("Continuidad","Android puede limitar procesos en segundo plano. Revisa la optimización de batería para mejorar la continuidad.")
            }
        }
    }
    private fun marketRow(b:BotSnapshot,parent:LinearLayout=content){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(10));setBackgroundColor(bg);setOnClickListener{selected=b.symbol;page="Detalle";render()}}
        row.addView(text(when(b.symbol){"BTC-USDT"->"₿";"ETH-USDT"->"◆";"SOL-USDT"->"≋";"XRP-USDT"->"✕";"DOGE-USDT"->"Ð";else->"●"},24f,when(b.symbol){"BTC-USDT"->Color.rgb(255,159,28);"ETH-USDT"->Color.rgb(99,126,234);"SOL-USDT"->Color.MAGENTA;else->blue},true))
        val names=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        names.addView(text(b.symbol.substringBefore("-"),14f,Color.WHITE,true)); names.addView(text(when(b.symbol){"BTC-USDT"->"Bitcoin";"ETH-USDT"->"Ethereum";"SOL-USDT"->"Solana";"DOGE-USDT"->"Dogecoin";else->b.symbol.substringBefore("-")},10f,muted,false))
        row.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(sparkline(b.symbol),LinearLayout.LayoutParams(dp(42),dp(24)).apply{setMargins(dp(5),0,dp(7),0)})
        val quote=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END}
        quote.addView(text(if(b.price>0)String.format(Locale.US,"%,.4f",b.price)else "—",12f,Color.WHITE,true))
        val change=NativeTradingEngine.marketChange24h(b.symbol)
        quote.addView(text(String.format(Locale.US,"%+.2f%%",change),11f,if(change>=0)green else Color.RED,true))
        row.addView(quote,LinearLayout.LayoutParams(-2,-2))
        parent.addView(row)
        parent.addView(View(this).apply{setBackgroundColor(Color.rgb(25,39,53))},LinearLayout.LayoutParams(-1,1))
    }
    private fun sparkline(symbol:String):View{
        val data=NativeTradingEngine.candles(symbol).takeLast(24)
        return object:View(this){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas){
                if(data.size<2)return
                val lo=data.minOf{it.close};val hi=data.maxOf{it.close};val span=(hi-lo).takeIf{it>0}?:1.0
                p.color=if(data.last().close>=data.first().close)green else Color.rgb(255,77,90)
                p.strokeWidth=dp(1.5).toFloat();p.style=Paint.Style.STROKE
                val path=Path()
                data.forEachIndexed{i,k->
                    val x=width.toFloat()*i/(data.size-1)
                    val y=height.toFloat()*(0.92f-((k.close-lo)/span).toFloat()*0.82f)
                    if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
                }
                c.drawPath(path,p);p.style=Paint.Style.FILL
            }
        }
    }
    private fun controls(symbol:String,risk:Double,leverage:Int){
        cardBlock("Riesgo por operación",risk.toInt().toString()+"% del balance como riesgo teórico")
        val bar=SeekBar(this).apply{max=20;progress=risk.toInt().coerceIn(1,20)}; content.addView(bar)
        bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){if(u)NativeTradingEngine.setRisk(symbol,p.coerceAtLeast(1).toDouble())};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})
        content.addView(text("Apalancamiento simulado",13f,Color.WHITE,true)); val levs=listOf(1,2,3,5,10,20,50,75,100); val sp=Spinner(this)
        sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,levs.map{"x"+it});sp.setSelection(levs.indexOf(leverage).coerceAtLeast(0))
        sp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){NativeTradingEngine.setLeverage(symbol,levs[pos])}}
        content.addView(sp)
    }
    private fun chart(){
        val candles=NativeTradingEngine.candles(selected).takeLast(55)
        val v=object:View(this){
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas){
                val w=width.toFloat(); val h=height.toFloat()
                if(w<=0f||h<=0f)return
                p.style=Paint.Style.FILL
                p.color=Color.rgb(7,14,23); c.drawRect(0f,0f,w,h,p)
                p.color=Color.rgb(25,39,53);p.strokeWidth=dp(1).toFloat()
                for(i in 0..4)c.drawLine(0f,h*i/4,w,h*i/4,p)
                for(i in 0..5)c.drawLine(w*i/5,0f,w*i/5,h,p)
                if(candles.size<2){
                    p.color=muted;p.textSize=dp(12).toFloat()
                    c.drawText("Esperando datos reales de velas…",dp(12).toFloat(),h/2,p);return
                }
                val minP=candles.minOf{it.low}; val maxP=candles.maxOf{it.high}
                val range=(maxP-minP).takeIf{it>0}?:1.0
                val plotBottom=h*0.78f; val volumeTop=h*0.82f; val volumeBottom=h*0.97f
                val maxVol=candles.maxOf{it.volume}.takeIf{it>0}?:1.0
                val slot=w/candles.size; val bodyW=(slot*0.58f).coerceAtLeast(dp(2).toFloat())
                candles.forEachIndexed{i,k->
                    val x=slot*(i+0.5f)
                    val yHigh=plotBottom-((k.high-minP)/range).toFloat()*plotBottom
                    val yLow=plotBottom-((k.low-minP)/range).toFloat()*plotBottom
                    val yOpen=plotBottom-((k.open-minP)/range).toFloat()*plotBottom
                    val yClose=plotBottom-((k.close-minP)/range).toFloat()*plotBottom
                    val up=k.close>=k.open
                    p.color=if(up)green else Color.rgb(255,77,90);p.strokeWidth=dp(1).toFloat()
                    c.drawLine(x,yHigh,x,yLow,p)
                    c.drawRect(x-bodyW/2,minOf(yOpen,yClose),x+bodyW/2,maxOf(yOpen,yClose).coerceAtLeast(minOf(yOpen,yClose)+dp(1)),p)
                    val vh=((k.volume/maxVol)*(volumeBottom-volumeTop)).toFloat()
                    c.drawRect(x-bodyW/2,volumeBottom-vh,x+bodyW/2,volumeBottom,p)
                }
                p.color=Color.rgb(30,132,255);p.style=Paint.Style.STROKE;p.strokeWidth=dp(1).toFloat()
                val emaPath=Path()
                candles.forEachIndexed{i,k->
                    val x=slot*(i+0.5f);val y=plotBottom-((k.close-minP)/range).toFloat()*plotBottom
                    if(i==0)emaPath.moveTo(x,y) else emaPath.lineTo(x,y)
                }
                c.drawPath(emaPath,p);p.style=Paint.Style.FILL
                p.color=muted;p.textSize=dp(9).toFloat()
                c.drawText(String.format(Locale.US,"%.4f",maxP),dp(3).toFloat(),dp(12).toFloat(),p)
                c.drawText(String.format(Locale.US,"%.4f",minP),dp(3).toFloat(),plotBottom-dp(3).toFloat(),p)
            }
        }
        v.layoutParams=LinearLayout.LayoutParams(-1,dp(260))
        content.addView(v)
        content.addView(text("● Alcista     ● Bajista                 VOLUMEN · VELAS 1H",10f,muted,false))
    }
    private fun overviewCard(pnl:Double,capital:Double){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));setBackgroundColor(card)}
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        titleRow.addView(text("Ganancia/Pérdida Total",15f,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(text("×",14f,muted,false))
        box.addView(titleRow)
        box.addView(text("Capital total: "+money(capital),11f,muted,false))
        val profit=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        profit.addView(text((if(pnl>=0) "+" else "")+money(pnl),26f,if(pnl>=0)green else Color.rgb(255,64,75),true))
        profit.addView(Space(this),LinearLayout.LayoutParams(dp(8),dp(1)))
        profit.addView(text(String.format(Locale.US,"%+.2f%%",pnl/1000.0*100),12f,if(pnl>=0)green else Color.rgb(255,64,75),true))
        box.addView(profit)
        content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(4),0,dp(8))})
    }
    private fun bestCard(symbol:String,pnl:Double){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundColor(card)}
        box.addView(text("Mejor rendimiento",13f,Color.WHITE,true))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        row.addView(text(when(symbol){"BTC-USDT"->"₿";"ETH-USDT"->"◆";"SOL-USDT"->"≋";"XRP-USDT"->"✕";"DOGE-USDT"->"Ð";else->"●"},22f,blue,true))
        val name=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0);addView(text(symbol.substringBefore("-"),13f,Color.WHITE,true));addView(text(String.format(Locale.US,"%+.2f USDT / %+.2f%%",pnl,pnl),11f,if(pnl>=0)green else Color.RED,true))}
        row.addView(name,LinearLayout.LayoutParams(0,-2,1f));row.addView(text("›",22f,muted,false))
        box.addView(row);content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(4),0,dp(6))})
    }
    private fun cardBlock(title:String,body:String){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(card);addView(text(title,14f,Color.WHITE,true));addView(text(body,12f,muted,false))};content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))})}
    private fun panel(title:String,subtitle:String){content.addView(text(title,21f,Color.WHITE,true));content.addView(text(subtitle,11f,muted,false));content.addView(View(this),LinearLayout.LayoutParams(1,dp(7)))}
    private fun actionButton(label:String,action:()->Unit){content.addView(Button(this).apply{text=label;isAllCaps=false;textSize=13f;setTextColor(Color.WHITE);setBackgroundColor(blue);setOnClickListener{action()}},LinearLayout.LayoutParams(-1,-2))}
    private fun text(value:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{text=value;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(4),0,dp(4))}
    override fun onDestroy(){handler.removeCallbacks(refreshRunnable);super.onDestroy()}
}
