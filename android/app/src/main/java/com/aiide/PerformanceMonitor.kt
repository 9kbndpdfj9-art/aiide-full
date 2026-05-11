package com.aiide

import android.util.Log
import org.json.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object PerformanceMonitor {
    private const val TAG = "PerfMonitor"
    private const val MAX_SLOW_OPS = 100

    private val opCounters = ConcurrentHashMap<String, AtomicLong>()
    private val opTimers = ConcurrentHashMap<String, AtomicLong>()
    private val opErrors = ConcurrentHashMap<String, AtomicLong>()
    private val slowOps = object : LinkedBlockingQueue<SlowOp>(MAX_SLOW_OPS) {
        fun addSafe(op: SlowOp): Boolean {
            if (remainingCapacity() == 0) poll()
            return offer(op)
        }
    }

    data class SlowOp(
        val engine: String,
        val operation: String,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    inline fun <T> track(engine: String, operation: String, block: () -> T): T {
        val key = "$engine.$operation"
        opCounters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val start = System.nanoTime()
        try {
            return block()
        } catch (e: Exception) {
            opErrors.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
            throw e
        } finally {
            val durationMs = (System.nanoTime() - start) / 1_000_000
            opTimers.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(durationMs)
            if (durationMs > 500) {
                slowOps.addSafe(SlowOp(engine, operation, durationMs))
                Log.w(TAG, "Slow op: $key took ${durationMs}ms")
            }
        }
    }

    fun getStats(): JSONObject {
        val stats = JSONObject()
        for ((key, counter) in opCounters) {
            val count = counter.get()
            val totalTime = opTimers[key]?.get() ?: 0L
            val errors = opErrors[key]?.get() ?: 0L
            stats.put(key, JSONObject().apply {
                put("count", count)
                put("total_ms", totalTime)
                put("avg_ms", if (count > 0) totalTime / count else 0)
                put("errors", errors)
                put("error_rate", if (count > 0) errors.toDouble() / count else 0.0)
            })
        }
        return stats
    }


    fun getSlowOps(): JSONArray {
        val arr = JSONArray()
        for (op in slowOps) {
            arr.put(JSONObject().apply {
                put("engine", op.engine)
                put("operation", op.operation)
                put("duration_ms", op.durationMs)
                put("timestamp", op.timestamp)
            })
        }
        return arr
    }

    fun reset() {
        opCounters.clear()
        opTimers.clear()
        opErrors.clear()
        slowOps.clear()
    }

    fun cleanupStaleKeys(maxKeys: Int = 200) {
        if (opCounters.size <= maxKeys) return
        val keysByCount = opCounters.entries.sortedBy { it.value.get() }
        val toRemove = keysByCount.take(keysByCount.size - maxKeys)
        for ((key, _) in toRemove) {
            opCounters.remove(key)
            opTimers.remove(key)
            opErrors.remove(key)
        }
    }
}