package com.aiide

import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SkillEvolver {

    companion object {
        private const val MIN_SUCCESS_RATE_TO_KEEP = 0.3
        private const val HIGH_SUCCESS_RATE = 0.8
        private const val EVOLUTION_THRESHOLD = 5
        private const val MAX_SKILL_VERSION = 100
    }

    data class SkillRecord(
        val id: String,
        var version: Int = 1,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var totalTokens: Long = 0,
        var avgTokensPerRun: Double = 0.0,
        var triggers: List<String> = emptyList(),
        var lastUsed: Long = 0L,
        var createdAt: Long = System.currentTimeMillis(),
        var fusedFrom: List<String>? = null
    ) {
        val successRate: Double
            get() {
                val total = successCount + failureCount
                return if (total == 0) 0.5 else successCount.toDouble() / total
            }

        val totalRuns: Int
            get() = successCount + failureCount

        val tokenEfficiency: Double
            get() = if (totalRuns == 0) 0.0 else totalTokens.toDouble() / totalRuns

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("version", version)
                put("successCount", successCount)
                put("failureCount", failureCount)
                put("successRate", successRate)
                put("totalTokens", totalTokens)
                put("avgTokensPerRun", avgTokensPerRun)
                put("tokenEfficiency", tokenEfficiency)
            }
        }
    }

    data class EvolutionSuggestion(
        val skillId: String,
        val type: String,
        val reason: String,
        val action: String,
        val confidence: Double
    )

    private val skillRecords = ConcurrentHashMap<String, SkillRecord>()
    private val usagePatterns = CopyOnWriteArrayList<UsageEvent>()
    private val evolutionSuggestions = CopyOnWriteArrayList<EvolutionSuggestion>()

    data class UsageEvent(
        val skillId: String,
        val success: Boolean,
        val tokensUsed: Int,
        val userFeedback: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun recordUsage(skillId: String, success: Boolean, tokensUsed: Int = 0, userFeedback: Int = 0) {
        val record = skillRecords.computeIfAbsent(skillId) {
            SkillRecord(id = skillId, triggers = emptyList())
        }

        if (success) record.successCount++ else record.failureCount++
        record.totalTokens += tokensUsed
        val total = record.successCount + record.failureCount
        if (total > 0) {
            record.avgTokensPerRun = record.totalTokens.toDouble() / total
        }
        record.lastUsed = System.currentTimeMillis()

        usagePatterns.add(UsageEvent(skillId, success, tokensUsed, userFeedback))

        if (usagePatterns.size > 10000) {
            val toKeep = usagePatterns.takeLast(5000)
            usagePatterns.clear()
            usagePatterns.addAll(toKeep)
        }

        if (record.totalRuns % EVOLUTION_THRESHOLD == 0) {
            evaluateSkill(record)
        }
    }

    fun getSkillStats(skillId: String): JSONObject? {
        return skillRecords[skillId]?.toJson()
    }

    fun getTopSkills(limit: Int = 10): List<SkillRecord> {
        return skillRecords.values
            .filter { it.totalRuns > 0 }
            .sortedWith(
                compareByDescending<SkillRecord> { it.successRate }
                    .thenByDescending { it.totalRuns }
            )
            .take(limit)
    }

    fun getUnderperformingSkills(): List<SkillRecord> {
        return skillRecords.values
            .filter { it.totalRuns >= EVOLUTION_THRESHOLD && it.successRate < MIN_SUCCESS_RATE_TO_KEEP }
            .sortedBy { it.successRate }
    }

    fun analyzeAndEvolve(): List<EvolutionSuggestion> {
        synchronized(evolutionSuggestions) {
            evolutionSuggestions.clear()

            skillRecords.values.forEach { record ->
                if (record.totalRuns < EVOLUTION_THRESHOLD) return@forEach

                when {
                    record.successRate >= HIGH_SUCCESS_RATE -> suggestPromotion(record)
                    record.successRate < MIN_SUCCESS_RATE_TO_KEEP -> suggestDemotion(record)
                    record.avgTokensPerRun > 5000 -> suggestTokenOptimization(record)
                }
            }

            detectFusionOpportunities()
            return evolutionSuggestions
        }
    }

    private fun suggestPromotion(record: SkillRecord) {
        if (record.version < MAX_SKILL_VERSION) {
            record.version++
            evolutionSuggestions.add(EvolutionSuggestion(
                skillId = record.id,
                type = "promote",
                reason = "High success rate (${record.successRate})",
                action = "Increase priority",
                confidence = record.successRate
            ))
        }
    }

    private fun suggestDemotion(record: SkillRecord) {
        evolutionSuggestions.add(EvolutionSuggestion(
            skillId = record.id,
            type = "demote",
            reason = "Low success rate (${record.successRate})",
            action = "Reduce priority",
            confidence = 1.0 - record.successRate
        ))
    }

    private fun suggestTokenOptimization(record: SkillRecord) {
        evolutionSuggestions.add(EvolutionSuggestion(
            skillId = record.id,
            type = "optimize_tokens",
            reason = "High token usage",
            action = "Optimize prompt",
            confidence = 0.8
        ))
    }

    private fun evaluateSkill(record: SkillRecord) {
        if (record.successRate >= HIGH_SUCCESS_RATE) {
            suggestPromotion(record)
        } else if (record.successRate < MIN_SUCCESS_RATE_TO_KEEP) {
            suggestDemotion(record)
        }
        if (record.avgTokensPerRun > 5000) {
            suggestTokenOptimization(record)
        }
    }

    private fun detectFusionOpportunities() {
        val recentUsage = usagePatterns.takeLast(1000)
        val skillCooccurrence = mutableMapOf<String, MutableMap<String, Int>>()

        recentUsage.forEach { event ->
            skillCooccurrence.getOrPut(event.skillId) { mutableMapOf() }
        }
    }

    fun exportSkill(skillId: String): JSONObject? {
        val record = skillRecords[skillId] ?: return null
        return JSONObject().apply {
            put("skillId", skillId)
            put("version", record.version)
            put("stats", record.toJson())
            put("exportTimestamp", System.currentTimeMillis())
        }
    }

    fun importSkill(skillData: JSONObject): Boolean {
        val skillId = skillData.optString("skillId", "")
        if (skillId.isEmpty()) return false

        val record = SkillRecord(
            id = skillId,
            version = skillData.optInt("version", 1),
            createdAt = System.currentTimeMillis()
        )
        skillRecords[skillId] = record
        return true
    }

    fun getEvolutionReport(): JSONObject {
        return JSONObject().apply {
            put("totalSkills", skillRecords.size)
            put("totalUsageEvents", usagePatterns.size)
            val avgSuccessRate = if (skillRecords.isNotEmpty()) {
                skillRecords.values.filter { it.totalRuns > 0 }.map { it.successRate }.average()
            } else 0.0
            put("averageSuccessRate", avgSuccessRate)
        }
    }
}
