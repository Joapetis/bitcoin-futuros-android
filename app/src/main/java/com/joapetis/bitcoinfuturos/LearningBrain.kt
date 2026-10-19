package com.joapetis.bitcoinfuturos

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TradeFeatures(
    val side: String,          // LONG o SHORT
    val imbalance: Double,     // desequilibrio ponderado 10 niveles (-1..1)
    val microEdgeBps: Double,  // microprecio - mid, en bps
    val spreadBps: Double,
    val flow: Double,          // CVD de cinta 30s (-1..1)
    val volBps: Double,        // rango de 60s en bps
    val hourUtc: Int,
    val funding: Double,
    val news: Double,          // sentimiento RSS + Gemini Nano (-1..1)
    val mtfScore: Double = 0.0 // alineación multi-temporal 1m/5m/15m/1H/4H (-1..1)
) {
    private fun sgn(): Double = if (side == "LONG") 1.0 else -1.0

    fun vector(): DoubleArray {
        val s = sgn()
        val ang = 2.0 * PI * hourUtc / 24.0
        return doubleArrayOf(
            1.0,
            (imbalance * s).coerceIn(-1.0, 1.0),
            (microEdgeBps * s).coerceIn(-1.0, 1.0),
            (spreadBps / 2.5).coerceIn(0.0, 2.0),
            (flow * s).coerceIn(-1.0, 1.0),
            (ln(1.0 + volBps) / 5.0).coerceIn(0.0, 1.5),
            sin(ang),
            cos(ang),
            (mtfScore * s).coerceIn(-1.0, 1.0),
            (news * s).coerceIn(-1.0, 1.0)
        )
    }

    fun buckets(): List<String> {
        val s = sgn()
        val aImb = abs(imbalance)
        return listOf(
            "hora:" + (hourUtc / 3),
            "libro:" + (if (aImb < 0.25) "medio" else if (aImb < 0.50) "fuerte" else "extremo"),
            "spread:" + (if (spreadBps <= 0.8) "bajo" else if (spreadBps <= 1.8) "medio" else "alto"),
            "flujo:" + (if (abs(flow) < 0.08) "neutro" else if (flow * s > 0) "a_favor" else "en_contra"),
            "vol:" + (if (volBps < 10.0) "baja" else if (volBps < 35.0) "media" else "alta"),
            "mtf:" + (if (mtfScore * s >= 0.2) "alineado" else if (mtfScore * s <= -0.2) "contra_tendencia" else "mixto")
        )
    }

    fun toJson(): JSONObject = JSONObject().put("side", side).put("imb", imbalance).put("micro", microEdgeBps)
        .put("spread", spreadBps).put("flow", flow).put("vol", volBps).put("hour", hourUtc)
        .put("funding", funding).put("news", news).put("mtf", mtfScore)

    companion object {
        fun fromJson(o: JSONObject?): TradeFeatures? {
            if (o == null || !o.has("side")) return null
            return TradeFeatures(
                o.optString("side", "LONG"), o.optDouble("imb", 0.0), o.optDouble("micro", 0.0),
                o.optDouble("spread", 1.0), o.optDouble("flow", 0.0), o.optDouble("vol", 20.0),
                o.optInt("hour", 12), o.optDouble("funding", 0.0), o.optDouble("news", 0.0),
                o.optDouble("mtf", 0.0)
            )
        }
    }
}

data class AiDecision(
    val allow: Boolean,
    val pWin: Double,
    val reasons: List<String>,
    val warmup: Boolean,
    val customStopRate: Double? = null,
    val customTargetRate: Double? = null,
    val nanoSource: Boolean = false
)

class LearningBrain {
    private class Stat(var wins: Double = 0.0, var losses: Double = 0.0, var edge: Double = 0.0)
    private class Shadow(
        val variantName: String,
        val features: TradeFeatures,
        val entry: Double,
        val startAt: Long,
        val targetRate: Double,
        val stopRate: Double,
        val maxHoldMs: Long
    )

    var imbEntry = 0.16
    var persistEvals = 3
    var targetRate = 0.0046           // 46 bps objetivo base
    var stopRate = 0.0018             // 18 bps stop base (Ratio > 2.5:1 bruto, breakeven ~50%)
    var minEdge = -0.01
    var warmupSamples = 15
    var dailyLossLimit = 0.15
    var mode = "normal"
    val blockedHoursLocal = linkedSetOf<Int>()

    private val protectedBuckets = setOf(
        "spread:bajo", "spread:medio", "flujo:a_favor",
        "libro:fuerte", "libro:extremo", "vol:media", "vol:alta", "mtf:alineado"
    )

    private val w = doubleArrayOf(0.12, 0.55, 0.35, -0.10, 0.42, 0.12, 0.0, 0.0, 0.35, 0.15)
    var samples = 0
        private set
    var realSamples = 0
        private set
    var shadowSamples = 0
        private set
    var lossStreak = 0
        private set
    var bestVariantLabel = "Estándar (SL 18 / TP 46 bps)"
        private set
    private val variantStats = linkedMapOf<String, Stat>()
    private val buckets = linkedMapOf<String, Stat>()
    private val activeBlocks = linkedSetOf<String>()
    private val recent = JSONArray()
    private val lessons = JSONArray()
    private val chat = JSONArray()
    private val shadows = ArrayList<Shadow>()
    private var lastShadowAt = 0L
    private var tunedAt = 0

    fun breakeven(costRate: Double): Double {
        val loss = stopRate + costRate
        val win = max(1e-9, targetRate - costRate)
        return (loss / (win + loss)).coerceIn(0.45, 0.56)
    }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x.coerceIn(-30.0, 30.0)))
    fun predict(f: TradeFeatures): Double = predictVec(f.vector())
    private fun predictVec(v: DoubleArray): Double { var z = 0.0; for (i in v.indices) z += w[i] * v[i]; return sigmoid(z) }
    private fun pct(x: Double) = String.format(Locale.US, "%.0f", x * 100.0)

    fun recentWinRate(): Double {
        if (recent.length() == 0) return 0.56
        var wins = 0
        for (i in 0 until recent.length()) if (recent.optJSONObject(i)?.optBoolean("w") == true) wins++
        return wins.toDouble() / recent.length()
    }

    fun decide(f: TradeFeatures, costRate: Double, nanoDecision: NanoTradeDecision? = null): AiDecision {
        val statP = predict(f)
        val reasons = mutableListOf<String>()
        val s = if (f.side == "LONG") 1.0 else -1.0

        // CVD y MTF son evidencia contextual, no vetos obligatorios de cada entrada.
        // Se conserva únicamente un filtro de volatilidad para evitar setups incapaces de cubrir costes.
        if (f.volBps < 6.5) {
            reasons.add("volatilidad baja (${String.format(Locale.US, "%.1f", f.volBps)} bps)")
        }
        for (b in f.buckets()) {
            if (isBlocking(b, costRate)) reasons.add("regla aprendida: " + describe(b))
        }
        // El warm-up reduce la confianza implícita, pero nunca bloquea indefinidamente
        // las entradas: el modelo puede aprender con resultados reales desde el inicio.
        val warmup = samples < warmupSamples
        val adjustedP = if (warmup) (statP * 0.85 + 0.075).coerceIn(0.05, 0.98) else statP
        val need = (breakeven(costRate) + minEdge).coerceIn(0.48, 0.55)
        if (adjustedP < need - 0.05) {
            reasons.add("modelo estadístico rechaza setup (" + pct(adjustedP) + "% < " + pct(need) + "%)")
        }

        val freshNano = nanoDecision?.takeIf { System.currentTimeMillis() - it.timestamp <= 10_000L }
        if (freshNano != null) {
            val blendedP = (freshNano.confidence * 0.35 + adjustedP * 0.65).coerceIn(0.05, 0.98)
            // Nano solo puede vetar con una opinión muy segura y si el modelo estadístico
            // también estima que la operación no cubre el punto de equilibrio.
            if ((freshNano.action == "HOLD" || freshNano.action != f.side) &&
                freshNano.confidence >= 0.85 && adjustedP < breakeven(costRate)) {
                return AiDecision(false, blendedP, listOf("Filtro de seguridad Nano + estadística: ${freshNano.reason}"), warmup, nanoSource = true)
            }
            if (blendedP < need - 0.05) {
                reasons.add("confianza combinada " + pct(blendedP) + "% < " + pct(need) + "%")
            }
            val safeStop = freshNano.stopRate.coerceIn(0.0018, 0.0032)
            val safeTarget = max(freshNano.targetRate, max(0.0044, safeStop * 2.3))
            return AiDecision(
                allow = reasons.isEmpty(),
                pWin = blendedP,
                reasons = if (reasons.isEmpty()) listOf("Gemini Nano (${pct(blendedP)}%): ${freshNano.reason}") else reasons,
                warmup = warmup,
                customStopRate = safeStop,
                customTargetRate = safeTarget,
                nanoSource = true
            )
        }

        if (adjustedP < need - 0.02) reasons.add("probabilidad estimada " + pct(adjustedP) + "% < " + pct(need) + "%")
        return AiDecision(reasons.isEmpty(), adjustedP, reasons, warmup)
    }

    private fun isBlocking(key: String, costRate: Double): Boolean {
        if (key in protectedBuckets || key.startsWith("hora:")) return false
        val s = buckets[key] ?: return false
        val n = s.wins + s.losses
        if (n < 24.0) return false
        val k = 6.0
        val mean = (s.wins + k * 0.5) / (n + k)
        val sd = sqrt(mean * (1.0 - mean) / (n + k + 1.0))
        return mean + sd < 0.36
    }

    fun riskMultiplier(pWin: Double, costRate: Double): Double {
        var m = ((pWin - breakeven(costRate) + 0.12) / 0.20).coerceIn(0.45, 1.0)
        if (lossStreak >= 3) m *= 0.5
        return m.coerceIn(0.25, 1.0)
    }

    fun isHourBlocked(localHour: Int) = localHour in blockedHoursLocal

    /** Sombra individual continua. */
    fun startShadow(f: TradeFeatures, entry: Double, now: Long): Boolean {
        if (now - lastShadowAt < 4_000L || shadows.size >= 20) return false
        lastShadowAt = now
        shadows.add(Shadow("Base", f, entry, now, targetRate, stopRate, 120_000L))
        return true
    }

    /**
     * Cuando el bot abre una operación real, lanza 4 variantes evolutivas en sombra al mismo tiempo:
     * 1) Francotirador (SL corto 14 bps / TP rápido 36 bps)
     * 2) Tendencial (SL amplio 24 bps / TP largo 65 bps)
     * 3) Equilibrada (SL 18 bps / TP 46 bps)
     * 4) Contrarreloj (cierre rápido 60s)
     * Todas alimentan el aprendizaje conjunto y auto-ajustan los parámetros del bot real hacia la variante más rentable.
     */
    fun spawnShadowVariantsOnRealOpen(f: TradeFeatures, entry: Double, now: Long) {
        if (shadows.size > 16) return
        shadows.add(Shadow("Francotirador", f, entry, now, 0.0036, 0.0014, 90_000L))
        shadows.add(Shadow("Tendencial", f, entry, now, 0.0065, 0.0024, 180_000L))
        shadows.add(Shadow("Equilibrada", f, entry, now, 0.0046, 0.0018, 120_000L))
        shadows.add(Shadow("Impulso60s", f, entry, now, 0.0038, 0.0016, 60_000L))
    }

    fun tickShadows(bid: Double, ask: Double, now: Long, costRate: Double, defaultHoldMs: Long) {
        val it = shadows.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val long = s.features.side == "LONG"
            val mark = if (long) bid else ask
            val move = (if (long) mark - s.entry else s.entry - mark) / s.entry
            var reason: String? = null
            var net = 0.0
            if (move >= s.targetRate) { reason = "Objetivo"; net = s.targetRate - costRate * 0.6 }
            else if (move <= -s.stopRate) { reason = "Stop"; net = -s.stopRate - costRate * 0.6 }
            else if (now - s.startAt >= s.maxHoldMs) {
                reason = "Tiempo máximo"
                net = move - costRate * 0.4
            }
            if (reason != null) {
                it.remove()
                val vs = variantStats.getOrPut(s.variantName) { Stat() }
                if (net > 0.0) vs.wins += 1.0 else vs.losses += 1.0
                vs.edge += net
                updateBestVariant(costRate)
                record(s.features, net > 0.0, net, false, reason, now - s.startAt, costRate)
            }
        }
    }

    private fun updateBestVariant(costRate: Double) {
        val best = variantStats.entries.filter { it.value.wins + it.value.losses >= 6.0 }
            .maxByOrNull { it.value.edge / (it.value.wins + it.value.losses) } ?: return
        when (best.key) {
            "Francotirador" -> { stopRate = 0.0016; targetRate = 0.0040; bestVariantLabel = "Francotirador (SL 16 / TP 40 bps)" }
            "Tendencial" -> { stopRate = 0.0022; targetRate = 0.0058; bestVariantLabel = "Tendencial (SL 22 / TP 58 bps)" }
            "Equilibrada" -> { stopRate = 0.0018; targetRate = 0.0046; bestVariantLabel = "Equilibrada (SL 18 / TP 46 bps)" }
            "Impulso60s" -> { stopRate = 0.0017; targetRate = 0.0042; bestVariantLabel = "Impulso Rápido (SL 17 / TP 42 bps)" }
        }
        clamp(costRate)
    }

    fun onRealTrade(f: TradeFeatures, net: Double, notional: Double, reason: String, durationMs: Long, costRate: Double): String {
        val rate = if (notional > 0.0) net / notional else 0.0
        val won = net > 0.0
        record(f, won, rate, true, reason, durationMs, costRate)
        if (won) {
            lossStreak = 0
            val winText = "Acierto en ${f.side} ($reason, ${String.format(Locale.US, "%+.2f", net)} USDT): L2 ${String.format(Locale.US, "%+.2f", f.imbalance)}, CVD ${String.format(Locale.US, "%+.2f", f.flow)}, MTF ${String.format(Locale.US, "%+.2f", f.mtfScore)}."
            addLesson("acierto", winText)
            PixelAi.analyzeClosedTradeAsync(f, won = true, netUsdt = net, reason = reason, durationSec = durationMs / 1000) { aiNote ->
                if (!aiNote.isNullOrBlank()) addLesson("gemini", "Gemini Nano: $aiNote")
            }
            return winText
        }
        lossStreak++
        val text = postMortem(f, reason, durationMs)
        addLesson("error", text)
        if (lossStreak == 3) addLesson("proteccion", "Tres pérdidas seguidas: reduzco el riesgo a la mitad hasta confirmar ganancia.")
        PixelAi.analyzeClosedTradeAsync(f, won = false, netUsdt = net, reason = reason, durationSec = durationMs / 1000) { aiNote ->
            if (!aiNote.isNullOrBlank()) addLesson("gemini", "Lección Gemini Nano: $aiNote")
        }
        return text
    }

    @Synchronized
    fun learnHistoricalSample(features: TradeFeatures, won: Boolean, netRate: Double) {
        record(features, won, netRate, false, "Histórico BTC", 0L, 0.0014)
    }

    private fun record(f: TradeFeatures, won: Boolean, rate: Double, real: Boolean, reason: String, durationMs: Long, costRate: Double) {
        val weight = if (real) 4.0 else 0.4
        val v = f.vector()
        val err = (if (won) 1.0 else 0.0) - predictVec(v)
        val lr = (0.07 / (1.0 + realSamples / 80.0)) * weight
        for (i in v.indices) w[i] = (w[i] + lr * (err * v[i] - 0.001 * w[i])).coerceIn(-4.0, 4.0)
        w[0] = w[0].coerceIn(-0.30, 0.35)
        for (k in f.buckets()) {
            val s = buckets.getOrPut(k) { Stat() }
            s.wins *= 0.96; s.losses *= 0.96
            if (won) s.wins += (if (real) 2.0 else 0.5) else s.losses += (if (real) 2.0 else 0.5)
            s.edge += rate
        }
        samples++
        if (real) realSamples++ else shadowSamples++
        recent.put(JSONObject().put("w", won).put("r", reason).put("real", real).put("d", durationMs))
        while (recent.length() > 60) recent.remove(0)
        refreshBlocks(costRate)
        if (samples - tunedAt >= 20) { tunedAt = samples; autoTune(costRate) }
    }

    private fun refreshBlocks(costRate: Double) {
        val now = linkedSetOf<String>()
        for (k in buckets.keys) if (isBlocking(k, costRate)) now.add(k)
        for (k in now) if (k !in activeBlocks) addLesson("regla", "Filtro activado: evitar operar con " + describe(k) + ".")
        for (k in activeBlocks) if (k !in now) addLesson("regla", "Filtro retirado: " + describe(k) + " vuelve a estar habilitado.")
        activeBlocks.clear(); activeBlocks.addAll(now)
    }

    private fun autoTune(costRate: Double) {
        val n = recent.length()
        if (n < 20) return
        var wins = 0; var timeouts = 0; var stops = 0
        for (i in 0 until n) {
            val o = recent.optJSONObject(i) ?: continue
            if (o.optBoolean("w")) wins++
            when (o.optString("r")) { "Tiempo máximo" -> timeouts++; "Stop" -> stops++ }
        }
        val winRate = wins.toDouble() / n
        val be = breakeven(costRate)
        var changed = false
        if (timeouts > n * 0.55 && targetRate > 0.0042) { targetRate = max(0.0040, targetRate - 0.0003); changed = true }
        else if (winRate < be - 0.06 && stops > n * 0.45 && imbEntry < 0.35) { imbEntry = min(0.35, imbEntry + 0.02); changed = true }
        else if (winRate > be + 0.06 && imbEntry > 0.13) { imbEntry = max(0.13, imbEntry - 0.015); changed = true }
        clamp(costRate)
        if (changed) {
            addLesson("ajuste", "Autoajuste tras $n muestras: umbral L2 ${String.format(Locale.US, "%.2f", imbEntry)} · TP ${String.format(Locale.US, "%.2f", targetRate * 100)}%.")
        }
    }

    fun clamp(costRate: Double = 0.0014) {
        imbEntry = imbEntry.coerceIn(0.12, 0.38)
        persistEvals = persistEvals.coerceIn(2, 5)
        stopRate = stopRate.coerceIn(0.0015, 0.0032)
        targetRate = targetRate.coerceIn(max(0.0040, costRate * 2.8), 0.0095)
        minEdge = minEdge.coerceIn(-0.05, 0.06)
        warmupSamples = warmupSamples.coerceIn(5, 20)
        dailyLossLimit = dailyLossLimit.coerceIn(0.03, 0.25)
    }

    fun applyMode(name: String): Boolean {
        when (name) {
            "conservador" -> { imbEntry = 0.22; persistEvals = 4; stopRate = 0.0018; targetRate = 0.0048; minEdge = 0.01; warmupSamples = 18; dailyLossLimit = 0.08 }
            "normal" -> { imbEntry = 0.16; persistEvals = 3; stopRate = 0.0018; targetRate = 0.0046; minEdge = -0.01; warmupSamples = 15; dailyLossLimit = 0.15 }
            "agresivo" -> { imbEntry = 0.13; persistEvals = 2; stopRate = 0.0016; targetRate = 0.0042; minEdge = -0.03; warmupSamples = 8; dailyLossLimit = 0.25 }
            else -> return false
        }
        mode = name
        clamp()
        addLesson("orden", "Modo $name aplicado por orden del usuario.")
        return true
    }

    private fun postMortem(f: TradeFeatures, reason: String, durationMs: Long): String {
        val s = if (f.side == "LONG") 1.0 else -1.0
        val causes = mutableListOf<String>()
        if (reason == "Liquidación") causes.add("apalancamiento excesivo")
        if (reason == "Stop" && durationMs < 20_000L) causes.add("barrido rápido de liquidez (<20 s)")
        if (reason == "Tiempo máximo") causes.add("agotamiento de momento (${String.format(Locale.US, "%.0f", f.volBps)} bps)")
        if (f.flow * s < 0.05) causes.add("divergencia en cinta CVD")
        if (f.mtfScore * s < 0.0) causes.add("temporalidades superiores sin empuje")
        if (causes.isEmpty()) causes.add("retroceso al stop tras absorción")
        return "Pérdida en ${f.side} ($reason): ${causes.joinToString("; ")}. Ajustando pesos."
    }

    private fun describe(key: String): String {
        val v = key.substringAfter(":")
        return when (key.substringBefore(":")) {
            "hora" -> { val h = (v.toIntOrNull() ?: 0) * 3; String.format(Locale.US, "horario %02d:00–%02d:00 UTC", h, h + 3) }
            "libro" -> "desequilibrio de libro $v"
            "spread" -> "spread $v"
            "flujo" -> "cinta CVD " + v.replace("_", " ")
            "vol" -> "volatilidad $v"
            "mtf" -> "enjambre " + v.replace("_", " ")
            else -> key
        }
    }

    fun addLesson(kind: String, text: String) {
        lessons.put(JSONObject().put("t", System.currentTimeMillis()).put("k", kind).put("text", text))
        while (lessons.length() > 60) lessons.remove(0)
    }

    fun lessonLines(n: Int): List<String> {
        val out = mutableListOf<String>()
        var i = lessons.length() - 1
        while (i >= 0 && out.size < n) {
            val o = lessons.optJSONObject(i); i--
            if (o == null) continue
            out.add(java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(o.optLong("t"))) + " · " + o.optString("text"))
        }
        return out
    }

    fun summaryLines(costRate: Double): List<String> {
        val be = breakeven(costRate)
        val rw = recentWinRate()
        val nano = PixelAi.lastTacticalDecision
        val news = NewsIntelligence.summary()
        val phase = when {
            samples < warmupSamples -> "Aprendiendo en vivo ($samples/$warmupSamples)"
            PixelAi.available -> "Operativa · Enjambre MTF + Gemini Nano"
            else -> "Operativa · Enjambre MTF + Order Flow ($samples muestras)"
        }
        val nanoLine = if (nano != null) {
            "Dictamen Gemini Nano: ${nano.action} (${pct(nano.confidence)}%) · SL ${(nano.stopRate * 10000).toInt()} / TP ${(nano.targetRate * 10000).toInt()} bps · ${nano.reason}"
        } else {
            "Estado Gemini Nano: ${PixelAi.statusText}"
        }
        return listOf(
            "Fase: $phase · modo $mode",
            nanoLine,
            "Variante líder en sombra: $bestVariantLabel (activas: ${shadows.size})",
            "Muestras: $samples (reales $realSamples · sombra $shadowSamples) · Acierto reciente: ${pct(rw)}% (breakeven: ${pct(be)}%)",
            "Noticias Gemini Nano: ${String.format(Locale.US, "%+.2f", news.sentiment)} · ${news.latestTitle.take(70)}",
            "Filtros activos: " + (if (activeBlocks.isEmpty()) "ninguno" else activeBlocks.joinToString(", ") { describe(it) })
        )
    }

    fun addChat(role: String, text: String) {
        chat.put(JSONObject().put("r", role).put("t", text).put("time", System.currentTimeMillis()))
        while (chat.length() > 40) chat.remove(0)
    }
    fun chatMessages(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until chat.length()) { val o = chat.optJSONObject(i) ?: continue; out.add(o.optString("r") to o.optString("t")) }
        return out
    }

    fun toJson(): JSONObject {
        val bs = JSONObject()
        for ((k, s) in buckets) bs.put(k, JSONArray().put(s.wins).put(s.losses).put(s.edge))
        val hours = JSONArray(); for (h in blockedHoursLocal) hours.put(h)
        val wj = JSONArray(); for (x in w) wj.put(x)
        return JSONObject().put("v", 5).put("w", wj).put("samples", samples).put("real", realSamples).put("shadow", shadowSamples)
            .put("streak", lossStreak).put("buckets", bs).put("blocks", JSONArray(activeBlocks.toList()))
            .put("recent", recent).put("lessons", lessons).put("chat", chat).put("tunedAt", tunedAt)
            .put("imbEntry", imbEntry).put("persist", persistEvals).put("target", targetRate).put("stop", stopRate)
            .put("minEdge", minEdge).put("warmup", warmupSamples).put("daily", dailyLossLimit).put("mode", mode).put("hours", hours)
    }

    fun load(o: JSONObject?) {
        if (o == null) return
        try {
            val ver = o.optInt("v", 1)
            val savedSamples = o.optInt("samples", 0)
            val wj = o.optJSONArray("w")
            if (wj != null && wj.length() == w.size && ver >= 5) {
                for (i in w.indices) w[i] = wj.optDouble(i, w[i])
            }
            samples = savedSamples; realSamples = o.optInt("real"); shadowSamples = o.optInt("shadow")
            lossStreak = o.optInt("streak"); tunedAt = o.optInt("tunedAt")
            buckets.clear()
            if (ver >= 5) {
                val bs = o.optJSONObject("buckets")
                if (bs != null) for (k in bs.keys()) {
                    val a = bs.optJSONArray(k) ?: continue
                    buckets[k] = Stat(a.optDouble(0), a.optDouble(1), a.optDouble(2))
                }
            }
            copyInto(recent, o.optJSONArray("recent")); copyInto(lessons, o.optJSONArray("lessons")); copyInto(chat, o.optJSONArray("chat"))
            imbEntry = o.optDouble("imbEntry", imbEntry).coerceAtMost(0.24)
            persistEvals = o.optInt("persist", persistEvals).coerceAtMost(4)
            targetRate = o.optDouble("target", targetRate).coerceIn(0.0040, 0.0095)
            stopRate = o.optDouble("stop", 0.0018).coerceIn(0.0015, 0.0025)
            minEdge = o.optDouble("minEdge", minEdge).coerceAtMost(0.01)
            warmupSamples = o.optInt("warmup", 15).coerceIn(5, 18)
            dailyLossLimit = o.optDouble("daily", dailyLossLimit); mode = o.optString("mode", mode)
            blockedHoursLocal.clear()
            val hs = o.optJSONArray("hours"); if (hs != null) for (i in 0 until hs.length()) blockedHoursLocal.add(hs.optInt(i))
            shadows.clear()
            clamp()
            refreshBlocks(0.0014)
        } catch (_: Exception) { }
    }

    private fun copyInto(target: JSONArray, src: JSONArray?) {
        while (target.length() > 0) target.remove(0)
        if (src != null) for (i in 0 until src.length()) target.put(src.opt(i))
    }
}
