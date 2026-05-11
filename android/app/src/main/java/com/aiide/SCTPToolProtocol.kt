package com.aiide

import org.json.*
import java.util.concurrent.ConcurrentHashMap

data class SCTPToolCall(
    val id: String,
    val tool: String,
    val params: Map<String, String> = emptyMap(),
    val context: String = "",
    val expect: String = "",
    val isDiff: Boolean = false
) {
    fun toCompactString(): String {
        return buildString {
            appendLine("# TOOL_CALL")
            appendLine("id: $id")
            appendLine("tool: $tool")
            if (params.isNotEmpty()) {
                appendLine("params: ${params.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (context.isNotEmpty()) {
                appendLine("ctx: $context")
            }
            if (expect.isNotEmpty()) {
                appendLine("expect: $expect")
            }
            if (isDiff) {
                appendLine("diff: true")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tool", tool)
        put("params", JSONObject(params))
        put("context", context)
        put("expect", expect)
        put("is_diff", isDiff)
    }

    fun estimateTokens(): Int = toCompactString().length / 4

    companion object {
        fun fromJson(json: JSONObject): SCTPToolCall {
            return SCTPToolCall(
                id = json.optString("id", ""),
                tool = json.optString("tool", ""),
                params = if (json.has("params")) {
                    val paramsObj = json.getJSONObject("params")
                    paramsObj.keys().asSequence().associateWith { paramsObj.optString(it, "") }
                } else emptyMap(),
                context = json.optString("context", ""),
                expect = json.optString("expect", ""),
                isDiff = json.optBoolean("is_diff", false)
            )
        }
    }
}

data class SCTPToolResult(
    val id: String,
    val ok: Boolean,
    val fingerprint: CompactToolFingerprint? = null,
    val depCount: Int = 0,
    val affectedFiles: List<String> = emptyList(),
    val errorMessage: String = "",
    val executionTimeMs: Long = 0
) {
    data class CompactToolFingerprint(
        val input: String = "",
        val output: String = "",
        val sideEffects: String = "",
        val dependencies: String = "",
        val calledBy: String = ""
    ) {
        fun toCompactString(): String {
            return buildString {
                append("i: {$input}, ")
                append("o: {$output}, ")
                append("s: [$sideEffects], ")
                append("d: [$dependencies], ")
                append("by: [$calledBy]")
            }
        }
    }

    fun toCompactString(): String {
        return buildString {
            appendLine("# TOOL_RESULT")
            appendLine("id: $id")
            appendLine("ok: $ok")
            if (fingerprint != null) {
                appendLine("fingerprint:")
                appendLine("  i: ${fingerprint.input}")
                appendLine("  o: ${fingerprint.output}")
                appendLine("  s: ${fingerprint.sideEffects}")
                appendLine("  d: ${fingerprint.dependencies}")
                appendLine("  by: ${fingerprint.calledBy}")
            }
            if (depCount > 0) {
                appendLine("dep_count: $depCount")
            }
            if (affectedFiles.isNotEmpty()) {
                appendLine("affected: [${affectedFiles.joinToString(", ")}]")
            }
            if (!ok && errorMessage.isNotEmpty()) {
                appendLine("error: $errorMessage")
            }
            appendLine("exec_time_ms: $executionTimeMs")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ok", ok)
        put("dep_count", depCount)
        put("affected_files", JSONArray(affectedFiles))
        put("error_message", errorMessage)
        put("execution_time_ms", executionTimeMs)
        fingerprint?.let { fp ->
            put("fingerprint", JSONObject().apply {
                put("i", fp.input)
                put("o", fp.output)
                put("s", fp.sideEffects)
                put("d", fp.dependencies)
                put("by", fp.calledBy)
            })
        }
    }

    fun estimateTokens(): Int = toCompactString().length / 4

    companion object {
        fun fromJson(json: JSONObject): SCTPToolResult {
            val fingerprint = if (json.has("fingerprint")) {
                val fpObj = json.getJSONObject("fingerprint")
                CompactToolFingerprint(
                    input = fpObj.optString("i", ""),
                    output = fpObj.optString("o", ""),
                    sideEffects = fpObj.optString("s", ""),
                    dependencies = fpObj.optString("d", ""),
                    calledBy = fpObj.optString("by", "")
                )
            } else null

            return SCTPToolResult(
                id = json.optString("id", ""),
                ok = json.optBoolean("ok", false),
                fingerprint = fingerprint,
                depCount = json.optInt("dep_count", 0),
                affectedFiles = if (json.has("affected_files")) {
                    val arr = json.getJSONArray("affected_files")
                    List(arr.length()) { i -> arr.optString(i) }
                } else emptyList(),
                errorMessage = json.optString("error_message", ""),
                executionTimeMs = json.optLong("execution_time_ms", 0)
            )
        }
    }
}

class SCTPToolProtocol {
    private val callCache = ConcurrentHashMap<String, SCTPToolCall>()
    private val callCacheTimestamps = ConcurrentHashMap<String, Long>()
    private val MAX_CACHE_SIZE = 50
    private val CACHE_TTL_MS = 5 * 60 * 1000L

    fun buildToolCall(
        toolName: String,
        params: Map<String, String>,
        context: String,
        expect: String
    ): SCTPToolCall {
        val lastCall = callCache[toolName]
        val isDiff = lastCall != null && params.keys == lastCall.params.keys
        
        val toolCall = SCTPToolCall(
            id = "tc_${System.currentTimeMillis() % 10000}",
            tool = toolName,
            params = if (isDiff && lastCall != null) {
                params.filter { (key, value) -> lastCall.params[key] != value }
            } else params,
            context = context,
            expect = expect,
            isDiff = isDiff
        )

        callCache[toolName] = toolCall
        callCacheTimestamps[toolName] = System.currentTimeMillis()
        while (callCache.size > MAX_CACHE_SIZE) {
            val oldestKey = callCacheTimestamps.entries.sortedBy { it.value }.firstOrNull()?.key
            if (oldestKey != null) {
                callCache.remove(oldestKey)
                callCacheTimestamps.remove(oldestKey)
            } else break
        }
        val now = System.currentTimeMillis()
        callCacheTimestamps.entries.removeIf { (_, ts) -> now - ts > CACHE_TTL_MS }
        callCache.keys.retainAll(callCacheTimestamps.keys)

        return toolCall
    }

    fun buildToolResult(
        callId: String,
        success: Boolean,
        fingerprint: SCTPToolResult.CompactToolFingerprint? = null,
        depCount: Int = 0,
        affectedFiles: List<String> = emptyList(),
        errorMessage: String = "",
        executionTimeMs: Long = 0
    ): SCTPToolResult {
        return SCTPToolResult(
            id = callId,
            ok = success,
            fingerprint = fingerprint,
            depCount = depCount,
            affectedFiles = affectedFiles,
            errorMessage = errorMessage,
            executionTimeMs = executionTimeMs
        )
    }

    fun calculateTokenSavings(sctpCall: SCTPToolCall, sctpResult: SCTPToolResult): Double {
        val traditionalEstimate = 350
        val sctpEstimate = sctpCall.estimateTokens() + sctpResult.estimateTokens()
        return ((1.0 - (sctpEstimate.toDouble() / traditionalEstimate)) * 100).coerceIn(0.0, 100.0)
    }
}
