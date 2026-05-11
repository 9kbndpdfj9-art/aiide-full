package com.aiide

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ToolGenomeEngine(private val context: Context) {

    data class ToolCall(
        val id: String,
        val toolName: String,
        val parameters: Map<String, Any>,
        val result: String,
        val success: Boolean,
        val durationMs: Long,
        val timestamp: Long
    )

    data class ToolGenome(
        val toolName: String,
        val callCount: Int,
        val successRate: Double,
        val avgDurationMs: Long,
        val patterns: List<String>,
        val score: Double
    )

    private val prefs: SharedPreferences = context.getSharedPreferences("tool_genome", Context.MODE_PRIVATE)
    private val callHistory = mutableListOf<ToolCall>()
    private val maxHistorySize = 1000

    init {
        loadHistory()
    }

    fun recordToolCall(
        toolName: String,
        parameters: Map<String, Any>,
        result: String,
        success: Boolean,
        durationMs: Long
    ) {
        val call = ToolCall(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            parameters = parameters,
            result = result,
            success = success,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )

        callHistory.add(call)
        if (callHistory.size > maxHistorySize) {
            callHistory.subList(0, callHistory.size - maxHistorySize).clear()
        }
        saveHistory()
    }

    fun getToolGenome(toolName: String): ToolGenome {
        val toolCalls = callHistory.filter { it.toolName == toolName }
        if (toolCalls.isEmpty()) {
            return ToolGenome(toolName, 0, 0.0, 0, emptyList(), 0.0)
        }

        val successCount = toolCalls.count { it.success }
        val successRate = successCount.toDouble() / toolCalls.size
        val avgDuration = toolCalls.map { it.durationMs }.average().toLong()

        val patterns = extractPatterns(toolCalls)
        val score = calculateScore(toolCalls.size, successRate, avgDuration)

        return ToolGenome(
            toolName = toolName,
            callCount = toolCalls.size,
            successRate = successRate,
            avgDurationMs = avgDuration,
            patterns = patterns,
            score = score
        )
    }

    fun getAllGenomes(): List<ToolGenome> {
        return callHistory.map { it.toolName }.distinct().map { getToolGenome(it) }
    }

    fun getTopTools(limit: Int = 10): List<ToolGenome> {
        return getAllGenomes().sortedByDescending { it.score }.take(limit)
    }

    fun getOptimizationSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        val genomes = getAllGenomes()

        genomes.filter { it.successRate < 0.8 }.forEach { genome ->
            suggestions.add("${genome.toolName}: Low success rate (${(genome.successRate * 100).toInt()}%). Consider improving error handling.")
        }

        genomes.filter { it.avgDurationMs > 5000 }.forEach { genome ->
            suggestions.add("${genome.toolName}: High latency (${genome.avgDurationMs}ms). Consider optimization.")
        }

        return suggestions
    }

    private fun extractPatterns(calls: List<ToolCall>): List<String> {
        val patterns = mutableListOf<String>()
        val successCalls = calls.filter { it.success }

        if (successCalls.size < 3) return patterns

        val paramKeys = successCalls.flatMap { it.parameters.keys }.groupingBy { it }.eachCount()
        val commonParams = paramKeys.filter { it.value >= successCalls.size / 2 }.keys
        patterns.add("Common params: ${commonParams.joinToString(", ")}")

        return patterns
    }

    private fun calculateScore(callCount: Int, successRate: Double, avgDurationMs: Long): Double {
        val countScore = (callCount.toDouble() / 100).coerceAtMost(1.0) * 0.3
        val successScore = successRate * 0.5
        val durationScore = (1.0 - (avgDurationMs.toDouble() / 10000).coerceAtMost(1.0)) * 0.2
        return countScore + successScore + durationScore
    }

    private fun saveHistory() {
        val jsonArray = JSONArray()
        callHistory.takeLast(100).forEach { call ->
            val obj = JSONObject().apply {
                put("id", call.id)
                put("toolName", call.toolName)
                put("result", call.result)
                put("success", call.success)
                put("durationMs", call.durationMs)
                put("timestamp", call.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("call_history", jsonArray.toString()).apply()
    }

    private fun loadHistory() {
        val jsonStr = prefs.getString("call_history", null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                callHistory.add(ToolCall(
                    id = obj.getString("id"),
                    toolName = obj.getString("toolName"),
                    parameters = emptyMap(),
                    result = obj.getString("result"),
                    success = obj.getBoolean("success"),
                    durationMs = obj.getLong("durationMs"),
                    timestamp = obj.getLong("timestamp")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
