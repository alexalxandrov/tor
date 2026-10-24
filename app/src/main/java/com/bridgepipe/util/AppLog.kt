package com.bridgepipe.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Кольцевой журнал приложения. По умолчанию verbose: сюда пишется ВСЁ —
 * события tor, результаты пингов мостов, состояние сервиса, ошибки.
 */
object AppLog {

    class Entry(val ts: Long, val level: String, val tag: String, val msg: String)

    private const val MAX_ENTRIES = 4000
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val entries = ArrayList<Entry>(2048)
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    @Volatile private var logFile: File? = null
    @Volatile private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fmtFile = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        logFile = File(dir, "bridgepipe.log")
        if (logFile!!.exists() && logFile!!.length() > MAX_FILE_BYTES) {
            runCatching { logFile!!.delete() }
        }
        AppLog.i("AppLog", "лог-файл: ${logFile?.absolutePath}")
    }

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    /** Сырой вывод процесса tor — пишется как есть, verbose по умолчанию. */
    fun t(tag: String, msg: String) = add("T", tag, msg)

    fun add(level: String, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, msg)
        val shortTime: String
        val fileLine: String
        synchronized(this) {
            entries.add(e)
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            val d = Date(e.ts)
            shortTime = fmt.format(d)
            fileLine = "${fmtFile.format(d)} ${e.level}/${e.tag}: ${e.msg}"
        }
        synchronized(this) {
            try {
                if (writer == null && logFile != null) {
                    writer = BufferedWriter(FileWriter(logFile, true))
                }
                writer?.apply {
                    write(fileLine)
                    newLine()
                    flush()
                }
            } catch (_: Exception) {
                writer = null
            }
        }
        for (l in listeners) {
            try {
                l(e)
            } catch (_: Exception) {
            }
        }
        when (level) {
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
            "I" -> Log.i(tag, msg)
            else -> Log.d(tag, msg)
        }
    }

    fun snapshot(): List<Entry> = synchronized(this) { ArrayList(entries) }

    fun clear() = synchronized(this) { entries.clear() }

    fun file(): File? = logFile

    /** Подписка на новые записи. Возвращает функцию отписки. */
    fun listen(cb: (Entry) -> Unit): () -> Unit {
        listeners.add(cb)
        return { listeners.remove(cb) }
    }
}
