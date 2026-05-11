package com.aiide

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "event_logger.db"
        private const val DATABASE_VERSION = 1
        private const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                event_data TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS events")
        onCreate(db)
    }
}

class EventLogger(context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val batch = mutableListOf<Pair<String, JSONObject>>()
    private val batchLock = Object()
    private val MAX_BATCH_SIZE = 20
    private val FLUSH_INTERVAL_MS = 2000L
    private val MAX_TOTAL_EVENTS = 50000
    private val MAX_EVENTS_BEFORE_ROTATION = 1000
    private val ROTATION_KEEP = 500

    private val handlerThread = HandlerThread("EventLogger").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var eventCount = 0

    @Volatile
    private var isDestroyed = false

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushBatch()
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    init {
        handler.post(flushRunnable)
    }

    fun logEvent(eventType: String, data: JSONObject) {
        if (isDestroyed) return
        synchronized(batchLock) {
            batch.add(Pair(eventType, data))
            if (batch.size >= MAX_BATCH_SIZE) {
                flushBatchLocked()
            }
        }
    }

    fun logLLMRequest(model: String, tokensIn: Int, tokensOut: Int, durationMs: Long, success: Boolean) {
        logEvent("llm_request", JSONObject().apply {
            put("model", model)
            put("tokens_in", tokensIn)
            put("tokens_out", tokensOut)
            put("duration_ms", durationMs)
            put("success", success)
        })
    }

    fun logFileEdit(path: String, source: String, linesAdded: Int, linesRemoved: Int) {
        logEvent("file_edit", JSONObject().apply {
            put("path", path)
            put("source", source)
            put("lines_added", linesAdded)
            put("lines_removed", linesRemoved)
        })
    }

    fun logToolCall(tool: String, success: Boolean, durationMs: Long) {
        logEvent("tool_call", JSONObject().apply {
            put("tool", tool)
            put("success", success)
            put("duration_ms", durationMs)
        })
    }

    fun logError(errorType: String, message: String) {
        logEvent("error", JSONObject().apply {
            put("type", errorType)
            put("message", message)
        })
    }

    fun logSession(durationMs: Long, filesOpened: Int, editsMade: Int) {
        logEvent("session", JSONObject().apply {
            put("duration_ms", durationMs)
            put("files_opened", filesOpened)
            put("edits_made", editsMade)
        })
    }

    fun flush() {
        synchronized(batchLock) {
            flushBatchLocked()
        }
    }

    private fun flushBatch() {
        synchronized(batchLock) {
            flushBatchLocked()
        }
    }

    private fun flushBatchLocked() {
        if (batch.isEmpty()) return
        val toWrite = ArrayList(batch)
        batch.clear()

        try {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT INTO events (event_type, event_data, timestamp) VALUES (?, ?, ?)"
                )
                for ((eventType, data) in toWrite) {
                    try {
                        stmt.bindString(1, eventType)
                        stmt.bindString(2, data.toString())
                        stmt.bindLong(3, System.currentTimeMillis())
                        stmt.executeInsert()
                        eventCount++
                    } catch (e: Exception) {
                        android.util.Log.w("EventLogger", "Failed to insert event: $eventType", e)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            if (eventCount >= MAX_EVENTS_BEFORE_ROTATION) {
                rotateEvents(db)
                eventCount = 0
            }
        } catch (e: Exception) {
            android.util.Log.e("EventLogger", "Failed to flush batch", e)
        }
    }

    private fun rotateEvents(db: android.database.sqlite.SQLiteDatabase) {
        try {
            val countCursor = db.rawQuery("SELECT COUNT(*) FROM events", null)
            val totalCount = if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
            countCursor.close()

            val deleteCount = (totalCount - ROTATION_KEEP).coerceAtLeast(0)
            if (deleteCount > 0) {
                val deleteOld = "DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY timestamp ASC LIMIT ?)"
                db.execSQL(deleteOld, arrayOf(deleteCount.toLong()))
            }
            android.util.Log.i("EventLogger", "Event rotation completed")
        } catch (e: Exception) {
            android.util.Log.w("EventLogger", "Event rotation failed", e)
        }
    }

    fun queryEvents(
        eventType: String? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
        limit: Int = 100
    ): List<Pair<String, JSONObject>> {
        val results = mutableListOf<Pair<String, JSONObject>>()
        val db = dbHelper.readableDatabase

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        eventType?.let {
            whereClauses.add("event_type = ?")
            args.add(it)
        }
        fromTimestamp?.let {
            whereClauses.add("timestamp >= ?")
            args.add(it.toString())
        }
        toTimestamp?.let {
            whereClauses.add("timestamp <= ?")
            args.add(it.toString())
        }

        val whereClause = if (whereClauses.isEmpty()) null else whereClauses.joinToString(" AND ")

        val cursor = db.query(
            "events",
            arrayOf("event_type", "event_data", "timestamp"),
            whereClause,
            args.toTypedArray(),
            null, null,
            "timestamp DESC",
            limit.toString()
        )

        try {
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val dataStr = cursor.getString(1)
                try {
                    val data = JSONObject(dataStr)
                    results.add(Pair(type, data))
                } catch (_: Exception) {}
            }
        } finally {
            cursor.close()
        }
        return results
    }

    fun getEventStats(): JSONObject {
        val db = dbHelper.readableDatabase
        val stats = JSONObject()

        try {
            val totalCursor = db.rawQuery("SELECT COUNT(*) FROM events", null)
            if (totalCursor.moveToFirst()) {
                stats.put("total_events", totalCursor.getInt(0))
            }
            totalCursor.close()

            val typeCursor = db.rawQuery(
                "SELECT event_type, COUNT(*) as cnt FROM events GROUP BY event_type ORDER BY cnt DESC LIMIT 10",
                null
            )
            val typeStats = JSONObject()
            while (typeCursor.moveToNext()) {
                typeStats.put(typeCursor.getString(0), typeCursor.getInt(1))
            }
            typeCursor.close()
            stats.put("by_type", typeStats)
        } catch (e: Exception) {
            android.util.Log.w("EventLogger", "Failed to get stats", e)
        }
        return stats
    }

    fun destroy() {
        isDestroyed = true
        handler.removeCallbacks(flushRunnable)
        flush()
        handlerThread.quitSafely()
    }

    fun getEvents(limit: Int = 100): List<Pair<String, JSONObject>> {
        return synchronized(batchLock) {
            batch.takeLast(limit.coerceAtMost(batch.size)).toList()
        }
    }
}
