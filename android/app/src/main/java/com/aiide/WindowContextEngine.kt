package com.aiide

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class WindowContext(
    val windowId: String,
    val windowName: String,
    val summary: String,
    val projectDir: String,
    val activeIntents: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val metadata: MutableMap<String, Any> = ConcurrentHashMap()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("windowId", windowId)
        put("windowName", windowName)
        put("summary", summary)
        put("projectDir", projectDir)
        put("activeIntents", JSONArray(activeIntents))
        put("createdAt", createdAt)
        put("lastAccessed", lastAccessed)
        put("metadata", JSONObject(metadata))
    }
}

data class CrossWindowRequest(
    val requestId: String,
    val sourceWindowId: String,
    val targetWindowId: String,
    val action: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending"
)

data class WindowCoherence(
    val windowA: String,
    val windowB: String,
    val sharedIntents: List<String>,
    val coherenceScore: Double,
    val lastSync: Long
)

class WindowContextEngine(private val context: Context) {
    private val windows = ConcurrentHashMap<String, WindowContext>()
    private val crossWindowRequests = ConcurrentHashMap<String, CrossWindowRequest>()
    private val scheduledTasks = ConcurrentHashMap<String, Runnable>()
    private val windowCoherence = ConcurrentHashMap<String, WindowCoherence>()
    private var scheduler: ScheduledExecutorService? = null
    private var windowCounter = 0

    init {
        scheduler = ScheduledThreadPoolExecutor(2)
        loadWindows()
    }

    private fun loadWindows() {
        try {
            val prefs = context.getSharedPreferences("window_contexts", Context.MODE_PRIVATE)
            prefs.getString("windows", null)?.let {
                val json = JSONArray(it)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val window = WindowContext(
                        windowId = obj.getString("windowId"),
                        windowName = obj.getString("windowName"),
                        summary = obj.getString("summary"),
                        projectDir = obj.getString("projectDir"),
                        activeIntents = (0 until obj.getJSONArray("activeIntents").length()).map {
                            obj.getJSONArray("activeIntents").getString(it)
                        },
                        createdAt = obj.getLong("createdAt"),
                        lastAccessed = obj.getLong("lastAccessed")
                    )
                    windows[window.windowId] = window
                }
            }
        } catch (e: Exception) {
            Log.e("WindowContext", "Failed to load windows: ${e.message}")
        }
    }

    private fun saveWindows() {
        try {
            val prefs = context.getSharedPreferences("window_contexts", Context.MODE_PRIVATE)
            val json = JSONArray()
            windows.values.forEach { window ->
                json.put(window.toJSON())
            }
            prefs.edit().putString("windows", json.toString()).apply()
        } catch (e: Exception) {
            Log.e("WindowContext", "Failed to save windows: ${e.message}")
        }
    }

    fun createWindow(windowName: String, summary: String, projectDir: String = "", activeIntents: List<String> = emptyList()): WindowContext {
        val windowId = "window_${System.currentTimeMillis()}_${windowCounter++}"
        val window = WindowContext(
            windowId = windowId,
            windowName = windowName,
            summary = summary,
            projectDir = projectDir,
            activeIntents = activeIntents
        )
        
        windows[windowId] = window
        saveWindows()
        
        scheduleCoherenceCheck(windowId)
        
        Log.i("WindowContext", "Created window: $windowId")
        return window
    }

    fun closeWindow(windowId: String): Boolean {
        val removed = windows.remove(windowId)
        if (removed != null) {
            saveWindows()
            scheduledTasks[windowId]?.let { /* cancel task */ }
            scheduledTasks.remove(windowId)
            Log.i("WindowContext", "Closed window: $windowId")
            return true
        }
        return false
    }

    fun getWindow(windowId: String): WindowContext? {
        val window = windows[windowId]
        if (window != null) {
            val updated = window.copy(lastAccessed = System.currentTimeMillis())
            windows[windowId] = updated
        }
        return window
    }

    fun getAllWindows(): List<WindowContext> {
        return windows.values.toList().sortedByDescending { it.lastAccessed }
    }

    fun updateWindowSummary(windowId: String, summary: String) {
        windows[windowId]?.let { window ->
            windows[windowId] = window.copy(summary = summary, lastAccessed = System.currentTimeMillis())
            saveWindows()
        }
    }

    fun addIntentToWindow(windowId: String, intentId: String) {
        windows[windowId]?.let { window ->
            val updatedIntents = window.activeIntents + intentId
            windows[windowId] = window.copy(activeIntents = updatedIntents, lastAccessed = System.currentTimeMillis())
            saveWindows()
            recalculateCoherence(windowId)
        }
    }

    fun removeIntentFromWindow(windowId: String, intentId: String) {
        windows[windowId]?.let { window ->
            val updatedIntents = window.activeIntents.filter { it != intentId }
            windows[windowId] = window.copy(activeIntents = updatedIntents, lastAccessed = System.currentTimeMillis())
            saveWindows()
            recalculateCoherence(windowId)
        }
    }

    fun createCrossWindowRequest(
        sourceWindowId: String,
        targetWindowId: String,
        action: String,
        payload: String
    ): CrossWindowRequest? {
        if (!windows.containsKey(sourceWindowId) || !windows.containsKey(targetWindowId)) {
            return null
        }
        
        val request = CrossWindowRequest(
            requestId = "req_${System.currentTimeMillis()}",
            sourceWindowId = sourceWindowId,
            targetWindowId = targetWindowId,
            action = action,
            payload = payload
        )
        
        crossWindowRequests[request.requestId] = request
        
        val sourceWindow = windows[sourceWindowId]!!
        val targetWindow = windows[targetWindowId]!!
        
        updateWindowSummary(
            sourceWindowId,
            "${sourceWindow.summary}; Sent request to ${targetWindow.windowName}"
        )
        
        Log.i("WindowContext", "Cross-window request: ${request.requestId}")
        return request
    }

    fun getCrossWindowRequests(windowId: String): List<CrossWindowRequest> {
        return crossWindowRequests.values.filter {
            it.sourceWindowId == windowId || it.targetWindowId == windowId
        }
    }

    fun updateRequestStatus(requestId: String, status: String) {
        crossWindowRequests[requestId]?.let { request ->
            crossWindowRequests[requestId] = request.copy(status = status)
        }
    }

    private fun scheduleCoherenceCheck(windowId: String) {
        val task = Runnable {
            recalculateCoherence(windowId)
        }
        scheduledTasks[windowId] = task
        scheduler?.scheduleAtFixedRate(task, 30, 30, TimeUnit.SECONDS)
    }

    private fun recalculateCoherence(windowId: String) {
        val window = windows[windowId] ?: return
        
        windows.values.filter { it.windowId != windowId }.forEach { otherWindow ->
            val sharedIntents = window.activeIntents.intersect(otherWindow.activeIntents.toSet()).toList()
            val coherenceScore = if (window.activeIntents.isNotEmpty()) {
                sharedIntents.size.toDouble() / window.activeIntents.size
            } else 0.0
            
            val coherence = WindowCoherence(
                windowA = windowId,
                windowB = otherWindow.windowId,
                sharedIntents = sharedIntents,
                coherenceScore = coherenceScore,
                lastSync = System.currentTimeMillis()
            )
            
            val key = listOf(windowId, otherWindow.windowId).sorted().joinToString("-")
            windowCoherence[key] = coherence
        }
    }

    fun getCoherenceWith(windowId: String): List<WindowCoherence> {
        return windowCoherence.values.filter {
            it.windowA == windowId || it.windowB == windowId
        }
    }

    fun transferContext(sourceWindowId: String, targetWindowId: String, contextKeys: List<String>) {
        val source = windows[sourceWindowId] ?: return
        val target = windows[targetWindowId] ?: return
        
        val contextData = contextKeys.mapNotNull { key ->
            source.metadata[key]?.let { key to it }
        }.toMap()
        
        contextData.forEach { (key, value) ->
            target.metadata[key] = value
        }
        
        windows[targetWindowId] = target.copy(lastAccessed = System.currentTimeMillis())
        saveWindows()
        
        Log.i("WindowContext", "Transferred ${contextKeys.size} context items from $sourceWindowId to $targetWindowId")
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalWindows" to windows.size,
            "crossWindowRequests" to crossWindowRequests.size,
            "pendingRequests" to crossWindowRequests.values.count { it.status == "pending" },
            "coherenceLinks" to windowCoherence.size,
            "avgCoherenceScore" to windowCoherence.values.map { it.coherenceScore }.average().let {
                if (it.isNaN()) 0.0 else it
            }
        )
    }

    fun destroy() {
        scheduler?.shutdown()
        saveWindows()
    }
}
