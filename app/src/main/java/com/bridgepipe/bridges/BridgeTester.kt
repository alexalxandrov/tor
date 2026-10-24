package com.bridgepipe.bridges

import com.bridgepipe.data.Bridge
import com.bridgepipe.data.BridgeDb
import com.bridgepipe.data.BridgeStatus
import com.bridgepipe.data.Prefs
import com.bridgepipe.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Дешёвая проверка мостов для медленных сетей: чистый TCP-connect
 * (хендшейк, почти ноль трафика), параллельный пул, жёсткие таймауты.
 * snowflake/meek не пингуются — они ходят через broker.
 */
class BridgeTester(private val db: BridgeDb, private val prefs: Prefs) {

    data class Result(val total: Int, val ok: Int, val fail: Int, val skipped: Int)

    suspend fun testAll(force: Boolean): Result = coroutineScope {
        val all = db.all()
        val targets = all.filter { BridgeParser.isProbeable(it.transport) && (force || it.status != BridgeStatus.OK) }
        val skipped = all.size - targets.size

        val sem = Semaphore(prefs.probeConcurrency.coerceIn(1, 64))
        AppLog.i("Probe", "проверяю ${targets.size} мостов (параллельно ${prefs.probeConcurrency}, таймаут ${prefs.probeTimeoutSec}с)")

        val jobs = targets.map { b ->
            async(Dispatchers.IO) {
                sem.withPermit { probe(b) }
            }
        }
        val results = jobs.awaitAll()
        val ok = results.count { it }
        val fail = results.size - ok
        AppLog.i("Probe", "готово: живых $ok, мёртвых $fail, пропущено (snowflake/meek) $skipped")
        Result(results.size, ok, fail, skipped)
    }

    private fun probe(b: Bridge): Boolean {
        val hp = BridgeParser.hostPortOf(b.line)
        if (hp == null) {
            db.updateProbe(b.id, false, -1)
            AppLog.d("Probe", "FAIL (нет host:port) ${b.transport} ${b.line.take(50)}")
            return false
        }
        db.markTesting(b.id)
        val t0 = System.currentTimeMillis()
        val ok = try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(hp.first, hp.second), prefs.probeTimeoutSec * 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
        val dt = (System.currentTimeMillis() - t0).toInt()
        db.updateProbe(b.id, ok, if (ok) dt else -1)
        AppLog.d(
            "Probe",
            (if (ok) "OK   " else "FAIL ") + "$dt ms ${b.transport} ${hp.first}:${hp.second}"
        )
        return ok
    }
}
