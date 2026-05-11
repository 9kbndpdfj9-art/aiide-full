package com.aiide

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.*

// ─── Data Classes ────────────────────────────────────────────────────────────

data class StepResult(
    val stepName: String = "",
    val output: String = "",
    val success: Boolean = true,
    val errorMessage: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("step_name", stepName)
        put("output", output)
        put("success", success)
        errorMessage?.let { put("error_message", it) }
        put("metadata", JSONObject(metadata))
    }
    companion object {
        fun fromJson(json: JSONObject): StepResult {
            return StepResult(
                stepName = json.optString("step_name", ""),
                output = json.optString("output", ""),
                success = json.optBoolean("success", true),
                errorMessage = json.optString("error_message").ifEmpty { null },
                metadata = json.optJSONObject("metadata")?.let {
                    val map = mutableMapOf<String, String>()
                    it.keys().forEach { key -> map[key] = it.optString(key, "") }
                    map
                } ?: emptyMap()
            )
        }
    }
}

data class SkillExecutionResult(
    val skillId: String = "",
    val success: Boolean = true,
    val output: String = "",
    val errorMessage: String? = null,
    val stepResults: List<StepResult> = emptyList(),
    val executionTimeMs: Long = 0,
    val tokensUsed: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("skill_id", skillId)
        put("success", success)
        put("output", output)
        errorMessage?.let { put("error_message", it) }
        put("step_results", org.json.JSONArray(stepResults.map { it.toJson() }))
        put("execution_time_ms", executionTimeMs)
        put("tokens_used", tokensUsed)
        put("metadata", JSONObject(metadata))
    }
}

class SkillManager(private val context: Context) {

    @Volatile
    private var skillsCache: MutableMap<String, SkillDefinition>? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("skill_registry", Context.MODE_PRIVATE)

    private val skillsDir: File
        get() = File(context.filesDir, "skills")

    private val loadSkillsLock = Any()

    init {
        loadSkills()
        ensureBuiltinSkills()
    }

    private fun loadSkills() {
        synchronized(loadSkillsLock) {
            if (skillsCache != null) return
            val cache = mutableMapOf<String, SkillDefinition>()
            val registeredIds = prefs.getStringSet("registered_skills", emptySet()) ?: emptySet()
            for (id in registeredIds) {
                val skillFile = File(skillsDir, "$id/skill.json")
                if (skillFile.exists()) {
                    try {
                        val json = JSONObject(skillFile.readText())
                        val skill = SkillDefinition.fromJson(json)
                        cache[id] = skill
                    } catch (e: Exception) {
                        Log.e("SkillManager", "Failed to load skill file: $id", e)
                    }
                }
            }
            skillsCache = cache
        }
    }

    fun getSkillDefinitions(): List<SkillDefinition> {
        val cache = skillsCache ?: synchronized(loadSkillsLock) {
            skillsCache ?: run {
                loadSkills()
                skillsCache ?: emptyMap()
            }
        }
        return cache.values.toList()
    }

    fun matchSkill(query: String): List<SkillDefinition> {
        val cache = skillsCache ?: synchronized(loadSkillsLock) {
            skillsCache ?: run {
                loadSkills()
                skillsCache ?: emptyMap()
            }
        }
        val queryLower = query.lowercase()
        return cache.values.filter { skill ->
            skill.enabled && (
                skill.name.lowercase().contains(queryLower) ||
                skill.id.lowercase().contains(queryLower) ||
                skill.description.lowercase().contains(queryLower) ||
                skill.tags.any { it.lowercase().contains(queryLower) } ||
                skill.triggers.any { it.lowercase().contains(queryLower) }
            )
        }.sortedByDescending { it.priority }
    }

    fun getBestSkill(query: String): SkillDefinition? {
        return matchSkill(query).firstOrNull()
    }

    fun getSkillById(skillId: String): SkillDefinition? {
        return skillsCache?.get(skillId)
    }

    private fun ensureBuiltinSkills() {
        synchronized(loadSkillsLock) {
            val currentCache = skillsCache ?: return
            if (!currentCache.containsKey("general")) {
                currentCache["general"] = createGeneralSkill()
            }
        }
    }

    private fun createGeneralSkill(): SkillDefinition {
        return SkillDefinition(
            id = "general",
            name = "General Programming",
            version = "1.0.0",
            description = "优秀的通用编程助手",
            author = "aiide-official",
            tags = listOf("coding", "general", "assistant"),
            triggers = listOf("write code", "create", "implement", "help"),
            priority = 10,
            enabled = true,
            maxTokens = 8000,
            temperature = 0.7,
            systemPrompt = "你是一名优秀的通用编程助手。"
        )
    }
}

data class SkillPipelineStep(
    val name: String,
    val type: String,
    val prompt: String = "",
    val toolCall: String? = null,
    val inputTransform: String? = null,
    val outputTransform: String? = null,
    val condition: String? = null,
    val retryCount: Int = 0,
    val timeoutMs: Long = 30000,
    val dependsOn: List<String> = emptyList()
)

data class SkillToolConfig(
    val name: String,
    val type: String,
    val description: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val priority: Int = 0
)

data class SkillAPIRoute(
    val type: String,
    val baseUrl: String,
    val endpoint: String = "",
    val method: String = "POST",
    val authType: String = "api_key",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String = "",
    val responseMapping: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30000,
    val retryOnFailure: Boolean = false,
    val cookieDomain: String? = null,
    val cookieName: String? = null,
    val cookieRefreshUrl: String? = null
)

data class CrossFileRule(
    val pattern: String,
    val targetExtensions: List<String> = emptyList(),
    val searchDepth: Int = 3,
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val action: String = "suggest",
    val description: String = ""
)

data class SkillState(
    val id: String,
    val name: String,
    val description: String = "",
    val prompt: String = "",
    val exitConditions: List<String> = emptyList(),
    val timeoutMs: Long = 0,
    val isTerminal: Boolean = false
)

data class StateTransition(
    val fromState: String,
    val event: String,
    val toState: String,
    val condition: String? = null,
    val action: String? = null
)

data class SkillStateMachine(
    val initialState: String,
    val states: List<SkillState> = emptyList(),
    val transitions: List<StateTransition> = emptyList()
)

data class SkillDefinition(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String,
    val author: String = "aiide-official",
    val tags: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
    val maxTokens: Int = 8000,
    val temperature: Double = 0.7,
    val systemPrompt: String = "",
    val pipeline: List<SkillPipelineStep> = emptyList(),
    val toolChain: List<SkillToolConfig> = emptyList(),
    val apiRoute: SkillAPIRoute? = null,
    val crossFileRules: List<CrossFileRule> = emptyList(),
    val stateMachine: SkillStateMachine? = null,
    val preProcessors: List<String> = emptyList(),
    val postProcessors: List<String> = emptyList(),
    val allowedEngines: List<String> = emptyList(),
    val fallbackSkill: String? = null,
    val metadata: Map<String, String> = emptyMap()
)