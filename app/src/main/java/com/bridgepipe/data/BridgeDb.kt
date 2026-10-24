package com.bridgepipe.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bridgepipe.bridges.BridgeParser

object BridgeStatus {
    const val NEW = 0
    const val TESTING = 1
    const val OK = 2
    const val FAIL = 3
}

data class Bridge(
    val id: Long,
    val line: String,
    val transport: String,
    val source: String,
    val status: Int,
    val latencyMs: Int,
    val lastCheck: Long,
    val okCount: Int,
    val failCount: Int,
    val addedAt: Long
)

class BridgeDb(context: Context) : SQLiteOpenHelper(context, "bridges.db", null, 1) {

    private val select =
        "SELECT id, line, transport, source, status, latency, last_check, ok_count, fail_count, added_at FROM bridges"
    private val order =
        " ORDER BY CASE status WHEN 2 THEN 0 WHEN 0 THEN 1 WHEN 1 THEN 2 ELSE 3 END ASC," +
            " CASE WHEN latency>=0 THEN latency ELSE 100000000 END ASC," +
            " ok_count DESC, last_check DESC"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bridges(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "line TEXT NOT NULL UNIQUE," +
                "transport TEXT NOT NULL," +
                "source TEXT NOT NULL DEFAULT ''," +
                "status INTEGER NOT NULL DEFAULT 0," +
                "latency INTEGER NOT NULL DEFAULT -1," +
                "last_check INTEGER NOT NULL DEFAULT 0," +
                "ok_count INTEGER NOT NULL DEFAULT 0," +
                "fail_count INTEGER NOT NULL DEFAULT 0," +
                "added_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_bridges_status ON bridges(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // схема версии 1
    }

    fun import(lines: List<String>, source: String): Int {
        var inserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (l in lines) {
                val cv = ContentValues()
                cv.put("line", l)
                cv.put("transport", BridgeParser.transportOf(l))
                cv.put("source", source.take(120))
                cv.put("status", BridgeStatus.NEW)
                cv.put("latency", -1)
                cv.put("added_at", System.currentTimeMillis())
                if (db.insertWithOnConflict("bridges", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun markTesting(id: Long) {
        val cv = ContentValues()
        cv.put("status", BridgeStatus.TESTING)
        writableDatabase.update("bridges", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateProbe(id: Long, ok: Boolean, latencyMs: Int) {
        if (ok) {
            writableDatabase.execSQL(
                "UPDATE bridges SET status=2, latency=?, last_check=?, ok_count=ok_count+1 WHERE id=?",
                arrayOf<Any>(latencyMs, System.currentTimeMillis(), id)
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE bridges SET status=3, latency=-1, last_check=?, fail_count=fail_count+1 WHERE id=?",
                arrayOf<Any>(System.currentTimeMillis(), id)
            )
        }
    }

    fun best(limit: Int = 4000): List<Bridge> = read(select + order + " LIMIT $limit")

    fun all(): List<Bridge> = read(select + order)

    private fun read(sql: String): List<Bridge> {
        val out = ArrayList<Bridge>(256)
        readableDatabase.rawQuery(sql, emptyArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bridge(
                        id = c.getLong(0),
                        line = c.getString(1),
                        transport = c.getString(2),
                        source = c.getString(3),
                        status = c.getInt(4),
                        latencyMs = c.getInt(5),
                        lastCheck = c.getLong(6),
                        okCount = c.getInt(7),
                        failCount = c.getInt(8),
                        addedAt = c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun count(): Int = readCount("SELECT COUNT(*) FROM bridges")
    fun countOk(): Int = readCount("SELECT COUNT(*) FROM bridges WHERE status=2")
    fun countFail(): Int = readCount("SELECT COUNT(*) FROM bridges WHERE status=3")
    fun countNew(): Int = readCount("SELECT COUNT(*) FROM bridges WHERE status=0")

    private fun readCount(sql: String): Int {
        readableDatabase.rawQuery(sql, emptyArray()).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun resetStatuses() {
        writableDatabase.execSQL("UPDATE bridges SET status=0, latency=-1")
    }

    fun deleteAll() {
        writableDatabase.delete("bridges", null, null)
    }
}
