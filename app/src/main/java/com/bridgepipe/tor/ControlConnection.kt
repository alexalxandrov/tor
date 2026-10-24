package com.bridgepipe.tor

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Простейший клиент протокола ControlPort (127.0.0.1:9051).
 * Аутентификация по cookie, подписка на события через SETEVENTS.
 */
class ControlConnection(
    private val port: Int,
    private val onEvent: (String) -> Unit,
    private val onReply: (String) -> Unit
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Volatile
    var alive = false
        private set

    /** Подключается с повторами (tor ещё может подниматься). */
    fun connect(maxRetries: Int = 40, delayMs: Long = 500): Boolean {
        var tries = 0
        while (tries < maxRetries) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1))
                reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                alive = true
                return true
            } catch (_: Exception) {
                tries++
                Thread.sleep(delayMs)
            }
        }
        return false
    }

    /** Отправляет команду и читает первую строку ответа (до старта потока событий). */
    private fun command(cmd: String): String {
        val w = writer ?: return "551 нет соединения"
        val r = reader ?: return "551 нет соединения"
        try {
            w.write(cmd)
            w.write("\r\n")
            w.flush()
            return r.readLine() ?: "551 соединение закрыто"
        } catch (e: Exception) {
            return "551 ${e.message}"
        }
    }

    fun authenticate(cookie: ByteArray): Boolean {
        val hex = cookie.joinToString("") { String.format("%02x", it) }
        return command("AUTHENTICATE $hex").startsWith("250")
    }

    fun setEvents(vararg events: String): Boolean =
        command("SETEVENTS ${events.joinToString(" ")}").startsWith("250")

    /** После вызова все ответы/события приходят в onEvent/onReply. */
    fun startPump() {
        thread(name = "tor-control-pump") {
            val r = reader ?: return@thread
            try {
                while (alive) {
                    val l = r.readLine() ?: break
                    if (l.startsWith("650 ")) {
                        onEvent(l.substring(4).trim())
                    } else {
                        onReply(l)
                    }
                }
            } catch (_: Exception) {
            }
            alive = false
        }
    }

    /** Огонь и забыть: сигналы отправляются без ожидания ответа. */
    fun signal(name: String) {
        try {
            val w = writer ?: return
            w.write("SIGNAL $name\r\n")
            w.flush()
        } catch (_: Exception) {
        }
    }

    fun close() {
        alive = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
    }
}
