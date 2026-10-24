package com.bridgepipe.tor

import android.content.Context
import com.bridgepipe.data.Prefs
import java.io.File
import java.util.Date

/**
 * Генератор torrc. Все настройки совместимы с tor; произвольный блок
 * torrc-override дописывается в конец как есть.
 */
object TorConfigBuilder {

    /** Транспорты, бинарники которых лежат в APK (jniLibs от CI). */
    fun availableTransports(ctx: Context): Set<String> {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val res = HashSet<String>()
        val lyrebird = File(dir, "liblyrebird.so").exists()
        val obfs4proxy = File(dir, "libobfs4proxy.so").exists()
        if (lyrebird || obfs4proxy) res.add("obfs4")
        if (lyrebird) res.add("webtunnel")
        if (File(dir, "libsnowflake.so").exists() || File(dir, "libsnowflake-client.so").exists()) {
            res.add("snowflake")
        }
        res.add("vanilla")
        return res
    }

    fun build(ctx: Context, prefs: Prefs, bridges: List<String>): String {
        val torDir = File(ctx.filesDir, "tor")
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s)
            sb.append('\n')
        }

        line("# BridgePipe torrc, сгенерирован ${Date()}")
        line("# Слушающие порты")
        val bind = if (prefs.socksBindAll) "0.0.0.0" else "127.0.0.1"
        if (!prefs.allowIpv6) {
            line("SocksPort $bind:${prefs.socksPort} IPv4Traffic")
        } else {
            line("SocksPort $bind:${prefs.socksPort}")
        }
        line("ControlPort 127.0.0.1:${prefs.controlPort}")
        line("CookieAuthentication 1")

        line("# Файлы")
        line("DataDirectory ${torDir.absolutePath}")
        line("CacheDirectory ${File(torDir, "cache").absolutePath}")
        val geoip = File(torDir, "geoip")
        val geoip6 = File(torDir, "geoip6")
        if (geoip.exists()) line("GeoIPFile ${geoip.absolutePath}")
        if (geoip6.exists()) line("GeoIPv6File ${geoip6.absolutePath}")

        line("# Режим клиента")
        line("ClientOnly 1")
        line("SafeSocks 0")
        line("SocksTimeout ${prefs.socksTimeoutSec}")
        if (!prefs.allowIpv6) line("ClientPreferIPv6ORPort 0")
        line("Log ${prefs.torLogLevel} stdout")

        line("# Экономия трафика (режим 2G)")
        if (prefs.reducedPadding) {
            line("ReducedConnectionPadding 1")
            line("ReducedCircuitPadding 1")
        } else {
            line("ConnectionPadding auto")
        }
        if (prefs.bandwidthRateKB > 0) {
            line("BandwidthRate ${prefs.bandwidthRateKB} KBytes")
            line("BandwidthBurst ${maxOf(prefs.bandwidthRateKB * 2, 512)} KBytes")
        }

        line("# Pluggable transports")
        val lyrebird = File(libDir, "liblyrebird.so")
        val obfs4proxy = File(libDir, "libobfs4proxy.so")
        val snowflake = listOf("libsnowflake.so", "libsnowflake-client.so")
            .map { File(libDir, it) }
            .firstOrNull { it.exists() }
        if (lyrebird.exists()) {
            line("ClientTransportPlugin obfs4,webtunnel exec ${lyrebird.absolutePath}")
        } else if (obfs4proxy.exists()) {
            line("ClientTransportPlugin obfs4 exec ${obfs4proxy.absolutePath}")
        }
        if (snowflake != null && prefs.snowflakeEnabled) {
            line(
                "ClientTransportPlugin snowflake exec ${snowflake.absolutePath}" +
                    " -url ${Prefs.SNOWFLAKE_BROKER_URL}" +
                    " -front ${Prefs.SNOWFLAKE_FRONT}" +
                    " -ice ${Prefs.SNOWFLAKE_ICE}"
            )
        }

        line("# Мосты")
        if (prefs.useBridges && bridges.isNotEmpty()) {
            line("UseBridges 1")
            for (b in bridges) {
                line("Bridge $b")
            }
        } else {
            if (prefs.useBridges) {
                line("# (мостов не найдено — прямое подключение к сети tor)")
            }
            line("UseBridges 0")
        }

        val ov = prefs.torrcOverride.trim()
        if (ov.isNotEmpty()) {
            line("# --- torrc override (настройки пользователя) ---")
            sb.append(ov)
            if (!ov.endsWith("\n")) sb.append('\n')
        }
        return sb.toString()
    }
}
