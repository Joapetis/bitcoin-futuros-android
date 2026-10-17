package com.joapetis.bitcoinfuturos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SwingSignal(val side: String, val regime: String, val price: Double, val atr: Double, val stopDistance: Double, val targetDistance: Double, val candleTime: Long)

internal object BitcoinSwingStrategy {
    fun evaluate(c15: List<CandleSnapshot>, c1h: List<CandleSnapshot>, c4h: List<CandleSnapshot>): SwingSignal? {
        if (c15.size < 60 || c1h.size < 60 || c4h.size < 40) return null
        val closes = c15.map { it.close }
        val close = closes.lastOrNull() ?: return null
        if (close <= 0.0) return null
        val ema20 = ema(closes, 20); val ema50 = ema(closes, 50)
        val h1 = c1h.map { it.close }; val h4 = c4h.map { it.close }
        val h1Trend = ema(h1, 20) - ema(h1, 50)
        val h4Trend = ema(h4, 20) - ema(h4, 40)
        val atr = atr(c15, 14)
        if (atr <= 0.0) return null
        val rsi = rsi(closes, 14)
        val recent = c15.takeLast(20)
        val upper = recent.maxOf { it.high }; val lower = recent.minOf { it.low }
        val mean = closes.takeLast(20).average()
        val sd = kotlin.math.sqrt(closes.takeLast(20).map { (it - mean) * (it - mean) }.average())
        val bandUpper = mean + 2.0 * sd; val bandLower = mean - 2.0 * sd
        val rangeBps = (upper - lower) / close * 10000.0
        val trend = abs((ema20 - ema50) / close) > 0.0015 && abs(h1Trend / close) > 0.001 && abs(h4Trend / close) > 0.001
        var side: String? = null; var regime = "RANGO"
        if (trend) {
            regime = "TENDENCIA"
            if (ema20 > ema50 && h1Trend > 0 && h4Trend > 0 && close >= c15[c15.size - 2].high) side = "LONG"
            if (ema20 < ema50 && h1Trend < 0 && h4Trend < 0 && close <= c15[c15.size - 2].low) side = "SHORT"
        } else if (rangeBps < 450.0) {
            // Mean reversion is enabled only when higher-timeframe trend strength is low.
            if (close < bandLower && rsi < 32.0 && h1Trend / close > -0.0008) side = "LONG"
            if (close > bandUpper && rsi > 68.0 && h1Trend / close < 0.0008) side = "SHORT"
        }
        val chosen = side ?: return null
        val stop = max(atr * 2.2, close * 0.004)
        val target = max(atr * 3.2, close * 0.008)
        return SwingSignal(chosen, regime, close, atr, stop, target, c15.last().time)
    }
    private fun ema(v: List<Double>, n: Int): Double {
        if (v.isEmpty()) return 0.0
        val k = 2.0 / (n + 1.0); var e = v.first()
        for (x in v.drop(1)) e = x * k + e * (1.0 - k)
        return e
    }
    private fun rsi(v: List<Double>, n: Int): Double {
        if (v.size <= n) return 50.0
        var up = 0.0; var down = 0.0
        for (i in v.size - n until v.size) { val d = v[i] - v[i - 1]; if (d > 0) up += d else down -= d }
        if (down <= 1e-12) return 100.0
        return 100.0 - 100.0 / (1.0 + up / down)
    }
    private fun atr(c: List<CandleSnapshot>, n: Int): Double {
        if (c.size <= n) return 0.0
        val ranges = (c.size - n until c.size).map { i ->
            val x = c[i]; val prev = c[i - 1].close
            max(x.high - x.low, max(abs(x.high - prev), abs(x.low - prev)))
        }
        return ranges.average()
    }
}