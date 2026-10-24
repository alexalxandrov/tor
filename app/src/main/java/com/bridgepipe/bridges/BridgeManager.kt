package com.bridgepipe.bridges

import android.content.Context
import com.bridgepipe.data.BridgeDb
import com.bridgepipe.data.Prefs
import com.bridgepipe.net.Net
import com.bridgepipe.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Оркестратор мостов: обновление источников, импорт, выбор топ-N для конфига tor.
 *
 * Холодный старт без туннеля (например, если bridges.torproject.org заблокирован):
 * встроенный список + свои зеркала + ручной импорт.
 * Когда туннель поднят — все HTTPS-запросы идут через него (чистый канал).
 */
class BridgeManager(
    private val ctx: Context,
    private val db: BridgeDb,
    private val prefs: Prefs
) {

    suspend fun refreshSources(torUp: Boolean): Int = withContext(Dispatchers.IO) {
        var added = 0

        // 1. Встроенный список
        val builtin = BridgeSources.builtin(ctx)
        if (builtin.isNotEmpty()) {
            val n = db.import(builtin, "builtin")
            added += n
            AppLog.i("Manager", "встроенный список: +$n новых")
        }

        // 2. Свои зеркала (URL на строку, опционально |sni=host)
        for (src in prefs.customSources.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val (url, perSni) = BridgeSources.parseSourceLine(src)
            val sni = perSni ?: if (prefs.sniEnabled) prefs.sniHost else null
            try {
                val t0 = System.currentTimeMillis()
                val text = Net.fetch(
                    url,
                    Net.Options(
                        viaTor = prefs.fetchViaTor && torUp,
                        socksPort = prefs.socksPort,
                        sniHost = sni
                    )
                )
                AppLog.i("Manager", "источник ${url.take(80)}: ${text.length} символов за ${System.currentTimeMillis() - t0}мс")
                val lines = BridgeParser.extract(text)
                val n = db.import(lines, url.take(80))
                added += n
            } catch (e: Exception) {
                AppLog.w("Manager", "источник ${url.take(80)} недоступен: ${e.message}")
            }
        }

        // 3. BridgesDB (если доступен — напрямую или через туннель)
        if (prefs.bridgesDbEnabled) {
            for (t in listOf("obfs4", "webtunnel")) {
                val url = prefs.bridgesDbUrl + t
                try {
                    val text = Net.fetch(
                        url,
                        Net.Options(
                            viaTor = prefs.fetchViaTor && torUp,
                            socksPort = prefs.socksPort,
                            sniHost = if (prefs.sniEnabled) prefs.sniHost else null
                        )
                    )
                    val lines = BridgeParser.extract(text)
                    val n = db.import(lines, "bridgesdb/$t")
                    added += n
                } catch (e: Exception) {
                    AppLog.w("Manager", "BridgesDB ($t) недоступен (ожидаемо, если заблокирован): ${e.message}")
                }
            }
        }

        AppLog.i("Manager", "обновление источников завершено, новых мостов: $added (всего в базе: ${db.count()})")
        added
    }

    fun importLines(text: String, source: String): Int =
        db.import(BridgeParser.extract(text), source)

    /**
     * Лучшие мосты для конфига: только те, чей транспорт доступен в APK
     * (liblyrebird/obfs4proxy/snowflake), лимит — top-N из настроек.
     */
    fun topForConfig(availableTransports: Set<String>): List<String> {
        val picked = ArrayList<String>()
        for (b in db.best()) {
            if (picked.size >= prefs.topN.coerceIn(1, 200)) break
            if (b.transport != "vanilla" && b.transport !in availableTransports) continue
            if (b.transport == "snowflake" && !prefs.snowflakeEnabled) continue
            picked.add(b.line)
        }
        return picked
    }

    fun stats(): String =
        "всего ${db.count()} · рабочих ${db.countOk()} · мёртвых ${db.countFail()} · непроверенных ${db.countNew()}"
}
