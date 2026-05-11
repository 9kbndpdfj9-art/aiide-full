package com.aiide

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class AutoTriggerMode {
    AGGRESSIVE, BALANCED, CONSERVATIVE, OFF
}

data class AutoSkillSuggestion(
    val skillId: String,
    val skillName: String,
    val confidence: Double,
    val reason: String,
    val applyMode: String,
    val autoApplyable: Boolean,
    val estimatedImpact: String
) {
    fun toJson(): String {
        val safeReason = reason
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val safeName = skillName
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val safeImpact = estimatedImpact
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """{"skill_id": "$skillId", "skill_name": "$safeName", "confidence": $confidence, "reason": "$safeReason", "apply_mode": "$applyMode", "auto_applyable": $autoApplyable, "estimated_impact": "$safeImpact"}"""
    }
}

data class SkillInvocationRecord(
    val id: String,
    val skillId: String,
    val triggerType: String,
    val query: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("skill_id", skillId)
        put("trigger_type", triggerType)
        put("query", query)
        put("success", success)
        put("execution_time_ms", executionTimeMs)
        put("timestamp", timestamp)
    }
}

class AutoSkillInvoker(
    private val semanticMatcher: SemanticSkillMatcher? = null,
    private var triggerMode: AutoTriggerMode = AutoTriggerMode.BALANCED
) {
    companion object {
        private const val TAG = "AutoSkillInvoker"
        private const val MAX_HISTORY = 500
        private const val MAX_STATS_SIZE = 200
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5
        private const val LOW_CONFIDENCE_THRESHOLD = 0.3

        private val AUTO_APPLICABLE_PATTERNS = listOf(
            Regex("""(?:web|网站|网页|页面|ui|界面|前端)""") to "web-ui",
            Regex("""(?:api|接口|后端|服务器|数据库|rest)""") to "backend",
            Regex("""(?:测试|test|spec|unit)""") to "test",
            Regex("""(?:重构|优化|性能|clean)""") to "refactor",
            Regex("""(?:文档|注释|readme|说明)""") to "docs",
            Regex("""(?:bug|错误|报错|fix|debug)""") to "debug",
            Regex("""(?:跨文件|多个文件|多文件|跨模块)""") to "cross-file"
        )

        private val CONTEXT_HINTS = mapOf(
            "web-ui" to listOf("html", "css", "js", "react", "vue", "页面", "响应式", "布局", "组件", "landing"),
            "backend" to listOf("node", "express", "api", "database", "sql", "mongo", "认证", "登录"),
            "test" to listOf("jest", "pytest", "junit", "mock", "assert", "coverage"),
            "refactor" to listOf("优化", "性能", "重构", "clean code", "design pattern"),
            "docs" to listOf("文档", "readme", "comment", "说明", "api doc"),
            "debug" to listOf("bug", "错误", "报错", "crash", "not working"),
            "cross-file" to listOf("跨文件", "多个文件", "依赖", "引用", "import"),
            "fullstack" to listOf("全栈", "完整", "前后端", "end-to-end", "full app")
        )
    }

    @Volatile private var isShutdown = false
    private val invocationHistory = CopyOnWriteArrayList<SkillInvocationRecord>()
    private val invocationCounter = AtomicLong(0)
    private val skillUsageStats = ConcurrentHashMap<String, SkillUsageStat>()
    private val evictionCounter = AtomicInteger(0)

    data class SkillUsageStat(
        val totalInvocations: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val avgExecutionTimeMs: Double = 0.0,
        val lastUsed: Long = 0,
        val autoTriggeredCount: Int = 0,
        val manualTriggeredCount: Int = 0
    ) {
        fun withInvocation(success: Boolean, executionTimeMs: Long, triggeredBy: String): SkillUsageStat {
            val newTotal = totalInvocations + 1
            val newAvg = (avgExecutionTimeMs * totalInvocations + executionTimeMs) / newTotal
            return copy(
                totalInvocations = newTotal,
                successCount = if (success) successCount + 1 else successCount,
                failureCount = if (success) failureCount else failureCount + 1,
                avgExecutionTimeMs = newAvg,
                lastUsed = System.currentTimeMillis(),
                autoTriggeredCount = if (triggeredBy == "auto") autoTriggeredCount + 1 else autoTriggeredCount,
                manualTriggeredCount = if (triggeredBy == "manual") manualTriggeredCount + 1 else manualTriggeredCount
            )
        }
    }

    fun analyzeAndSuggest(query: String, context: Map<String, String> = emptyMap()): List<AutoSkillSuggestion> {
        if (isShutdown) return emptyList()
        if (query.isBlank()) return emptyList()
        if (triggerMode == AutoTriggerMode.OFF) return emptyList()

        val suggestions = mutableListOf<AutoSkillSuggestion>()

        val semanticMatches = semanticMatcher?.fuzzyMatch(query) ?: emptyList()
        for (match in semanticMatches) {
            val adjustedScore = adjustScoreByMode(match.score)
            if (adjustedScore < getThresholdForMode()) continue

            val autoApplyable = shouldAutoApply(match, context)
            val reason = buildSuggestionReason(match, query, context)

            suggestions.add(AutoSkillSuggestion(
                skillId = match.skill.id,
                skillName = match.skill.name,
                confidence = adjustedScore,
                reason = reason,
                applyMode = if (autoApplyable) "auto" else "suggest",
                autoApplyable = autoApplyable,
                estimatedImpact = estimateImpact(match.skill)
            ))
        }

        if (suggestions.isEmpty()) {
            val patternMatch = matchByPatterns(query, context)
            if (patternMatch != null) {
                suggestions.add(patternMatch)
            }
        }

        return suggestions.sortedByDescending { it.confidence }
    }

    fun shouldAutoTrigger(query: String, context: Map<String, String> = emptyMap()): Boolean {
        if (triggerMode == AutoTriggerMode.OFF) return false
        if (query.isBlank()) return false

        val suggestions = analyzeAndSuggest(query, context)
        return suggestions.any { it.autoApplyable && it.confidence >= getAutoTriggerThreshold() }
    }

    fun getAutoTriggerSkill(query: String, context: Map<String, String> = emptyMap()): AutoSkillSuggestion? {
        if (triggerMode == AutoTriggerMode.OFF) return null
        if (query.isBlank()) return null

        val suggestions = analyzeAndSuggest(query, context)
        return suggestions.find { it.autoApplyable && it.confidence >= getAutoTriggerThreshold() }
    }

    fun recordInvocation(skillId: String, query: String, success: Boolean, executionTimeMs: Long, triggeredBy: String = "manual") {
        require(skillId.isNotBlank()) { "skillId must not be blank" }
        require(executionTimeMs >= 0) { "executionTimeMs must not be negative" }

        val record = SkillInvocationRecord(
            id = "inv_${invocationCounter.incrementAndGet()}",
            skillId = skillId,
            triggerType = triggeredBy,
            query = query,
            success = success,
            executionTimeMs = executionTimeMs
        )

        invocationHistory.add(record)
        if (invocationHistory.size > MAX_HISTORY) {
            invocationHistory.removeAt(0)
        }

        skillUsageStats.merge(skillId, SkillUsageStat().withInvocation(success, executionTimeMs, triggeredBy)) { existing, update ->
            existing.withInvocation(success, executionTimeMs, triggeredBy)
        }

        val currentSize = skillUsageStats.size
        if (currentSize > MAX_STATS_SIZE && evictionCounter.incrementAndGet() % 50 == 0) {
            evictLeastUsedStats()
        }
    }

    private fun evictLeastUsedStats() {
        val sortedEntries = skillUsageStats.entries
            .sortedBy { it.value.lastUsed }
            .take(skillUsageStats.size / 4)
        for (entry in sortedEntries) {
            skillUsageStats.remove(entry.key)
        }
        Log.i(TAG, "Evicted ${sortedEntries.size} least-used skill stats")
    }

    fun setTriggerMode(mode: AutoTriggerMode) {
        triggerMode = mode
        Log.i(TAG, "Trigger mode changed to $mode")
    }

    fun getTriggerMode(): AutoTriggerMode = triggerMode

    fun getUsageStats(skillId: String? = null): String {
        return if (skillId != null) {
            val stat = skillUsageStats[skillId]
            if (stat != null) JSONObject().apply {
                put("success", true)
                put("skill_id", skillId)
                put("total_invocations", stat.totalInvocations)
                put("success_count", stat.successCount)
                put("failure_count", stat.failureCount)
                put("success_rate", if (stat.totalInvocations > 0) stat.successCount.toDouble() / stat.totalInvocations else 0.0)
                put("avg_execution_time_ms", stat.avgExecutionTimeMs)
                put("auto_triggered", stat.autoTriggeredCount)
                put("manual_triggered", stat.manualTriggeredCount)
                put("last_used", stat.lastUsed)
            }.toString()
            else JSONObject().put("success", false).put("error", "Skill not found: $skillId").toString()
        } else {
            val arr = JSONArray()
            skillUsageStats.forEach { (id, stat) ->
                arr.put(JSONObject().apply {
                    put("skill_id", id)
                    put("total_invocations", stat.totalInvocations)
                    put("success_rate", if (stat.totalInvocations > 0) stat.successCount.toDouble() / stat.totalInvocations else 0.0)
                    put("auto_triggered", stat.autoTriggeredCount)
                    put("manual_triggered", stat.manualTriggeredCount)
                })
            }
            JSONObject().apply {
                put("success", true)
                put("stats", arr)
                put("total_invocations", invocationHistory.size)
            }.toString()
        }
    }

    fun getRecentInvocations(limit: Int = 20): String {
        val clampedLimit = limit.coerceAtLeast(1)
        val recent = invocationHistory.takeLast(clampedLimit)
        val arr = JSONArray()
        recent.forEach { record -> arr.put(record.toJson()) }
        return JSONObject().apply {
            put("success", true)
            put("invocations", arr)
        }.toString()
    }

    private fun adjustScoreByMode(score: Double): Double {
        return when (triggerMode) {
            AutoTriggerMode.AGGRESSIVE -> score * 1.2
            AutoTriggerMode.BALANCED -> score
            AutoTriggerMode.CONSERVATIVE -> score * 0.8
            AutoTriggerMode.OFF -> 0.0
        }.coerceIn(0.0, 1.0)
    }

    private fun getThresholdForMode(): Double {
        return when (triggerMode) {
            AutoTriggerMode.AGGRESSIVE -> LOW_CONFIDENCE_THRESHOLD
            AutoTriggerMode.BALANCED -> MEDIUM_CONFIDENCE_THRESHOLD
            AutoTriggerMode.CONSERVATIVE -> HIGH_CONFIDENCE_THRESHOLD
            AutoTriggerMode.OFF -> Double.MAX_VALUE
        }
    }

    private fun getAutoTriggerThreshold(): Double {
        return when (triggerMode) {
            AutoTriggerMode.AGGRESSIVE -> MEDIUM_CONFIDENCE_THRESHOLD
            AutoTriggerMode.BALANCED -> HIGH_CONFIDENCE_THRESHOLD
            AutoTriggerMode.CONSERVATIVE -> 0.85
            AutoTriggerMode.OFF -> Double.MAX_VALUE
        }
    }

    private fun shouldAutoApply(match: SemanticSkillMatch, context: Map<String, String>): Boolean {
        if (match.confidence == MatchConfidence.EXACT) return true
        if (match.confidence == MatchConfidence.HIGH && match.score >= 0.8) return true

        val contextHint = CONTEXT_HINTS[match.skill.id]
        if (contextHint != null) {
            val contextMatches = context.any { (_, value) ->
                contextHint.any { hint -> value.lowercase().contains(hint) }
            }
            if (contextMatches && match.score >= 0.6) return true
        }

        return false
    }

    private fun buildSuggestionReason(match: SemanticSkillMatch, query: String, context: Map<String, String>): String {
        val parts = mutableListOf<String>()

        if (match.reasons.isNotEmpty()) {
            parts.addAll(match.reasons)
        }

        parts.add("基于查询: \"${query.take(80)}\"")

        val contextInfo = context.entries.map { (key, value) ->
            "$key: ${value.take(50)}"
        }
        if (contextInfo.isNotEmpty()) {
            parts.add("上下文: ${contextInfo.joinToString("; ")}")
        }

        return parts.joinToString("；")
    }

    private fun estimateImpact(skill: SkillDefinition): String {
        return when (skill.id) {
            "web-ui" -> "将提升UI生成质量和响应式设计"
            "backend" -> "将确保API和数据库代码的规范性"
            "test" -> "将生成完整的测试用例和覆盖率报告"
            "refactor" -> "将优化代码结构和性能"
            "docs" -> "将生成规范的文档和注释"
            "debug" -> "将系统化诊断和修复问题"
            "cross-file" -> "将协调多个文件的修改，避免冲突"
            "fullstack" -> "将完整覆盖前后端开发流程"
            else -> "将提升代码质量和规范性"
        }
    }

    private fun matchByPatterns(query: String, context: Map<String, String>): AutoSkillSuggestion? {
        val queryLower = query.lowercase()

        var matchCount = 0
        for ((pattern, skillId) in AUTO_APPLICABLE_PATTERNS) {
            if (pattern.containsMatchIn(queryLower)) {
                matchCount++
                val baseConfidence = MEDIUM_CONFIDENCE_THRESHOLD + 0.05 * matchCount.coerceAtMost(3)
                return AutoSkillSuggestion(
                    skillId = skillId,
                    skillName = skillId.replace("-", " ").replaceFirstChar { it.uppercase() },
                    confidence = baseConfidence.coerceAtMost(0.75),
                    reason = "模式匹配：查询中包含相关关键词",
                    applyMode = "suggest",
                    autoApplyable = false,
                    estimatedImpact = "检测到相关任务类型，建议使用对应技能"
                )
            }
        }

        val contextSkill = context.values.flatMap { value ->
            CONTEXT_HINTS.entries.mapNotNull { (skillId, hints) ->
                if (hints.any { value.lowercase().contains(it) }) skillId else null
            }
        }.firstOrNull()

        if (contextSkill != null) {
            return AutoSkillSuggestion(
                skillId = contextSkill,
                skillName = contextSkill.replace("-", " ").replaceFirstChar { it.uppercase() },
                confidence = LOW_CONFIDENCE_THRESHOLD,
                reason = "上下文匹配：当前环境适合此技能",
                applyMode = "suggest",
                autoApplyable = false,
                estimatedImpact = "基于上下文推荐"
            )
        }

        return null
    }

    fun shutdown() {
        if (!isShutdown) {
            isShutdown = true
            skillUsageStats.clear()
            invocationHistory.clear()
        }
    }
}