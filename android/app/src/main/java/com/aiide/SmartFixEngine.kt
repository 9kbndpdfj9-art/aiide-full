package com.aiide

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class FixPattern(
    val id: String,
    val errorType: String,
    val pattern: String,
    val fixTemplate: String,
    val language: String = "generic",
    val successRate: Double = 0.5,
    val usageCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
)

data class FixSuggestion(
    val filePath: String,
    val line: Int,
    val column: Int,
    val errorType: String,
    val originalCode: String,
    val suggestedFix: String,
    val confidence: Double,
    val explanation: String
)

data class FixRecord(
    val id: String,
    val errorType: String,
    val originalCode: String,
    val fixedCode: String,
    val timestamp: Long = System.currentTimeMillis(),
    val adopted: Boolean = false,
    val source: String = "auto"
)

class SmartFixEngine(context: Context) {

    companion object {
        private const val PREFS_NAME = "smart_fix_engine"
        private const val KEY_PATTERNS = "patterns"
        private const val KEY_RECORDS = "fix_records"
        private const val MAX_RECORDS = 1000
        private val RE_DOLLAR_DIGIT = Regex("""\$\d+""")
        private const val TAG = "SmartFixEngine"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val patterns: ConcurrentHashMap<String, FixPattern> = ConcurrentHashMap()
    private val fixRecords: CopyOnWriteArrayList<FixRecord> = CopyOnWriteArrayList()

    init {
        initializeBuiltInPatterns()
        loadPersistedData()
    }

    fun analyzeCode(content: String, filePath: String): List<FixSuggestion> {
        val suggestions = mutableListOf<FixSuggestion>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            patterns.values.forEach { pattern ->
                try {
                    val regex = Regex(pattern.pattern, RegexOption.IGNORE_CASE)
                    regex.findAll(line).forEach { match ->
                        val confidence = calculateConfidence(pattern, line, match.value)
                        val fix = generateFix(pattern.fixTemplate, match.value, match, line)
                        suggestions.add(FixSuggestion(
                            filePath = filePath,
                            line = lineNum,
                            column = match.range.first + 1,
                            errorType = pattern.errorType,
                            originalCode = match.value,
                            suggestedFix = fix,
                            confidence = confidence,
                            explanation = buildExplanation(pattern, match.value)
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pattern match failed for '${pattern.errorType}' at line $lineNum: ${e.message}")
                }
            }
        }

        return suggestions.distinctBy { "${it.filePath}|${it.line}|${it.errorType}|${it.column}" }
            .sortedByDescending { it.confidence }
    }

    fun recordFix(errorType: String, originalCode: String, fixedCode: String, adopted: Boolean) {
        val record = FixRecord(
            id = generateRecordId(),
            errorType = errorType,
            originalCode = originalCode,
            fixedCode = fixedCode,
            adopted = adopted,
            source = if (adopted) "manual" else "auto"
        )
        fixRecords.add(record)

        if (adopted) {
            updatePatternSuccessRate(errorType, true)
        }
        persistRecords()
        trimRecords()
    }

    fun getFixSuggestions(errorType: String, code: String): List<FixSuggestion> {
        val matchingPatterns = patterns.values
            .filter { it.errorType.equals(errorType, ignoreCase = true) }
            .sortedByDescending { it.successRate }

        return matchingPatterns.mapNotNull { pattern ->
            try {
                val regex = Regex(pattern.pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(code)
                if (match != null) {
                    FixSuggestion(
                        filePath = "",
                        line = 0,
                        column = match.range.first + 1,
                        errorType = pattern.errorType,
                        originalCode = match.value,
                        suggestedFix = generateFix(pattern.fixTemplate, match.value, match, code),
                        confidence = pattern.successRate,
                        explanation = buildExplanation(pattern, match.value)
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun applyAutoFixes(content: String, filePath: String, minConfidence: Double = 0.7): String {
        val suggestions = analyzeCode(content, filePath)
            .filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }

        val appliedFixes = mutableMapOf<Int, MutableMap<String, FixSuggestion>>()
        suggestions.forEach { suggestion ->
            appliedFixes.getOrPut(suggestion.line) { mutableMapOf() }[suggestion.errorType] = suggestion
        }

        val lines = content.lines().toMutableList()
        appliedFixes.toSortedMap().reversed().forEach { (lineNum, fixes) ->
            val lineIndex = lineNum - 1
            if (lineIndex in lines.indices) {
                var line = lines[lineIndex]
                fixes.values.sortedByDescending { it.confidence }.forEach { fix ->
                    try {
                        val escaped = Regex.escape(fix.originalCode)
                        val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                        line = regex.replaceFirst(line, fix.suggestedFix)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply fix at line $lineNum: ${e.message}")
                    }
                }
                lines[lineIndex] = line
            }
        }

        return lines.joinToString("\n")
    }

    fun getFixHistory(limit: Int = 50): List<FixRecord> {
        return fixRecords.sortedByDescending { it.timestamp }.take(limit)
    }

    fun getTopFixPatterns(): List<FixPattern> {
        return patterns.values
            .filter { it.usageCount > 0 }
            .sortedWith(
                compareByDescending<FixPattern> { it.successRate }
                    .thenByDescending { it.usageCount }
            )
            .take(20)
    }

    fun exportPatterns(): String {
        val jsonArray = JSONArray()
        patterns.values.forEach { pattern ->
            val obj = JSONObject()
            obj.put("id", pattern.id)
            obj.put("errorType", pattern.errorType)
            obj.put("pattern", pattern.pattern)
            obj.put("fixTemplate", pattern.fixTemplate)
            obj.put("language", pattern.language)
            obj.put("successRate", pattern.successRate)
            obj.put("usageCount", pattern.usageCount)
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    fun importPatterns(jsonString: String): Boolean {
        return try {
            val jsonArray = JSONArray(jsonString)
            var imported = 0
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val pattern = FixPattern(
                    id = json.optString("id", ""),
                    errorType = json.optString("errorType", ""),
                    pattern = json.optString("pattern", ""),
                    fixTemplate = json.optString("fixTemplate", ""),
                    language = json.optString("language", "generic"),
                    successRate = json.optDouble("successRate", 0.5),
                    usageCount = json.optInt("usageCount", 0)
                )
                if (pattern.id.isNotEmpty() && pattern.pattern.isNotEmpty()) {
                    patterns[pattern.id] = pattern
                    imported++
                }
            }
            if (imported > 0) persistPatterns()
            imported > 0
        } catch (_: Exception) {
            false
        }
    }

    fun cleanup() {
        patterns.clear()
        fixRecords.clear()
        prefs.edit().clear().apply()
    }

    private fun initializeBuiltInPatterns() {
        val builtInPatterns = listOf(
            FixPattern("null_ptr_1", "null_pointer", """(\w+)\s*!=\s*null\s*\?\s*\1\.\w+""", "$1?.run { ... }", "kotlin", 0.85),
            FixPattern("null_ptr_2", "null_pointer", """(\w+)\s*==\s*null\s*\?\s*(\w+)\s*:\s*(\w+)""", "$1 ?: $3", "kotlin", 0.90),
            FixPattern("deprecated_api_1", "deprecated_api", """\bgetActivity\(\)""", "requireActivity()", "kotlin", 0.88),
            FixPattern("string_concat_1", "string_concatenation_in_loop", """for\s*\([^)]*\)\s*\{[^}]*\+=""", "Use StringBuilder", "kotlin", 0.95),
            FixPattern("empty_catch_1", "empty_catch_block", """catch\s*\([^)]*\)\s*\{\s*\}""", "Add error handling", "kotlin", 0.95),
            FixPattern("type_conversion_1", "type_conversion", """Integer\.parseInt""", "string.toIntOrNull()", "kotlin", 0.85)
        )
        builtInPatterns.forEach { patterns[it.id] = it }
    }

    private fun loadPersistedData() {
        loadPatterns()
        loadRecords()
    }

    private fun loadPatterns() {
        try {
            val jsonStr = prefs.getString(KEY_PATTERNS, null)
            if (jsonStr != null) {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    val pattern = FixPattern(
                        id = json.optString("id", ""),
                        errorType = json.optString("errorType", ""),
                        pattern = json.optString("pattern", ""),
                        fixTemplate = json.optString("fixTemplate", ""),
                        language = json.optString("language", "generic"),
                        successRate = json.optDouble("successRate", 0.5),
                        usageCount = json.optInt("usageCount", 0)
                    )
                    if (pattern.id.isNotEmpty()) {
                        patterns[pattern.id] = pattern
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load patterns", e)
        }
    }

    private fun loadRecords() {
        try {
            val jsonStr = prefs.getString(KEY_RECORDS, null)
            if (jsonStr != null) {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    val record = FixRecord(
                        id = json.optString("id", ""),
                        errorType = json.optString("errorType", ""),
                        originalCode = json.optString("originalCode", ""),
                        fixedCode = json.optString("fixedCode", ""),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        adopted = json.optBoolean("adopted", false),
                        source = json.optString("source", "auto")
                    )
                    if (record.id.isNotEmpty()) {
                        fixRecords.add(record)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load records", e)
        }
    }

    private fun persistPatterns() {
        try {
            val jsonArray = JSONArray()
            patterns.values.forEach { pattern ->
                val obj = JSONObject()
                obj.put("id", pattern.id)
                obj.put("errorType", pattern.errorType)
                obj.put("pattern", pattern.pattern)
                obj.put("fixTemplate", pattern.fixTemplate)
                obj.put("language", pattern.language)
                obj.put("successRate", pattern.successRate)
                obj.put("usageCount", pattern.usageCount)
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_PATTERNS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist patterns", e)
        }
    }

    private fun persistRecords() {
        try {
            val jsonArray = JSONArray()
            fixRecords.forEach { record ->
                val obj = JSONObject()
                obj.put("id", record.id)
                obj.put("errorType", record.errorType)
                obj.put("originalCode", record.originalCode)
                obj.put("fixedCode", record.fixedCode)
                obj.put("timestamp", record.timestamp)
                obj.put("adopted", record.adopted)
                obj.put("source", record.source)
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist records", e)
        }
    }

    private fun trimRecords() {
        if (fixRecords.size > MAX_RECORDS) {
            val sorted = fixRecords.sortedByDescending { it.timestamp }
            val toKeep = sorted.take(MAX_RECORDS)
            fixRecords.clear()
            fixRecords.addAll(toKeep)
            persistRecords()
        }
    }

    private fun calculateConfidence(pattern: FixPattern, line: String, match: String): Double {
        var confidence = pattern.successRate
        val usageBoost = minOf(pattern.usageCount * 0.01, 0.15)
        confidence += usageBoost
        val lengthRatio = match.length.toDouble() / line.length.toDouble()
        if (lengthRatio > 0.8) confidence += 0.05
        else if (lengthRatio > 0.5) confidence += 0.03
        return minOf(confidence, 1.0)
    }

    private fun generateFix(template: String, match: String, matchResult: MatchResult, fullLine: String): String {
        var result = template
        for (group in matchResult.groupValues.drop(1).withIndex()) {
            val placeholder = "$${group.index + 1}"
            if (group.value.isNotEmpty()) {
                result = result.replace(placeholder, group.value)
            } else {
                result = result.replace(placeholder, "")
            }
        }
        result = RE_DOLLAR_DIGIT.replace(result, "")
        return result
    }

    private fun buildExplanation(pattern: FixPattern, match: String): String {
        return when (pattern.errorType) {
            "null_pointer" -> "Potential null pointer exception. Use safe call operator (?.) or elvis operator (?:)."
            "unused_import" -> "Unused import detected. Remove unused imports."
            "deprecated_api" -> "Deprecated API usage detected. Update to recommended replacement."
            "memory_leak" -> "Potential memory leak. Ensure resources are properly closed."
            "string_concatenation_in_loop" -> "Use StringBuilder for better performance."
            "empty_catch_block" -> "Empty catch block swallows exceptions. Add logging."
            else -> "Code issue detected: ${pattern.errorType}."
        }
    }

    private fun updatePatternSuccessRate(errorType: String, adopted: Boolean) {
        patterns.keys.filter { key ->
            patterns[key]?.errorType == errorType
        }.forEach { key ->
            patterns.computeIfPresent(key) { _, pattern ->
                val currentTotal = pattern.usageCount
                val currentSuccess = pattern.successRate * currentTotal
                val newTotal = currentTotal + 1
                val newSuccess = if (adopted) currentSuccess + 1.0 else currentSuccess
                val newRate = if (newTotal > 0) newSuccess / newTotal else 0.0
                pattern.copy(usageCount = newTotal, successRate = newRate, lastUsed = System.currentTimeMillis())
            }
        }
    }

    private val recordCounter = AtomicLong(0L)
    private fun generateRecordId(): String {
        return "fix_${System.currentTimeMillis()}_${recordCounter.incrementAndGet()}"
    }
}
