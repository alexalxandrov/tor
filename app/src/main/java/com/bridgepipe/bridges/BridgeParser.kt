package com.bridgepipe.bridges

import java.util.Locale

/**
 * Парсер и валидатор bridge-строк tor.
 * Поддерживает: obfs4, webtunnel, snowflake, meek, meek_lite, vanilla.
 * Умеет вытаскивать мосты из «грязного» текста: списки, JSON, конфиги.
 */
object BridgeParser {

    val TRANSPORTS = arrayOf("obfs4", "webtunnel", "snowflake", "meek_lite", "meek", "vanilla")

    private val HOST_PORT_BR = Regex("\\[(([0-9A-Fa-f]{1,4}:){1,7}[0-9A-Fa-f]{0,4})\\]:(\\d{1,5})")
    private val HOST_PORT_IP = Regex("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{1,5})")

    /** Нормализует строку. Возвращает null для мусора. */
    fun normalize(rawIn: String): String? {
        var raw = rawIn.trim()
            .replace("\\/", "/")
            .replace("\\n", " ")
            .trim('"', '\'', '`', ',', ';', '\\', ' ')
        if (raw.isEmpty() || raw.startsWith("#")) return null

        // Убираем возможный префикс "Bridge"
        val lowRaw = raw.lowercase(Locale.US)
        if (lowRaw.startsWith("bridge ")) {
            raw = raw.substring(7).trim()
        }

        var transport = "vanilla"
        var body = raw
        val low = raw.lowercase(Locale.US)
        for (t in TRANSPORTS) {
            if (low.startsWith(t + " ")) {
                transport = t
                body = raw.substring(t.length + 1).trim()
                break
            }
        }

        val hp = hostPortOf(body) ?: return null

        // Требования к обязательным параметрам транспортов
        val bl = body.lowercase(Locale.US)
        when (transport) {
            "obfs4" -> if (!bl.contains("cert=")) return null
            "webtunnel", "snowflake", "meek", "meek_lite" -> if (!bl.contains("url=")) return null
        }

        return if (transport == "vanilla") body else "$transport $body"
    }

    fun transportOf(line: String): String {
        val low = line.lowercase(Locale.US)
        for (t in TRANSPORTS) {
            if (low.startsWith(t + " ")) return t
        }
        return "vanilla"
    }

    /** Вытаскивает (host, port) из строки моста. Понимает IPv4 и [IPv6]. */
    fun hostPortOf(line: String): Pair<String, Int>? {
        val m6 = HOST_PORT_BR.find(line)
        if (m6 != null) {
            val port = m6.groupValues[3].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return m6.groupValues[1] to port
        }
        val m4 = HOST_PORT_IP.find(line)
        if (m4 != null) {
            val port = m4.groupValues[2].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return m4.groupValues[1] to port
        }
        return null
    }

    /**
     * Извлекает все мосты из произвольного текста: plain-список,
     * base64-блок, JSON-массив, конфиг torrc — не важно.
     */
    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (chunk in text.split('\n', '\r', '"', ';')) {
            val c = chunk.trim(' ', '\t', ',', '{', '}', '[', ']', '"', '\'', '\\', '`')
            val n = normalize(c) ?: continue
            out.add(n)
        }
        return out.toList()
    }

    /** Мосты, которые имеет смысл TCP-пинговать. snowflake/meek идут через broker — пинг не нужен. */
    fun isProbeable(transport: String): Boolean =
        transport == "obfs4" || transport == "webtunnel" || transport == "vanilla"
}
