package com.bridgepipe.data

import android.content.Context

/**
 * Все настройки приложения. Имена и дефолты подобраны под работу на 2G:
 * сниженный padding, увеличенные таймауты, snowflake по умолчанию выключен.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("bridgepipe", Context.MODE_PRIVATE)

    var socksPort: Int
        get() = sp.getInt("socksPort", 9050)
        set(v) = sp.edit().putInt("socksPort", v).apply()

    var controlPort: Int
        get() = sp.getInt("controlPort", 9051)
        set(v) = sp.edit().putInt("controlPort", v).apply()

    var socksBindAll: Boolean
        get() = sp.getBoolean("socksBindAll", false)
        set(v) = sp.edit().putBoolean("socksBindAll", v).apply()

    var topN: Int
        get() = sp.getInt("topN", 12)
        set(v) = sp.edit().putInt("topN", v).apply()

    var useBridges: Boolean
        get() = sp.getBoolean("useBridges", true)
        set(v) = sp.edit().putBoolean("useBridges", v).apply()

    var reducedPadding: Boolean
        get() = sp.getBoolean("reducedPadding", true)
        set(v) = sp.edit().putBoolean("reducedPadding", v).apply()

    var allowIpv6: Boolean
        get() = sp.getBoolean("allowIpv6", false)
        set(v) = sp.edit().putBoolean("allowIpv6", v).apply()

    var snowflakeEnabled: Boolean
        get() = sp.getBoolean("snowflakeEnabled", false)
        set(v) = sp.edit().putBoolean("snowflakeEnabled", v).apply()

    var bandwidthRateKB: Int
        get() = sp.getInt("bandwidthRateKB", 0)
        set(v) = sp.edit().putInt("bandwidthRateKB", v).apply()

    var socksTimeoutSec: Int
        get() = sp.getInt("socksTimeoutSec", 120)
        set(v) = sp.edit().putInt("socksTimeoutSec", v).apply()

    var fetchViaTor: Boolean
        get() = sp.getBoolean("fetchViaTor", true)
        set(v) = sp.edit().putBoolean("fetchViaTor", v).apply()

    var bridgesDbEnabled: Boolean
        get() = sp.getBoolean("bridgesDbEnabled", true)
        set(v) = sp.edit().putBoolean("bridgesDbEnabled", v).apply()

    var bridgesDbUrl: String
        get() = sp.getString("bridgesDbUrl", "https://bridges.torproject.org/bridges?transport=")!!
        set(v) = sp.edit().putString("bridgesDbUrl", v).apply()

    /** Свои источники: один URL на строку, опционально "|sni=host". */
    var customSources: String
        get() = sp.getString("customSources", "")!!
        set(v) = sp.edit().putString("customSources", v).apply()

    var sniEnabled: Boolean
        get() = sp.getBoolean("sniEnabled", false)
        set(v) = sp.edit().putBoolean("sniEnabled", v).apply()

    var sniHost: String
        get() = sp.getString("sniHost", "")!!
        set(v) = sp.edit().putString("sniHost", v).apply()

    var probeTimeoutSec: Int
        get() = sp.getInt("probeTimeoutSec", 7)
        set(v) = sp.edit().putInt("probeTimeoutSec", v).apply()

    var probeConcurrency: Int
        get() = sp.getInt("probeConcurrency", 12)
        set(v) = sp.edit().putInt("probeConcurrency", v).apply()

    /** Уровень stdout-лога tor: notice | info | debug */
    var torLogLevel: String
        get() = sp.getString("torLogLevel", "notice")!!
        set(v) = sp.edit().putString("torLogLevel", v).apply()

    var autoApply: Boolean
        get() = sp.getBoolean("autoApply", true)
        set(v) = sp.edit().putBoolean("autoApply", v).apply()

    /** Произвольный torrc, дописываемый в конец конфига как есть. */
    var torrcOverride: String
        get() = sp.getString("torrcOverride", "")!!
        set(v) = sp.edit().putString("torrcOverride", v).apply()

    companion object {
        /** Публичные параметры snowflake-брокера (стандартные значения Tor Browser). */
        const val SNOWFLAKE_BROKER_URL =
            "https://snowflake-broker.torproject.net.global.prod.fastly.net/"
        const val SNOWFLAKE_FRONT = "cdn.sstatic.net"
        const val SNOWFLAKE_ICE =
            "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.bluesoft.net:3478," +
                "stun:stun.dus.net:3478,stun:stun.epytx.tomoe-n.com:3478,stun:stun.slm.ec:3478," +
                "stun:stun.sohnen.de:3478,stun:stun.voip.blackberry.com:3478,stun:stun.voipbuster.com:3478"
    }
}
