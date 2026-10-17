package com.joapetis.bitcoinfuturos

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

object AiCommander {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-commander").apply { isDaemon = true } }
    const val MAX_AI_RISK = 10.0

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    const val HELP = "Puedo hacer esto:\n" +
        "• «Resumen» o «¿qué has aprendido?»\n" +
        "• «Explica la última operación»\n" +
        "• «Pausa» / «Reanuda» / «Cierra todo y pausa»\n" +
        "• «Riesgo 3%» (máx. 10% por orden) · «Apalancamiento máximo x20»\n" +
        "• «Modo conservador / normal / agresivo»\n" +
        "• «Límite de pérdida diaria 10%»\n" +
        "• «No operes entre las 0 y las 6» · «Opera a cualquier hora»"

    fun handle(userText: String, onReply: (String) -> Unit) {
        val clean = userText.trim()
        if (clean.isEmpty()) return
        worker.execute {
            NativeTradingEngine.aiChatAdd("user", clean)
            val local = parseLocal(clean)
            if (local != null) {
                val reply = try { execute(local) } catch (e: Exception) { "No pude ejecutar la orden: " + (e.message ?: "error") }
                NativeTradingEngine.aiChatAdd("ai", reply); onReply(reply)
                return@execute
            }
            if (!PixelAi.available) {
                PixelAi.prepare()
                val reply = "No he reconocido esa orden exacta y Gemini Nano se está sincronizando (${PixelAi.statusText}).\n\n" + HELP
                NativeTradingEngine.aiChatAdd("ai", reply); onReply(reply)
                return@execute
            }
            PixelAi.ask(buildPrompt(clean)) { out ->
                val cmd = out?.let { parseJson(it) }
                val reply = if (cmd == null) "No he podido interpretar esa orden. " + HELP
                else try { execute(cmd) } catch (e: Exception) { "No pude ejecutar la orden: " + (e.message ?: "error") }
                NativeTradingEngine.aiChatAdd("ai", reply); onReply(reply)
            }
        }
    }

    private fun parseLocal(raw: String): JSONObject? {
        val t = norm(raw)
        fun cmd(action: String) = JSONObject().put("action", action)
        if (Regex("(cierra|cerrar).*(todo|posicion|operacion)|parada de emergencia").containsMatchIn(t)) return cmd("stop_close")
        Regex("no oper\\w*\\s+(?:entre|de|desde)\\s+las?\\s+(\\d{1,2})(?::\\d{2})?\\s*(?:y|a|hasta)\\s+las?\\s+(\\d{1,2})").find(t)?.let {
            return cmd("block_hours").put("from", it.groupValues[1].toInt()).put("to", it.groupValues[2].toInt())
        }
        if (Regex("(opera|operar).*(cualquier hora|todas las horas)|desbloquea.*hora").containsMatchIn(t)) return cmd("unblock_hours")
        if (Regex("\\b(pausa|pausar|pausa las|detener|deten|no abras|para el bot|parar)\\b").containsMatchIn(t) && !t.contains("reanud")) return cmd("pause")
        if (Regex("\\b(reanuda|reanudar|inicia|iniciar|arranca|arrancar|empieza|empezar|continua|activa el bot)\\b").containsMatchIn(t)) return cmd("start")
        Regex("riesgo\\D{0,25}(\\d+(?:[.,]\\d+)?)").find(t)?.let { return cmd("risk").put("value", it.groupValues[1].replace(',', '.').toDouble()) }
        Regex("(?:apalancamiento|leverage)\\D{0,25}(\\d{1,3})").find(t)?.let { return cmd("leverage").put("value", it.groupValues[1].toInt()) }
        Regex("(?:limite|perdida)\\s+(?:maxim\\w*\\s+)?diari\\w*\\D{0,15}(\\d+(?:[.,]\\d+)?)").find(t)?.let {
            return cmd("daily_limit").put("value", it.groupValues[1].replace(',', '.').toDouble())
        }
        if (Regex("conservador|prudente|mas seguro").containsMatchIn(t)) return cmd("mode").put("value", "conservador")
        if (Regex("agresivo|mas arriesgado|rentabilidad alta").containsMatchIn(t)) return cmd("mode").put("value", "agresivo")
        if (Regex("modo normal|equilibrado|estandar").containsMatchIn(t)) return cmd("mode").put("value", "normal")
        if (Regex("ultima operacion|ultimo cierre|por que perdiste|que paso con la ultima").containsMatchIn(t)) return cmd("explain_last")
        if (Regex("aprendid|leccion|errores").containsMatchIn(t)) return cmd("lessons")
        if (Regex("resumen|informe|como vamos|rendimiento|estado|como va").containsMatchIn(t)) return cmd("report")
        if (Regex("ayuda|que puedes hacer|comandos").containsMatchIn(t)) return cmd("help")
        return null
    }

    private fun buildPrompt(user: String): String =
        "Eres el intérprete de órdenes de un sistema de trading Swing de Bitcoin. Responde SOLO con un objeto JSON, sin texto extra.\n" +
        "Acciones válidas: start, pause, stop_close, risk(value 0.5-10), leverage(value entero), mode(value conservador|normal|agresivo), " +
        "daily_limit(value 3-25), block_hours(from 0-23,to 0-23), unblock_hours, report, lessons, explain_last, help.\n" +
        "Si la orden no encaja con ninguna, usa {\"action\":\"help\"}.\n" +
        "Orden del usuario: «" + user.take(300).replace("\"", "'") + "»\nJSON:"

    private fun parseJson(out: String): JSONObject? {
        val a = out.indexOf('{'); val b = out.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            val o = JSONObject(out.substring(a, b + 1))
            if (o.optString("action") in setOf("start", "pause", "stop_close", "risk", "leverage", "mode", "daily_limit",
                    "block_hours", "unblock_hours", "report", "lessons", "explain_last", "help")) o else null
        } catch (_: Exception) { null }
    }

    private fun execute(c: JSONObject): String {
        return when (c.optString("action")) {
            "start" -> { NativeTradingEngine.resumeAll(); "Hecho: enjambre multi-temporal y Gemini Nano reanudados." }
            "pause" -> { NativeTradingEngine.setBotEnabled("BTC-USDT", false); "Hecho: pausadas nuevas entradas; gestionando posición y sombras activas." }
            "stop_close" -> { val net = NativeTradingEngine.stopAllAndCloseNow(); "Hecho: posición cerrada (neto " + String.format(Locale.US, "%+.2f", net) + " USDT) y bot pausado." }
            "risk" -> {
                val v = c.optDouble("value", Double.NaN)
                if (v.isNaN()) return "No he entendido el porcentaje de riesgo."
                val applied = v.coerceIn(0.5, MAX_AI_RISK)
                NativeTradingEngine.setRisk("BTC-USDT", applied)
                "Riesgo por operación: " + String.format(Locale.US, "%.1f", applied) + "% del balance."
            }
            "leverage" -> {
                val v = c.optInt("value", 0)
                if (v !in listOf(1, 2, 3, 5, 10, 20, 50, 75, 100)) return "Apalancamientos válidos: x1, 2, 3, 5, 10, 20, 50, 75 o 100."
                NativeTradingEngine.setLeverage("BTC-USDT", v)
                "Apalancamiento máximo: x$v."
            }
            "mode" -> if (NativeTradingEngine.aiSetMode(c.optString("value"))) "Modo " + c.optString("value") + " aplicado." else "Modo desconocido."
            "daily_limit" -> {
                val v = c.optDouble("value", Double.NaN)
                if (v.isNaN()) return "No he entendido el límite."
                val applied = NativeTradingEngine.aiSetDailyLimit(v)
                "Límite de pérdida diaria fijado en " + String.format(Locale.US, "%.0f", applied) + "%."
            }
            "block_hours" -> { NativeTradingEngine.aiBlockHours(c.optInt("from"), c.optInt("to")); "Horario bloqueado de " + c.optInt("from") + ":00 a " + c.optInt("to") + ":00." }
            "unblock_hours" -> { NativeTradingEngine.aiClearBlockedHours(); "Sin restricciones horarias." }
            "lessons" -> {
                val l = NativeTradingEngine.aiLessons(8)
                if (l.isEmpty()) "Recopilando lecciones del enjambre." else "Últimas lecciones:\n• " + l.joinToString("\n• ")
            }
            "explain_last" -> NativeTradingEngine.lastTradeSummary()
            "report" -> NativeTradingEngine.compoundingStatus() + "\n" + NativeTradingEngine.aiBrainSummary().joinToString("\n")
            else -> HELP
        }
    }
}
