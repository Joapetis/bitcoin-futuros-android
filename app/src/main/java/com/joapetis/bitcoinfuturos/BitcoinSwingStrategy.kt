package com.joapetis.bitcoinfuturos
import kotlin.math.abs
import kotlin.math.max
internal data class SwingSignal(val side:String,val regime:String,val price:Double,val atr:Double,val stopDistance:Double,val targetDistance:Double,val candleTime:Long)
internal object BitcoinSwingStrategy {
    // 15m decide la entrada; 1H/4H dan contexto, sin veto obligatorio. Solo velas cerradas.
    fun evaluate(c15:List<CandleSnapshot>,c1h:List<CandleSnapshot>,c4h:List<CandleSnapshot>):SwingSignal? {
        if(c15.size<60||c1h.size<25||c4h.size<15)return null
        val closes=c15.map{it.close};val close=closes.lastOrNull()?.takeIf{it>0.0}?:return null
        val e20=ema(closes,20);val e50=ema(closes,50);val h1=c1h.map{it.close};val h4=c4h.map{it.close}
        val p1=h1.lastOrNull()?.takeIf{it>0.0}?:return null;val p4=h4.lastOrNull()?.takeIf{it>0.0}?:return null
        val r1=(ema(h1,20)-ema(h1,minOf(50,h1.size)))/p1
        val r4=(ema(h4,minOf(20,h4.size))-ema(h4,minOf(40,h4.size)))/p4
        val local=(e20-e50)/close;val atr=atr(c15,14);if(atr<=0.0)return null
        val rsi=rsi(closes,14);val recent=c15.takeLast(20)
        val range=(recent.maxOf{it.high}-recent.minOf{it.low})/close*10000.0
        val mean=closes.takeLast(20).average();val sd=kotlin.math.sqrt(closes.takeLast(20).map{(it-mean)*(it-mean)}.average())
        val longOk=r1> -0.0015&&r4> -0.002;val shortOk=r1<0.0015&&r4<0.002
        var side:String?=null;var regime="TENDENCIA"
        if(abs(local)>0.0008){val prev=c15[c15.size-2]
            if(local>0&&close>e20&&close>=prev.high&&longOk)side="LONG"
            if(local<0&&close<e20&&close<=prev.low&&shortOk)side="SHORT"
        }
        if(side==null&&range<650&&abs(r1)<0.004&&abs(r4)<0.006){
            if(close<mean-2*sd&&rsi<35&&longOk)side="LONG"
            if(close>mean+2*sd&&rsi>65&&shortOk)side="SHORT"
            if(side!=null)regime="RANGO"
        }
        val chosen=side?:return null
        return SwingSignal(chosen,regime,close,atr,max(atr*2.2,close*0.004),max(atr*3.2,close*0.008),c15.last().time)
    }
    private fun ema(v:List<Double>,n:Int):Double{if(v.isEmpty())return 0.0;val k=2.0/(n.coerceIn(1,v.size)+1.0);var e=v.first();for(x in v.drop(1))e=x*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int):Double{if(v.size<=n)return 50.0;var up=0.0;var down=0.0;for(i in v.size-n until v.size){val d=v[i]-v[i-1];if(d>0)up+=d else down-=d};if(down<=1e-12)return 100.0;return 100.0-100.0/(1.0+up/down)}
    private fun atr(c:List<CandleSnapshot>,n:Int):Double{if(c.size<=n)return 0.0;return(c.size-n until c.size).map{i->val x=c[i];val p=c[i-1].close;max(x.high-x.low,max(abs(x.high-p),abs(x.low-p)))}.average()}
}