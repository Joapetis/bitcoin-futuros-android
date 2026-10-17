package com.joapetis.bitcoinfuturos

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class NanoTradeDecision(
    val action: String,
    val confidence: Double,
    val stopRate: Double,
    val targetRate: Double,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

object PixelAi {
    @Volatile var available = false
        private set
    @Volatile var statusText = "Gemini Nano: iniciando conexión con AICore…"
        private set
    @Volatile var lastTacticalDecision: NanoTradeDecision? = null
        private set

    private val preparing = AtomicBoolean(false)
    private val inferring = AtomicBoolean(false)
    private var lastInferenceAt = 0L
    private const val MIN_INFERENCE_GAP_MS = 3_500L

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pixel-ai-core").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pixel-ai-watchdog").apply { isDaemon = true } }
    private val model by lazy { Generation.getClient() }
    private var watchdogStarted = false

    fun forceRetry(onDone: () -> Unit = {}) {
        preparing.set(false)
        prepare(onDone)
    }

    fun prepare(onDone: () -> Unit = {}) {
        startWatchdogIfNeeded(onDone)
        if (!preparing.compareAndSet(false, true)) {
            try { onDone() } catch (_: Throwable) {}
            return
        }
        executor.execute {
            try {
                var status = runBlocking { withTimeoutOrNull(8_000L) { model.checkStatus() } ?: -1 }
                if (status == FeatureStatus.AVAILABLE) {
                    markAvailable()
                    return@execute
                }
                if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                    statusText = "Gemini Nano: sincronizando pesos en Android AICore…"
                    try { onDone() } catch (_: Throwable) {}
                    runBlocking {
                        withTimeoutOrNull(90_000L) {
                            try {
                                model.download().collect { dlStatus ->
                                    statusText = "Gemini Nano: descarga AICore en progreso ($dlStatus)…"
                                    try { onDone() } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    status = runBlocking { withTimeoutOrNull(6_000L) { model.checkStatus() } ?: status }
                }
                if (status == FeatureStatus.AVAILABLE) {
                    markAvailable()
                } else {
                    val probe = runBlocking {
                        try { withTimeoutOrNull(4_500L) { model.generateContent("Responde OK").candidates.firstOrNull()?.text } }
                        catch (_: Throwable) { null }
                    }
                    if (!probe.isNullOrBlank()) markAvailable()
                    else {
                        available = false
                        statusText = "Gemini Nano en espera de AICore (estado $status) · Enjambre MTF activo"
                    }
                }
            } catch (t: Throwable) {
                available = false
                statusText = "Gemini Nano esperando AICore (${t.javaClass.simpleName}) · Enjambre MTF activo"
            } finally {
                preparing.set(false)
                try { onDone() } catch (_: Throwable) {}
            }
        }
    }

    private fun markAvailable() {
        available = true
        statusText = "✦ Gemini Nano ACTIVO · Dirigiendo Enjambre MTF, Order Flow y Noticias"
    }

    private fun startWatchdogIfNeeded(onDone: () -> Unit) {
        if (watchdogStarted) return
        watchdogStarted = true
        watchdog.scheduleWithFixedDelay({ if (!available && !preparing.get()) prepare(onDone) }, 8, 12, TimeUnit.SECONDS)
    }

    fun ask(prompt: String, timeoutMs: Long = 15_000L, onResult: (String?) -> Unit) {
        if (!available) { prepare(); onResult(null); return }
        executor.execute {
            val text = try { runBlocking { withTimeoutOrNull(timeoutMs) { model.generateContent(prompt).candidates.firstOrNull()?.text } } } catch (_: Throwable) { null }
            try { onResult(text?.trim()?.takeIf { it.isNotEmpty() }) } catch (_: Throwable) {}
        }
    }

    fun evaluateTradeAsync(
        candidateSide: String,
        price: Double,
        features: TradeFeatures,
        statWinProb: Double,
        breakevenWinProb: Double,
        recentWinRate: Double,
        lossStreak: Int
    ) {
        if (!available) return
        val now = System.currentTimeMillis()
        if (now - lastInferenceAt < MIN_INFERENCE_GAP_MS) return
        if (!inferring.compareAndSet(false, true)) return
        lastInferenceAt = now

        executor.execute {
            try {
                val prompt = String.format(
                    Locale.US,
                    "Eres un evaluador de señales de trading BTC/USDT. No elijas HOLD por defecto: comprueba los datos y decide sobre el candidato.\\n" +
                    "Datos: precio %.2f USDT; candidato %s; MTF %+.2f; imbalance L2 %+.3f; microprice %+.2f bps; spread %.2f bps; CVD 30s %+.2f; volatilidad 60s %.1f bps; noticias %+.2f; probabilidad estadística %.0f%%; racha pérdidas %d.\\n" +
                    "Reglas: conserva la dirección candidata si todas sus señales están alineadas: LONG requiere CVD > 0.05, MTF >= 0.10, imbalance >= 0.15, microprice >= 0.04 bps y volatilidad >= 8 bps; SHORT requiere CVD < -0.05, MTF <= -0.10, imbalance <= -0.15, microprice <= -0.04 bps y volatilidad >= 8 bps. Spread > 3.5 bps, contradicción real entre señales o probabilidad insuficiente => HOLD. No inventes datos ni cambies LONG por SHORT sin evidencia.\\n" +
                    "Responde SOLO JSON válido con action LONG, SHORT o HOLD; confidence entre 0 y 1; stopBps entre 18 y 32; targetBps entre 44 y 74; reason breve. Ejemplo de formato: {\\\"action\\\":\\\"LONG\\\",\\\"confidence\\\":0.62,\\\"stopBps\\\":20,\\\"targetBps\\\":46,\\\"reason\\\":\\\"señales alineadas\\\"}. Usa la acción del candidato solo si cumple las reglas.\\nJSON:",
                    price, candidateSide, features.mtfScore, features.imbalance, features.microEdgeBps,
                    features.spreadBps, features.flow, features.volBps, features.news, statWinProb * 100.0, lossStreak
                )
                val raw = runBlocking { withTimeoutOrNull(2_500L) { model.generateContent(prompt).candidates.firstOrNull()?.text } }
                val parsed = parseTradeDecision(raw, candidateSide, statWinProb)
                if (parsed != null) lastTacticalDecision = parsed
            } catch (_: Throwable) {
            } finally {
                inferring.set(false)
            }
        }
    }

    /** Analiza los titulares de los feeds RSS con Gemini Nano para extraer sentimiento macro (-1..1) y catalizador. */
    fun analyzeNewsHeadlinesAsync(headlines: List<String>, onResult: (Double?, String?) -> Unit) {
        if (!available || headlines.isEmpty()) { onResult(null, null); return }
        executor.execute {
            try {
                val joined = headlines.take(5).joinToString(" | ")
                val prompt = "Eres un analista institucional de Bitcoin. Evalúa el impacto de estos titulares recientes en el precio de BTC:\n" +
                    "Titulares: «$joined»\n" +
                    "Responde SOLO un JSON válido con sentiment (-1.0 muy bajista a +1.0 muy alcista) y summary (máx 15 palabras en español):\n" +
                    "{\"sentiment\":0.0,\"summary\":\"resumen corto\"}\nJSON:"
                val raw = runBlocking { withTimeoutOrNull(5_000L) { model.generateContent(prompt).candidates.firstOrNull()?.text } }
                if (raw.isNullOrBlank()) { onResult(null, null); return@execute }
                val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
                if (s >= 0 && e > s) {
                    val o = JSONObject(raw.substring(s, e + 1))
                    onResult(o.optDouble("sentiment", 0.0).coerceIn(-1.0, 1.0), o.optString("summary", "").take(90))
                } else onResult(null, null)
            } catch (_: Throwable) { onResult(null, null) }
        }
    }

    fun analyzeClosedTradeAsync(
        features: TradeFeatures,
        won: Boolean,
        netUsdt: Double,
        reason: String,
        durationSec: Long,
        onLesson: (String?) -> Unit
    ) {
        if (!available) { onLesson(null); return }
        executor.execute {
            try {
                val prompt = String.format(
                    Locale.US,
                    "Analiza en 1 sola frase corta (máx 20 palabras en español) esta operación cerrada de BTC:\n" +
                    "Resultado: %s (%+.2f USDT) por %s en %d s. Dirección: %s, L2: %+.2f, CVD: %+.2f, MTF: %+.2f.\nLección:",
                    if (won) "GANADA" else "PERDIDA", netUsdt, reason, durationSec,
                    features.side, features.imbalance, features.flow, features.mtfScore
                )
                val out = runBlocking { withTimeoutOrNull(4_000L) { model.generateContent(prompt).candidates.firstOrNull()?.text } }
                onLesson(out?.replace("\n", " ")?.replace("**", "")?.trim()?.take(160))
            } catch (_: Throwable) { onLesson(null) }
        }
    }

    private fun parseTradeDecision(raw: String?, fallbackSide: String, fallbackConf: Double): NanoTradeDecision? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            val act = o.optString("action", "HOLD").uppercase(Locale.US).let { if (it in setOf("LONG", "SHORT", "HOLD")) it else fallbackSide }
            val conf = o.optDouble("confidence", fallbackConf).coerceIn(0.10, 0.98)
            val stopBps = o.optDouble("stopBps", 18.0).coerceIn(14.0, 35.0)
            val targetBps = o.optDouble("targetBps", 46.0).coerceIn(38.0, 85.0)
            val reason = o.optString("reason", "Confirmación Enjambre MTF + CVD").take(110)
            NanoTradeDecision(act, conf, stopBps / 10_000.0, targetBps / 10_000.0, reason)
        } catch (_: Exception) { null }
    }
}
