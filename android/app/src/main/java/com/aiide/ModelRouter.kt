package com.aiide

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ModelRouter {

    companion object {
        private const val LOCAL_CONFIDENCE_THRESHOLD = 0.7
        private const val EXPENSIVE_MODEL_THRESHOLD = 0.8
        private const val CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 60_000L
        private const val TOKEN_BUCKET_CAPACITY = 60
        private const val TOKEN_BUCKET_REFILL_MS = 1_000L
        private const val MAX_HISTORY_SIZE = 100
        private const val HISTORY_SHRINK_TARGET = 50
        private val REGEX_EXTERNAL_KNOWLEDGE = Regex("""\b(latest|recent|new|2025|2026|current|today|now)\b""")
        private val REGEX_CODE_UNDERSTANDING = Regex("""\b(understand|explain|analyze|review|refactor|debug|fix|bug|error|issue)\b""")
        private val REGEX_REASONING = Regex("""\b(why|how|architect|design|plan|strategy|optimize|complex|algorithm)\b""")
        private val REGEX_CREATION = Regex("""\b(create|generate|write|build|make|implement|add)\b""")
        private val REGEX_DEBUGGING = Regex("""\b(fix|debug|resolve|solve|repair|correct)\b""")
        private val REGEX_REFACTORING = Regex("""\b(refactor|optimize|improve|clean|simplify|restructure)\b""")
        private val REGEX_TESTING = Regex("""\b(test|verify|validate|check)\b""")
        private val REGEX_EXPLANATION = Regex("""\b(explain|describe|what|how|why)\b""")
        private val REGEX_SEARCH = Regex("""\b(search|find|look|locate)\b""")
    }

    private data class CircuitBreakerState(
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
        val openSince: AtomicLong = AtomicLong(0),
        val isOpen: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        val halfOpen: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    )

    private data class TokenBucket(
        val tokens: AtomicInteger = AtomicInteger(TOKEN_BUCKET_CAPACITY),
        val lastRefillTime: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val rateLimiter = TokenBucket()

    data class ModelConfig(
        val id: String,
        val name: String,
        val provider: String,
        val endpoint: String,
        val costPerMTok: Double,
        val maxTokens: Int,
        val capabilities: Set<String>,
        val enabled: Boolean = true
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("name", name)
                put("provider", provider)
                put("endpoint", endpoint)
                put("costPerMTok", costPerMTok)
                put("maxTokens", maxTokens)
                val capsArr = JSONArray()
                capabilities.forEach { capsArr.put(it) }
                put("capabilities", capsArr)
                put("enabled", enabled)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): ModelConfig {
                val capsSet = mutableSetOf<String>()
                val capsArr = json.optJSONArray("capabilities")
                if (capsArr != null) {
                    for (i in 0 until capsArr.length()) {
                        capsSet.add(capsArr.getString(i))
                    }
                }
                return ModelConfig(
                    id = json.getString("id"),
                    name = json.optString("name", json.getString("id")),
                    provider = json.getString("provider"),
                    endpoint = json.getString("endpoint"),
                    costPerMTok = json.optDouble("costPerMTok", 0.0),
                    maxTokens = json.optInt("maxTokens", 4096),
                    capabilities = capsSet,
                    enabled = json.optBoolean("enabled", true)
                )
            }
        }
    }

    data class RoutingDecision(
        val useLocal: Boolean,
        val selectedModel: ModelConfig?,
        val reason: String,
        val estimatedCost: Double,
        val alternatives: List<ModelConfig>
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("useLocal", useLocal)
                put("selectedModel", selectedModel?.toJson() ?: JSONObject.NULL)
                put("reason", reason)
                put("estimatedCost", estimatedCost)
                val altArr = JSONArray()
                alternatives.forEach { altArr.put(it.toJson()) }
                put("alternatives", altArr)
            }
        }
    }

    data class TaskAnalysis(
        val complexity: Double,
        val needsExternalKnowledge: Boolean,
        val needsCodeUnderstanding: Boolean,
        val needsReasoning: Boolean,
        val estimatedTokens: Int,
        val category: String
    )

    private val registeredModels = CopyOnWriteArrayList<ModelConfig>()
    private val modelHistory = ConcurrentHashMap<String, MutableList<Double>>()
    private val modelHistoryLock = Any()

    fun analyzeTask(query: String, context: Map<String, Any>): TaskAnalysis {
        val lowerQuery = query.lowercase()

        val needsExternalKnowledge = lowerQuery.contains(REGEX_EXTERNAL_KNOWLEDGE)
        val needsCodeUnderstanding = lowerQuery.contains(REGEX_CODE_UNDERSTANDING)
        val needsReasoning = lowerQuery.contains(REGEX_REASONING)

        val complexityScore = computeComplexity(query, context)

        val category = when {
            lowerQuery.contains(REGEX_CREATION) -> "creation"
            lowerQuery.contains(REGEX_DEBUGGING) -> "debugging"
            lowerQuery.contains(REGEX_REFACTORING) -> "refactoring"
            lowerQuery.contains(REGEX_TESTING) -> "testing"
            lowerQuery.contains(REGEX_EXPLANATION) -> "explanation"
            lowerQuery.contains(REGEX_SEARCH) -> "search"
            else -> "general"
        }

        val estimatedTokens = estimateTokens(query, context)

        return TaskAnalysis(
            complexity = complexityScore,
            needsExternalKnowledge = needsExternalKnowledge,
            needsCodeUnderstanding = needsCodeUnderstanding,
            needsReasoning = needsReasoning,
            estimatedTokens = estimatedTokens,
            category = category
        )
    }

    fun routeTask(query: String, context: Map<String, Any>): RoutingDecision {
        if (!tryAcquireToken()) {
            return RoutingDecision(
                useLocal = true,
                selectedModel = null,
                reason = "Rate limit exceeded, please retry later",
                estimatedCost = 0.0,
                alternatives = emptyList()
            )
        }

        val analysis = analyzeTask(query, context)

        if (analysis.complexity < LOCAL_CONFIDENCE_THRESHOLD && !analysis.needsExternalKnowledge) {
            return RoutingDecision(
                useLocal = true,
                selectedModel = null,
                reason = "Task complexity (${analysis.complexity}) below threshold, using zero-token engine",
                estimatedCost = 0.0,
                alternatives = emptyList()
            )
        }

        val enabledModels = registeredModels.filter { it.enabled && !isCircuitOpen(it.id) }

        val suitableModels = enabledModels.filter { model ->
            when {
                analysis.needsReasoning && analysis.complexity > EXPENSIVE_MODEL_THRESHOLD ->
                    model.capabilities.contains("reasoning") || model.capabilities.contains("complex")
                analysis.needsCodeUnderstanding ->
                    model.capabilities.contains("coding")
                analysis.needsExternalKnowledge ->
                    model.capabilities.contains("search")
                else ->
                    true
            }
        }

        val cheapestModel = suitableModels.minByOrNull { it.costPerMTok }
            ?: enabledModels.minByOrNull { it.costPerMTok }
            ?: return RoutingDecision(
                useLocal = true,
                selectedModel = null,
                reason = "No models registered. Please register models or load from config.",
                estimatedCost = 0.0,
                alternatives = emptyList()
            )

        val alternatives = suitableModels
            .filter { it.id != cheapestModel.id }
            .sortedBy { it.costPerMTok }
            .take(3)

        val estimatedCost = cheapestModel.costPerMTok * analysis.estimatedTokens / 1_000_000.0

        return RoutingDecision(
            useLocal = false,
            selectedModel = cheapestModel,
            reason = "Task requires ${getModelReason(analysis)}, selected cheapest suitable model",
            estimatedCost = estimatedCost,
            alternatives = alternatives
        )
    }

    fun recordModelResult(modelId: String, success: Boolean, quality: Double) {
        val score = if (success) quality else 0.0
        synchronized(modelHistoryLock) {
            val scores = modelHistory.getOrPut(modelId) { mutableListOf() }
            scores.add(score)
            if (scores.size > MAX_HISTORY_SIZE) {
                scores.subList(0, scores.size - HISTORY_SHRINK_TARGET).clear()
            }
        }

        val breaker = circuitBreakers.computeIfAbsent(modelId) { CircuitBreakerState() }
        if (success) {
            breaker.consecutiveFailures.set(0)
            breaker.isOpen.set(false)
            recordHalfOpenResult(modelId, true)
        } else {
            val failures = breaker.consecutiveFailures.incrementAndGet()
            if (failures >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
                breaker.isOpen.set(true)
                breaker.halfOpen.set(false)
                breaker.openSince.set(System.currentTimeMillis())
            }
            recordHalfOpenResult(modelId, false)
        }
    }

    private fun isCircuitOpen(modelId: String): Boolean {
        val breaker = circuitBreakers[modelId] ?: return false
        if (!breaker.isOpen.get()) return false
        val elapsed = System.currentTimeMillis() - breaker.openSince.get()
        if (elapsed >= CIRCUIT_BREAKER_COOLDOWN_MS) {
            if (breaker.halfOpen.compareAndSet(false, true)) {
                breaker.consecutiveFailures.set(0)
            }
            return false
        }
        return true
    }

    private fun recordHalfOpenResult(modelId: String, success: Boolean) {
        val breaker = circuitBreakers[modelId] ?: return
        if (breaker.halfOpen.get()) {
            if (success) {
                breaker.isOpen.set(false)
                breaker.halfOpen.set(false)
                breaker.consecutiveFailures.set(0)
            } else {
                breaker.isOpen.set(true)
                breaker.halfOpen.set(false)
                breaker.openSince.set(System.currentTimeMillis())
            }
        }
    }

    private fun tryAcquireToken(): Boolean {
        val now = System.currentTimeMillis()
        val lastRefill = rateLimiter.lastRefillTime.get()
        val elapsed = now - lastRefill
        if (elapsed >= TOKEN_BUCKET_REFILL_MS) {
            val tokensToAdd = (elapsed / TOKEN_BUCKET_REFILL_MS).toInt().coerceAtMost(TOKEN_BUCKET_CAPACITY)
            if (rateLimiter.lastRefillTime.compareAndSet(lastRefill, now)) {
                var refilled = false
                while (!refilled) {
                    val current = rateLimiter.tokens.get()
                    val newTokens = (current + tokensToAdd).coerceAtMost(TOKEN_BUCKET_CAPACITY)
                    refilled = rateLimiter.tokens.compareAndSet(current, newTokens)
                }
            }
        }
        while (true) {
            val current = rateLimiter.tokens.get()
            if (current <= 0) return false
            if (rateLimiter.tokens.compareAndSet(current, current - 1)) return true
        }
    }

    fun getModelPerformance(modelId: String): Double {
        synchronized(modelHistoryLock) {
            val scores = modelHistory[modelId] ?: return 0.5
            return if (scores.isEmpty()) 0.5 else scores.average()
        }
    }

    fun getRecommendedModel(category: String): ModelConfig? {
        val suitableModels = registeredModels.filter { model ->
            model.enabled && model.capabilities.contains(category)
        }
        if (suitableModels.isEmpty()) return null

        val maxCost = suitableModels.maxOfOrNull { it.costPerMTok }?.coerceAtLeast(1.0) ?: 1.0

        return suitableModels
            .map { model ->
                val performance = getModelPerformance(model.id)
                val costScore = 1.0 - (model.costPerMTok / maxCost).coerceIn(0.0, 1.0)
                val combinedScore = performance * 0.6 + costScore * 0.4
                Pair(model, combinedScore)
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun validateModel(config: ModelConfig): Boolean {
        if (config.id.isBlank()) return false
        if (config.provider.isBlank()) return false
        if (config.endpoint.isBlank()) return false
        if (config.capabilities.isEmpty()) return false
        return true
    }

    fun registerModel(config: ModelConfig): Boolean {
        if (!validateModel(config)) return false
        val existingIndex = registeredModels.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            registeredModels[existingIndex] = config
        } else {
            registeredModels.add(config)
        }
        return true
    }

    fun unregisterModel(modelId: String): Boolean {
        return registeredModels.removeIf { it.id == modelId }
    }

    fun updateModelConfig(modelId: String, updates: ModelConfig): Boolean {
        if (!validateModel(updates)) return false
        val existingIndex = registeredModels.indexOfFirst { it.id == modelId }
        if (existingIndex < 0) return false
        registeredModels[existingIndex] = updates
        return true
    }

    fun getRegisteredModels(): List<ModelConfig> {
        return registeredModels.toList()
    }

    fun importModelsFromJson(jsonStr: String): Int {
        val jsonArray = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            return 0
        }
        var imported = 0
        for (i in 0 until jsonArray.length()) {
            try {
                val config = ModelConfig.fromJson(jsonArray.getJSONObject(i))
                if (registerModel(config)) {
                    imported++
                }
            } catch (e: Exception) {
            }
        }
        return imported
    }

    fun loadModelsFromConfig(filesDir: String): Int {
        val configFile = File(filesDir, "models_config.json")
        if (!configFile.exists()) return 0
        return try {
            val jsonStr = configFile.readText(StandardCharsets.UTF_8)
            importModelsFromJson(jsonStr)
        } catch (e: Exception) {
            0
        }
    }

    fun saveModelsToConfig(filesDir: String): Boolean {
        val dir = File(filesDir)
        if (!dir.exists()) dir.mkdirs()
        val configFile = File(dir, "models_config.json")
        val jsonArray = JSONArray()
        registeredModels.forEach { jsonArray.put(it.toJson()) }
        configFile.writeText(jsonArray.toString(2), StandardCharsets.UTF_8)
        return true
    }

    fun getAvailableProviders(): List<String> {
        return registeredModels.map { it.provider }.distinct()
    }

    private fun computeComplexity(query: String, context: Map<String, Any>): Double {
        var score = 0.3

        val lines = query.lines().size
        score += (lines / 50.0).coerceAtMost(0.3)

        val fileCount = (context["fileCount"] as? Int) ?: 0
        score += (fileCount / 20.0).coerceAtMost(0.2)

        val hasCodeContext = context.containsKey("codeContent") || context.containsKey("filePath")
        if (hasCodeContext) score += 0.1

        val complexKeywords = listOf("architecture", "design", "refactor", "optimize", "algorithm", "complex", "multi-file", "cross-platform")
        var keywordBonus = 0.0
        complexKeywords.forEach { keyword ->
            if (query.contains(keyword, ignoreCase = true)) {
                keywordBonus += 0.1
            }
        }
        score += keywordBonus.coerceAtMost(0.4)

        return score.coerceIn(0.0, 1.0)
    }

    private fun estimateTokens(query: String, context: Map<String, Any>): Int {
        var baseTokens = query.length / 4

        val fileCount = (context["fileCount"] as? Int) ?: 0
        baseTokens += fileCount * 200

        val hasCodeContent = context.containsKey("codeContent")
        if (hasCodeContent) {
            val codeContent = context["codeContent"] as? String
            baseTokens += (codeContent?.length ?: 0) / 4
        }

        return (baseTokens * 1.5).toInt()
    }

    private fun getModelReason(analysis: TaskAnalysis): String {
        return when {
            analysis.needsExternalKnowledge -> "external knowledge"
            analysis.needsReasoning && analysis.complexity > 0.8 -> "complex reasoning"
            analysis.needsCodeUnderstanding -> "code understanding"
            analysis.category == "debugging" -> "debugging analysis"
            else -> "general assistance"
        }
    }
}
