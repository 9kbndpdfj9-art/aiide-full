package com.aiide

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class MatchConfidence { LOW, MEDIUM, HIGH, EXACT }

data class SemanticSkillMatch(
    val skill: SkillDefinition,
    val score: Double,
    val confidence: MatchConfidence,
    val reasons: List<String>,
    val semanticOverlap: Double,
    val keywordMatches: Int,
    val semanticMatches: Int
)

class SemanticSkillMatcher {
    companion object {
        private const val TAG = "SemanticSkillMatcher"
        private const val EXACT_THRESHOLD = 0.9
        private const val HIGH_THRESHOLD = 0.7
        private const val MEDIUM_THRESHOLD = 0.4
        private const val LOW_THRESHOLD = 0.2

        private val SEMANTIC_SYNONYMS = mapOf(
            "web" to listOf("website", "frontend", "ui", "page", "html", "css", "react", "vue"),
            "backend" to listOf("api", "server", "database", "rest", "endpoint", "auth"),
            "test" to listOf("testing", "unit test", "spec", "jest", "pytest", "junit"),
            "debug" to listOf("bug", "fix", "error", "issue", "crash"),
            "refactor" to listOf("optimize", "improve", "clean", "restructure"),
            "docs" to listOf("documentation", "readme", "comment", "explain"),
            "fullstack" to listOf("end to end", "complete", "full app"),
            "cross-file" to listOf("multiple files", "cross module", "interdependent"),
            "general" to listOf("code", "function", "class", "implement", "create")
        )

        private val INTENT_PATTERNS = listOf(
            Regex("""(?:创建|生成|写|实现|构建)\s*(.+)?""") to "create",
            Regex("""(?:修改|改|调整|优化)\s*(.+)?""") to "modify",
            Regex("""(?:修复|解决|处理)\s*(.+(?:bug|错误|问题|报错))""") to "fix",
            Regex("""(?:测试|写测试)\s*(.+)?""") to "test",
            Regex("""(?:重构|优化|清理)\s*(.+)?""") to "refactor",
            Regex("""(?:文档|注释|说明)\s*(.+)?""") to "document"
        )

        private val SPLIT_REGEX = Regex("""[\s,，。、;；:：!?！？\[\]\(\)\{\}]+""")
    }

    private val skillCache = ConcurrentHashMap<String, SkillDefinition>()
    private val matchHistory = CopyOnWriteArrayList<MatchHistoryEntry>()
    private val skillsLoadedFlag = AtomicBoolean(false)

    data class MatchHistoryEntry(val query: String, val matchedSkill: String, val score: Double, val timestamp: Long = System.currentTimeMillis())

    fun loadSkills(skills: List<SkillDefinition>) {
        skillCache.clear()
        skills.forEach { skill -> if (skill.id.isNotBlank()) skillCache[skill.id] = skill }
        skillsLoadedFlag.set(skills.isNotEmpty())
    }

    fun fuzzyMatch(query: String, threshold: Double = MEDIUM_THRESHOLD): List<SemanticSkillMatch> {
        if (!skillsLoadedFlag.get() || query.isBlank()) return emptyList()

        val queryLower = query.lowercase().trim()
        val intentType = detectIntent(queryLower)
        val queryKeywords = extractKeywords(queryLower)
        val queryConcepts = extractConcepts(queryLower)

        val scored = skillCache.values.mapNotNull { skill ->
            if (!skill.enabled) return@mapNotNull null

            val keywordScore = calculateKeywordScore(queryKeywords, skill)
            val semanticScore = calculateSemanticScore(queryConcepts, intentType, skill)
            val descriptionScore = calculateDescriptionScore(queryLower, skill)
            val tagScore = calculateTagScore(queryLower, skill)

            val totalScore = (keywordScore * 0.3 + semanticScore * 0.4 + descriptionScore * 0.2 + tagScore * 0.1).coerceIn(0.0, 1.0)
            if (totalScore < threshold) return@mapNotNull null

            val confidence = when {
                totalScore >= EXACT_THRESHOLD -> MatchConfidence.EXACT
                totalScore >= HIGH_THRESHOLD -> MatchConfidence.HIGH
                totalScore >= MEDIUM_THRESHOLD -> MatchConfidence.MEDIUM
                else -> MatchConfidence.LOW
            }

            val reasons = mutableListOf<String>()
            if (keywordScore > 0.5) reasons.add("关键词匹配度 ${(keywordScore * 100).toInt()}%")
            if (semanticScore > 0.5) reasons.add("语义关联度 ${(semanticScore * 100).toInt()}%")
            if (descriptionScore > 0.5) reasons.add("描述匹配度 ${(descriptionScore * 100).toInt()}%")
            if (tagScore > 0.5) reasons.add("标签匹配度 ${(tagScore * 100).toInt()}%")

            SemanticSkillMatch(skill = skill, score = totalScore, confidence = confidence, reasons = reasons,
                semanticOverlap = semanticScore, keywordMatches = countKeywordMatches(queryKeywords, skill),
                semanticMatches = countSemanticMatches(queryConcepts, intentType, skill))
        }

        val results = scored.sortedByDescending { it.score }
        results.firstOrNull()?.let { match ->
            matchHistory.add(MatchHistoryEntry(query = query, matchedSkill = match.skill.id, score = match.score))
        }
        Log.i(TAG, "Fuzzy match: '$query' -> ${results.size} skills")
        return results
    }

    fun autoSelectSkill(query: String): SkillDefinition? {
        return fuzzyMatch(query, HIGH_THRESHOLD).firstOrNull()?.skill
    }

    fun getSuggestions(query: String, maxCount: Int = 3): List<SemanticSkillMatch> {
        return fuzzyMatch(query, LOW_THRESHOLD).take(maxCount)
    }

    private fun detectIntent(query: String): String {
        for ((pattern, intentType) in INTENT_PATTERNS) {
            if (pattern.containsMatchIn(query)) return intentType
        }
        if (query.contains("页面") || query.contains("ui") || query.contains("前端")) return "frontend"
        if (query.contains("api") || query.contains("后端") || query.contains("数据库")) return "backend"
        if (query.contains("登录") || query.contains("注册") || query.contains("认证")) return "auth"
        if (query.contains("bug") || query.contains("错误") || query.contains("报错")) return "fix"
        if (query.contains("测试") || query.contains("test")) return "test"
        if (query.contains("重构") || query.contains("优化") || query.contains("性能")) return "refactor"
        return "unknown"
    }

    private fun extractKeywords(query: String): Set<String> {
        val words = query.split(SPLIT_REGEX).filter { it.length >= 2 }.toSet()
        val expanded = mutableSetOf<String>()
        expanded.addAll(words)
        for (word in words) {
            SEMANTIC_SYNONYMS[word]?.let { expanded.addAll(it) }
        }
        return expanded
    }

    private fun extractConcepts(query: String): Set<String> {
        val concepts = mutableSetOf<String>()
        listOf("function" to Regex("""(fun|function|def)"""),
            "class" to Regex("""(class|interface|object)"""),
            "api" to Regex("""(api|endpoint|route)"""),
            "database" to Regex("""(database|db|table)"""),
            "test" to Regex("""(test|spec|unit)""")).forEach { (concept, pattern) ->
            if (pattern.containsMatchIn(query)) concepts.add(concept)
        }
        return concepts
    }

    private fun calculateKeywordScore(keywords: Set<String>, skill: SkillDefinition): Double {
        if (keywords.isEmpty()) return 0.0
        val skillKeywords = (skill.triggers + skill.tags).map { it.lowercase() }.toSet()
        val matched = keywords.intersect(skillKeywords)
        return if (skillKeywords.isEmpty()) 0.0 else matched.size.toDouble() / skillKeywords.size.coerceAtLeast(1)
    }

    private fun calculateSemanticScore(concepts: Set<String>, intentType: String, skill: SkillDefinition): Double {
        if (concepts.isEmpty() && intentType == "unknown") return 0.0
        val skillConcepts = skill.tags.map { it.lowercase() }.toSet()
        val conceptOverlap = concepts.intersect(skillConcepts).size
        val conceptScore = if (concepts.isEmpty()) 0.0 else conceptOverlap.toDouble() / concepts.size.coerceAtLeast(1)
        val intentMatch = if (intentType != "unknown" && skill.tags.any { it.lowercase() == intentType }) 0.3 else 0.0
        return (conceptScore * 0.6 + intentMatch).coerceIn(0.0, 1.0)
    }

    private fun calculateDescriptionScore(query: String, skill: SkillDefinition): Double {
        val descLower = skill.description.lowercase()
        if (descLower.isEmpty()) return 0.0
        val queryWords = query.split(Regex("""\s+""")).filter { it.length >= 3 }
        val matchedWords = queryWords.count { descLower.contains(it) }
        return if (queryWords.isEmpty()) 0.0 else matchedWords.toDouble() / queryWords.size.coerceAtLeast(1)
    }

    private fun calculateTagScore(query: String, skill: SkillDefinition): Double {
        if (skill.tags.isEmpty()) return 0.0
        val matchedTags = skill.tags.count { query.contains(it.lowercase()) }
        return matchedTags.toDouble() / skill.tags.size.coerceAtLeast(1)
    }

    private fun countKeywordMatches(keywords: Set<String>, skill: SkillDefinition): Int {
        return keywords.intersect((skill.triggers + skill.tags).map { it.lowercase() }.toSet()).size
    }

    private fun countSemanticMatches(concepts: Set<String>, intentType: String, skill: SkillDefinition): Int {
        var count = concepts.intersect(skill.tags.map { it.lowercase() }.toSet()).size
        if (intentType != "unknown" && skill.tags.contains(intentType)) count++
        return count
    }

    fun getMatchHistory(limit: Int = 50): List<MatchHistoryEntry> = matchHistory.takeLast(limit)
    fun getStats(): String = """{"total_matches": ${matchHistory.size}, "skills_loaded": ${skillCache.size}}"""
}