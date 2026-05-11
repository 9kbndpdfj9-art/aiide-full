package com.aiide

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

data class Prediction(
    val text: String,
    val confidence: Double,
    val source: PredictionSource,
    val timestamp: Long = System.currentTimeMillis()
)

enum class PredictionSource {
    STRUCTURAL, PATTERN, CONTEXT, CROSS_FILE, HYBRID
}

data class PredictionContext(
    val beforeCursor: String = "",
    val afterCursor: String = "",
    val indentation: Int = 0,
    val language: String = "kotlin",
    val recentEdits: List<String> = emptyList()
)

class PredictiveZeroTokenEngine(private val context: Context) {
    companion object {
        private const val MAX_PREDICTIONS = 5
        private const val MAX_PATTERN_CACHE = 2000
        private const val CONFIDENCE_THRESHOLD = 0.4
        private const val PATTERN_WINDOW = 500
        private const val CROSS_FILE_WINDOW = 50
    }

    private val patternCache = ConcurrentHashMap<String, List<String>>()
    private val structuralCache = ConcurrentHashMap<String, List<String>>()
    private val contextCache = ConcurrentHashMap<String, List<String>>()
    private val adoptionTracker = ConcurrentHashMap<String, Pair<Int, Int>>()

    init {
        initializeStructuralPatterns()
        loadFromPersistence()
    }

    private fun initializeStructuralPatterns() {
        structuralCache["kotlin/function"] = listOf(
            "fun ${1}(${2}) {\n    ${3}\n}",
            "fun ${1}(${2}): ${3} {\n    return ${4}\n}",
            "private fun ${1}(${2}) {\n    ${3}\n}",
            "suspend fun ${1}(${2}) {\n    ${3}\n}"
        )
        structuralCache["kotlin/class"] = listOf(
            "class ${1}(${2}) {\n    ${3}\n}",
            "data class ${1}(${2})\n",
            "sealed class ${1}\n",
            "interface ${1}\n"
        )
        structuralCache["kotlin/control"] = listOf(
            "if (${1}) {\n    ${2}\n}",
            "when (${1}) {\n    ${2} -> ${3}\n}",
            "for (${1} in ${2}) {\n    ${3}\n}",
            "while (${1}) {\n    ${2}\n}"
        )
    }

    private fun loadFromPersistence() {
        try {
            val prefs = context.getSharedPreferences("zero_token_cache", Context.MODE_PRIVATE)
            prefs.getString("patterns", null)?.let {
                val json = JSONArray(it)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    patternCache[obj.getString("key")] = obj.getString("value").split("|")
                }
            }
        } catch (e: Exception) {
            Log.e("PredictiveZeroToken", "Failed to load cache: ${e.message}")
        }
    }

    private fun saveToPersistence() {
        try {
            val prefs = context.getSharedPreferences("zero_token_cache", Context.MODE_PRIVATE)
            val json = JSONArray()
            patternCache.forEach { (key, value) ->
                json.put(JSONObject().put("key", key).put("value", value.joinToString("|")))
            }
            prefs.edit().putString("patterns", json.toString()).apply()
        } catch (e: Exception) {
            Log.e("PredictiveZeroToken", "Failed to save cache: ${e.message}")
        }
    }

    fun predict(
        content: String,
        cursorPosition: Int,
        filePath: String = "",
        context: PredictionContext? = null
    ): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val ctx = context ?: extractContext(content, cursorPosition)
        
        predictions.addAll(predictStructural(content, ctx))
        predictions.addAll(predictFromPatterns(content, ctx))
        predictions.addAll(predictFromContext(content, ctx))
        
        if (filePath.isNotEmpty()) {
            predictions.addAll(predictFromCrossFile(content, ctx, filePath))
        }
        
        return predictions
            .sortedByDescending { it.confidence }
            .take(MAX_PREDICTIONS)
            .filter { it.confidence >= CONFIDENCE_THRESHOLD }
    }

    private fun extractContext(content: String, cursor: Int): PredictionContext {
        val before = if (cursor > 0) content.substring(max(0, cursor - PATTERN_WINDOW), cursor) else ""
        val after = if (cursor < content.length) content.substring(cursor, min(content.length, cursor + PATTERN_WINDOW)) else ""
        val indent = before.count { it == '\n' && before.length > 0 }
        val lang = when {
            content.substring(max(0, cursor - 1000), cursor).contains("class ") -> "kotlin"
            content.substring(max(0, cursor - 1000), cursor).contains("fun ") -> "kotlin"
            content.substring(max(0, cursor - 1000), cursor).contains("fn ") -> "rust"
            else -> "unknown"
        }
        return PredictionContext(before, after, indent, lang)
    }

    private fun predictStructural(content: String, ctx: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val lastLine = ctx.beforeCursor.split("\n").lastOrNull() ?: ""
        val trimmed = lastLine.trim()
        
        when {
            trimmed.startsWith("fun ") || trimmed.startsWith("private fun ") -> {
                structuralCache["kotlin/function"]?.forEach { pattern ->
                    predictions.add(Prediction(pattern, 0.75, PredictionSource.STRUCTURAL))
                }
            }
            trimmed.startsWith("class ") -> {
                structuralCache["kotlin/class"]?.forEach { pattern ->
                    predictions.add(Prediction(pattern, 0.8, PredictionSource.STRUCTURAL))
                }
            }
            trimmed.startsWith("if ") || trimmed.startsWith("for ") || trimmed.startsWith("while ") || trimmed.startsWith("when ") -> {
                structuralCache["kotlin/control"]?.forEach { pattern ->
                    predictions.add(Prediction(pattern, 0.7, PredictionSource.STRUCTURAL))
                }
            }
            trimmed.isEmpty() && ctx.indentation == 0 && ctx.beforeCursor.isNotEmpty() -> {
                predictions.add(Prediction("fun ${1}() {\n    ${2}\n}", 0.6, PredictionSource.STRUCTURAL))
            }
        }
        
        return predictions
    }

    private fun predictFromPatterns(content: String, ctx: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val recentText = ctx.beforeCursor.takeLast(200)
        
        patternCache.entries.take(100).forEach { (pattern, completions) ->
            if (recentText.contains(pattern.take(10))) {
                completions.take(3).forEach { completion ->
                    val confidence = calculatePatternConfidence(pattern, completion, ctx)
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        predictions.add(Prediction(completion, confidence, PredictionSource.PATTERN))
                    }
                }
            }
        }
        
        return predictions
    }

    private fun calculatePatternConfidence(pattern: String, completion: String, ctx: PredictionContext): Double {
        var confidence = 0.5
        if (completion.length > 5) confidence += 0.1
        if (ctx.beforeCursor.endsWith("\n")) confidence += 0.1
        if (ctx.indentation > 0) confidence += 0.05
        
        val adoption = adoptionTracker[pattern]
        if (adoption != null) {
            val (yes, total) = adoption
            if (total > 0) {
                confidence *= (0.5 + (yes.toDouble() / total) * 0.5)
            }
        }
        
        return confidence.coerceIn(0.0, 1.0)
    }

    private fun predictFromContext(content: String, ctx: PredictionContext): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val recentWords = ctx.beforeCursor.split(Regex("[^a-zA-Z0-9_]+")).takeLast(10)
        
        if (recentWords.size >= 2) {
            val lastWord = recentWords.lastOrNull() ?: ""
            val prevWord = recentWords.getOrNull(recentWords.size - 2) ?: ""
            
            val commonPairs = mapOf(
                "fun " to listOf("process", "handle", "execute", "calculate", "fetch", "load"),
                "val " to listOf("result", "data", "response", "value", "instance"),
                "if (" to listOf("it != null", "it.isEmpty()", "result != null", "success"),
                "return " to listOf("result", "null", "true", "false")
            )
            
            commonPairs.forEach { (prefix, suffixes) ->
                if (lastWord.startsWith(prefix.dropLast(1)) || prevWord == prefix.trim()) {
                    suffixes.forEach { suffix ->
                        predictions.add(Prediction(suffix, 0.55, PredictionSource.CONTEXT))
                    }
                }
            }
        }
        
        return predictions
    }

    private fun predictFromCrossFile(content: String, ctx: PredictionContext, filePath: String): List<Prediction> {
        val predictions = mutableListOf<Prediction>()
        val imports = extractImports(ctx.beforeCursor)
        
        imports.forEach { import ->
            val cached = contextCache["${import}_${ctx.language}"]
            if (cached != null) {
                cached.take(2).forEach { suggestion ->
                    predictions.add(Prediction(suggestion, 0.5, PredictionSource.CROSS_FILE))
                }
            }
        }
        
        return predictions
    }

    private fun extractImports(text: String): List<String> {
        val imports = mutableListOf<String>()
        val importRegex = Regex("""import\s+([a-zA-Z0-9_.]+)""")
        importRegex.findAll(text).forEach { match ->
            imports.add(match.groupValues[1])
        }
        return imports
    }

    fun recordAdoption(predictionSource: String, adopted: Boolean) {
        val current = adoptionTracker[predictionSource] ?: Pair(0, 0)
        val (yes, total) = current
        adoptionTracker[predictionSource] = if (adopted) {
            Pair(yes + 1, total + 1)
        } else {
            Pair(yes, total + 1)
        }
    }

    fun learnFromEdit(before: String, after: String, filePath: String) {
        if (after.length <= before.length) return
        
        val added = after.substring(before.length)
        if (added.length < 3 || added.length > 200) return
        
        val context = extractContext(after, after.indexOf(added))
        val contextKey = context.beforeCursor.takeLast(50)
        
        val existing = patternCache[contextKey] ?: emptyList()
        val updated = listOf(added) + existing.filter { it != added }.take(MAX_PATTERN_CACHE - 1)
        patternCache[contextKey] = updated
        
        if (patternCache.size > MAX_PATTERN_CACHE * 2) {
            cleanupOldPatterns()
        }
        
        saveToPersistence()
    }

    private fun cleanupOldPatterns() {
        val toRemove = patternCache.entries
            .sortedBy { adoptionTracker[it.key]?.second ?: 0 }
            .take(patternCache.size / 4)
            .map { it.key }
        toRemove.forEach { patternCache.remove(it) }
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "patternCacheSize" to patternCache.size,
            "structuralCacheSize" to structuralCache.size,
            "contextCacheSize" to contextCache.size,
            "adoptionTrackerSize" to adoptionTracker.size,
            "totalPredictions" to adoptionTracker.values.sumOf { it.second },
            "successfulAdoptions" to adoptionTracker.values.sumOf { it.first }
        )
    }
}
