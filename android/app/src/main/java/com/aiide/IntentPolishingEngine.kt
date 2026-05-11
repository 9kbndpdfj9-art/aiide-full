package com.aiide

import org.json.JSONObject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class IntentPolishingEngine { private val polishedCounter = AtomicInteger(0)
    private val polishHistory = object : LinkedHashMap<String, PolishResult>(32, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean { return size > 200 } }
    private val patternCache = ConcurrentHashMap<String, List<PolishSuggestion>>()

    init { loadPolishPatterns() }
    private fun loadPolishPatterns() { patternCache["含糊"] = listOf(PolishSuggestion("添加时间维度", "添加具体时间要求，如'三天内完成'", 0.9), PolishSuggestion("添加范围", "添加具体的范围，如'用户模块内'", 0.85)); patternCache["模糊"] = listOf(PolishSuggestion("指定具体对象", "明确指出具体的功能对象", 0.9), PolishSuggestion("量化指标", "添加具体的量化指标", 0.8)); patternCache["岐义"] = listOf(PolishSuggestion("区分主体", "明确是哪个模块/系统", 0.9), PolishSuggestion("补充条件", "添加约束条件消除歧义", 0.85)); patternCache["不完整"] = listOf(PolishSuggestion("补全操作", "明确是增删改查哪种操作", 0.9), PolishSuggestion("补全宾语", "明确操作的具体对象", 0.9)) }

    fun polishIntent(rawIntent: String): PolishResult { if (rawIntent.length < 3) return PolishResult(rawIntent, emptyList(), false, "Intent too short"); val issues = detectIssues(rawIntent); if (issues.isEmpty()) return PolishResult(rawIntent, emptyList(), true, "Intent is clear"); val suggestions = generateSuggestions(rawIntent, issues); val polishedText = applySuggestions(rawIntent, suggestions); val polished = polishHistory[polishedText] ?: PolishResult(polishedText, suggestions, polishedText != rawIntent, "${suggestions.size} issues resolved"); polishHistory[polishedText] = polished; return polished }

    private fun detectIssues(intent: String): List<IntentIssue> { val issues = mutableListOf<IntentIssue>(); val lower = intent.lowercase(); if (lower.contains("优化") && !lower.contains("什么") && !lower.contains("哪些")) issues.add(IntentIssue("含糊", "缺少优化目标", 0.7)); if (lower.contains("修改") && !lower.contains("为") && !lower.contains("成")) issues.add(IntentIssue("不完整", "缺少修改后的目标", 0.8)); if (lower.contains("添加") && !lower.contains("到") && !lower.contains("中")) issues.add(IntentIssue("不完整", "缺少目标位置", 0.7)); if (lower.contains("删除") && lower.count { it == '文件' || it == '模块' || it == '函数' } > 1) issues.add(IntentIssue("岐义", "可能有多个删除目标", 0.6)); return issues }

    private fun generateSuggestions(intent: String, issues: List<IntentIssue>): List<PolishSuggestion> { val suggestions = mutableListOf<PolishSuggestion>(); for (issue in issues) { val cached = patternCache[issue.type]; if (cached != null) suggestions.addAll(cached) }; return suggestions.distinctBy { it.suggestion }.take(5) }

    private fun applySuggestions(intent: String, suggestions: List<PolishSuggestion>): String { if (suggestions.isEmpty()) return intent; val parts = intent.split(" ", limit = 2); return if (parts.size > 1) "${parts[0]} ${suggestions.first().suggestion} ${parts[1]}" else intent }

    fun getPolishHistory(): List<PolishResult> = polishHistory.values.toList().takeLast(20).reversed()
    fun getIssueTypes(): List<String> = listOf("含糊", "模糊", "岐义", "不完整")
    fun getStats(): JSONObject = JSONObject().apply { put("total_polished", polishedCounter.get()); put("history_size", polishHistory.size) }

    data class IntentIssue(val type: String, val description: String, val severity: Double)
    data class PolishSuggestion(val category: String, val suggestion: String, val confidence: Double)
    data class PolishResult(val polishedIntent: String, val suggestions: List<PolishSuggestion>, val improved: Boolean, val message: String)
}