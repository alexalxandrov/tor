package com.bridgepipe.bridges

import android.content.Context
import com.bridgepipe.util.AppLog

/** Источники bridge-строк. */
object BridgeSources {

    /** Встроенный список: сначала файл из official expert-bundle (кладёт CI), затем fallback. */
    fun builtin(ctx: Context): List<String> {
        for (asset in listOf("tor/builtin_bridges_ci", "builtin_bridges.conf")) {
            try {
                val text = ctx.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }
                val lines = BridgeParser.extract(text)
                if (lines.isNotEmpty()) {
                    AppLog.i("Sources", "встроенный список [$asset]: ${lines.size} мостов")
                    return lines
                }
            } catch (_: Exception) {
            }
        }
        AppLog.w("Sources", "встроенный список мостов не найден — импортируйте вручную")
        return emptyList()
    }

    /** Строка настройки источника: URL или URL|sni=домен */
    fun parseSourceLine(src: String): Pair<String, String?> {
        val parts = src.split('|')
        val url = parts[0].trim()
        var sni: String? = null
        for (i in 1 until parts.size) {
            val p = parts[i].trim()
            if (p.startsWith("sni=", ignoreCase = true)) sni = p.substring(4).trim()
        }
        return url to sni
    }
}
