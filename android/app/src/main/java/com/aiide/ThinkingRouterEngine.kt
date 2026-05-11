package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

enum class ThinkingPath {
    FAST, DEEP, EXPLORE
}

data class ThinkingRouteDecision(
    val path: ThinkingPath,
    val confidence: Double,
    val reasons: List<String>,
    val estimatedSteps: Int,
    val estimatedTokens: Int,
    val riskFactors: List<String>,
    val organizationalConstraints: List<String>,
    val suggestedTools: List<String>,
    val decidedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path.name)
        put("confidence", confidence)
        put("reasons", JSONArray(reasons))
        put("estimated_steps", estimatedSteps)
        put("estimated_tokens", estimatedTokens)
        put("risk_factors", JSONArray(riskFactors))
        put("organizational_constraints", JSONArray(organizationalConstraints))
        put("suggested_tools", JSONArray(suggestedTools))
        put("decided_at", decidedAt)
    }
}

data class ThinkingRouteConfig(
    var fastThreshold: Double = 0.7,
    var deepRiskKeywords: Set<String> = setOf("支付", "删除", "权限", "生产", "数据库迁移", "认证", "资金", "退款"),
    var exploreAmbiguityKeywords: Set<String> = setOf("类似", "大概", "等", "某种", "看看", "试试", "探索"),
    var maxFastTokens: Int = 2000,
    var maxDeepTokens: Int = 8000,
    var maxExploreTokens: Int = 5000
)

class ThinkingRouterEngine(
    private val context: Context,
    private val knowledgeBase: OrganizationKnowledgeBase,
    private val toolGenome: ToolGenomeEngine
) {
    companion object {
        private const val TAG = "ThinkingRouterEngine"
        private const val MAX_HISTORY = 500
        private val REGEX_CJK = Regex("[\\u4e00-\\u9fff]")
        private val REGEX_CODE = Regex("(函数|方法|类|接口|变量|模块|API|配置|文件|目录)")
        private const val DEEP_MIN_ACCURATE_STEPS = 3
        private const val FAST_MAX_ACCURATE_STEPS = 2
        private const val RESOURCE_ESTIMATE_MULTIPLIER = 1.5
    }

    private val configLock = ReentrantReadWriteLock()
    private var config = ThinkingRouteConfig()
    private val routeHistory = ConcurrentHashMap<String, ThinkingRouteRecord>()
    private val historyOrder = ConcurrentLinkedQueue<String>()
    private val routeCounter = AtomicLong(0)

    private val pathStats = ConcurrentHashMap<ThinkingPath, AtomicLong>().apply {
        put(ThinkingPath.FAST, AtomicLong(0))
        put(ThinkingPath.DEEP, AtomicLong(0))
        put(ThinkingPath.EXPLORE, AtomicLong(0))
    }

    private val accuracyStats = ConcurrentHashMap<String, Boolean>()

    @Volatile private var isShutdown = false

    fun route(userIntent: String): ThinkingRouteDecision {
        if (isShutdown) throw IllegalStateException("ThinkingRouterEngine is shutdown")
        if (userIntent.isBlank()) {
            return ThinkingRouteDecision(
                path = ThinkingPath.EXPLORE,
                confidence = 0.3,
                reasons = listOf("空意图，需要探索"),
                estimatedSteps = 1,
                estimatedTokens = 500,
                riskFactors = emptyList(),
                organizationalConstraints = emptyList(),
                suggestedTools = emptyList()
            )
        }

        val reasons = mutableListOf<String>()
        val riskFactors = mutableListOf<String>()
        val orgConstraints = mutableListOf<String>()
        val suggestedTools = mutableListOf<String>()

        val complexityScore = calculateComplexity(userIntent, reasons)
        val riskScore = calculateRisk(userIntent, riskFactors)
        val ambiguityScore = calculateAmbiguity(userIntent, reasons)

        val currentConfig = config

        val adjustedComplexity = complexityScore.coerceIn(0.0, 1.0)
        val adjustedRisk = riskScore.coerceIn(0.0, 1.0)

        val path = when {
            adjustedRisk >= 0.6 -> {
                reasons.add("高风险任务，强制Deep路径")
                ThinkingPath.DEEP
            }
            ambiguityScore >= 0.5 -> {
                reasons.add("意图模糊，需要探索")
                ThinkingPath.EXPLORE
            }
            adjustedComplexity <= currentConfig.fastThreshold && adjustedRisk < 0.3 -> {
                reasons.add("简单明确任务，Fast路径")
                ThinkingPath.FAST
            }
            adjustedComplexity > 0.7 -> {
                reasons.add("高复杂度任务，Deep路径")
                ThinkingPath.DEEP
            }
            else -> {
                reasons.add("中等复杂度，Deep路径（默认安全）")
                ThinkingPath.DEEP
            }
        }

        val confidence = calculateConfidence(complexityScore, riskScore, ambiguityScore, path)
        val (estimatedSteps, estimatedTokens) = estimateResources(path, adjustedComplexity, currentConfig)

        val decision = ThinkingRouteDecision(
            path = path,
            confidence = confidence,
            reasons = reasons,
            estimatedSteps = estimatedSteps,
            estimatedTokens = estimatedTokens,
            riskFactors = riskFactors,
            organizationalConstraints = orgConstraints,
            suggestedTools = suggestedTools
        )

        pathStats[path]?.incrementAndGet()
        return decision
    }

    private fun calculateComplexity(intent: String, reasons: MutableList<String>): Double {
        var score = 0.0
        val cjkCount = REGEX_CJK.findAll(intent).count()
        if (cjkCount > 30) {
            score += 0.2
            reasons.add("长意图描述")
        }
        val codeMatches = REGEX_CODE.findAll(intent).count()
        if (codeMatches >= 3) {
            score += 0.2
            reasons.add("涉及多个代码概念")
        }
        if (intent.contains("同时") || intent.contains("并且") || intent.contains("以及")) {
            score += 0.15
            reasons.add("包含并行需求")
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateRisk(intent: String, riskFactors: MutableList<String>): Double {
        var score = 0.0
        val lower = intent.lowercase()
        val currentConfig = config

        for (keyword in currentConfig.deepRiskKeywords) {
            if (lower.contains(keyword.lowercase())) {
                score += 0.3
                riskFactors.add("包含高风险关键词：$keyword")
            }
        }

        if (lower.contains("全部") || lower.contains("所有") || lower.contains("批量")) {
            score += 0.2
            riskFactors.add("涉及批量操作")
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateAmbiguity(intent: String, reasons: MutableList<String>): Double {
        var score = 0.0
        val lower = intent.lowercase()
        val currentConfig = config

        for (keyword in currentConfig.exploreAmbiguityKeywords) {
            if (lower.contains(keyword.lowercase())) {
                score += 0.2
                reasons.add("包含模糊关键词：$keyword")
            }
        }

        if (intent.length < 10) {
            score += 0.3
            reasons.add("意图描述过短")
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateConfidence(complexity: Double, risk: Double, ambiguity: Double, path: ThinkingPath): Double {
        val pathFitScore = when (path) {
            ThinkingPath.FAST -> if (complexity < 0.3 && risk < 0.2) 0.9 else 0.5
            ThinkingPath.DEEP -> if (complexity > 0.5 || risk > 0.3) 0.9 else 0.6
            ThinkingPath.EXPLORE -> if (ambiguity > 0.4) 0.85 else 0.5
        }
        val clarityBonus = (1.0 - ambiguity) * 0.2
        return (pathFitScore + clarityBonus).coerceIn(0.0, 1.0)
    }

    private fun estimateResources(path: ThinkingPath, complexity: Double, cfg: ThinkingRouteConfig): Pair<Int, Int> {
        return when (path) {
            ThinkingPath.FAST -> Pair(1, (cfg.maxFastTokens * (0.5 + complexity * 0.5)).toInt())
            ThinkingPath.DEEP -> Pair((3 + (complexity * 5).toInt()), (cfg.maxDeepTokens * (0.6 + complexity * 0.4)).toInt())
            ThinkingPath.EXPLORE -> Pair((2 + (complexity * 3).toInt()), (cfg.maxExploreTokens * (0.5 + complexity * 0.5)).toInt())
        }
    }

    fun getRoutingStats(): JSONObject {
        val totalRoutes = pathStats.values.sumOf { it.get() }
        return JSONObject().apply {
            put("total_routes", totalRoutes)
            put("fast_count", pathStats[ThinkingPath.FAST]?.get() ?: 0)
            put("deep_count", pathStats[ThinkingPath.DEEP]?.get() ?: 0)
            put("explore_count", pathStats[ThinkingPath.EXPLORE]?.get() ?: 0)
        }
    }

    fun shutdown() {
        isShutdown = true
    }
}

data class ThinkingRouteRecord(
    val id: String,
    val userIntent: String,
    val decision: ThinkingRouteDecision,
    val actualPath: ThinkingPath,
    val actualSteps: Int,
    val actualTokens: Int,
    val wasAccurate: Boolean,
    val completedAt: Long = System.currentTimeMillis()
)
