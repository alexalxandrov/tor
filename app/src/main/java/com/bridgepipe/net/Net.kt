package com.bridgepipe.net

import com.bridgepipe.util.AppLog
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP-клиент приложения для скачивания списков мостов.
 * Умеет: ходить через tor-SOCKS (когда туннель поднят), просить gzip
 * (экономия на 2G) и подменять SNI в TLS.
 */
object Net {

    /** SNI-имя без строгой валидации hostname (в отличие от SNIHostName). */
    private class RawSni(host: String) : SNIServerName(0, host.toByteArray(Charsets.US_ASCII))

    class Options(
        val viaTor: Boolean = false,
        val socksPort: Int = 9050,
        val sniHost: String? = null,
        val timeoutMs: Int = 15000
    )

    fun fetch(urlStr: String, opts: Options): String {
        val url = URL(urlStr)
        val conn = if (opts.viaTor) {
            // SOCKS-прокси tor: DNS-имена резолвятся на стороне tor (аналог SOCKS5h)
            url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", opts.socksPort)))
        } else {
            url.openConnection()
        } as java.net.HttpURLConnection

        conn.connectTimeout = opts.timeoutMs
        conn.readTimeout = opts.timeoutMs * 2
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "BridgePipe/0.1")

        if (conn is HttpsURLConnection && !opts.sniHost.isNullOrEmpty()) {
            conn.sslSocketFactory = SniSocketFactory(opts.sniHost)
            // SNI подменён — обычная проверка имени хоста невозможна.
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        val code = conn.responseCode
        if (code != 200) throw IOException("HTTP $code: $urlStr")

        var input: InputStream = conn.inputStream
        if (conn.contentEncoding?.contains("gzip") == true) {
            input = GZIPInputStream(input)
        }
        val bytes = input.use { it.readBytes() }
        AppLog.d("Net", "получено ${bytes.size} байт от ${url.host}")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * SSLSocketFactory, подменяющая SNI на заданное значение.
     * ВНИМАНИЕ: проверка сертификата при этом отключается (иначе соединение
     * не сойдётся) — это осознанный режим «прорваться», включается опционально.
     */
    private class SniSocketFactory(private val sni: String) : SSLSocketFactory() {

        private val base: SSLSocketFactory

        init {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tm, SecureRandom())
            base = ctx.socketFactory
        }

        private fun java.net.Socket.applySni(): java.net.Socket {
            if (this is SSLSocket) {
                val p = sslParameters
                p.serverNames = listOf(RawSni(sni))
                sslParameters = p
            }
            return this
        }

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket =
            base.createSocket(s, host, port, autoClose).applySni()

        override fun createSocket(host: String, port: Int): java.net.Socket =
            base.createSocket(host, port).applySni()

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): java.net.Socket =
            base.createSocket(host, port, localHost, localPort).applySni()

        override fun createSocket(host: InetAddress, port: Int): java.net.Socket =
            base.createSocket(host, port).applySni()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): java.net.Socket =
            base.createSocket(address, port, localAddress, localPort).applySni()

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    }
}
