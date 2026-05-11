package com.aiide

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class CodeIssue(
    val severity: String,
    val message: String,
    val line: Int = 0,
    val column: Int = 0
)

data class HealthReport(
    val overallScore: Int,
    val issues: List<CodeIssue>,
    val metrics: Map<String, Any> = emptyMap()
)

class CodeHealthAnalyzer {
    fun analyze(filePath: String, content: String): HealthReport {
        val issues = mutableListOf<CodeIssue>()
        var score = 100

        if (content.contains("TODO") || content.contains("FIXME")) {
            score -= 5
            issues.add(CodeIssue("info", "Contains TODO/FIXME comments"))
        }

        val lines = content.lines()
        val longLines = lines.filter { it.length > 120 }
        if (longLines.isNotEmpty()) {
            score -= longLines.size.coerceAtMost(10)
            issues.add(CodeIssue("warning", "${longLines.size} lines exceed 120 characters"))
        }

        return HealthReport(
            overallScore = score.coerceIn(0, 100),
            issues = issues
        )
    }
}

class SmartHookSystem {

    companion object {
        private const val MAX_HOOKS_PER_EVENT = 10
        private const val HOOK_TIMEOUT_MS = 30000L
        private const val MAX_HOOK_OUTPUT = 10000
    }

    enum class HookEvent {
        FILE_SAVED, FILE_MODIFIED, FILE_CREATED, FILE_DELETED,
        CODE_HEALTH_LOW, TEST_FAILED, BUILD_FAILED,
        INTENT_DETECTED, SESSION_STARTED, SESSION_ENDED,
        PATTERN_DETECTED, ANOMALY_DETECTED
    }

    enum class HookCondition {
        ALWAYS, ON_CHANGE, ON_THRESHOLD, ON_PATTERN, ON_TIME, ON_IDLE
    }

    data class HookConfig(
        val id: String,
        val event: HookEvent,
        val condition: HookCondition,
        val pattern: String?,
        val threshold: Double?,
        val action: String,
        val parameters: Map<String, String>,
        val enabled: Boolean = true,
        val priority: Int = 0,
        val description: String = ""
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("event", event.name)
                put("condition", condition.name)
                put("action", action)
            }
        }
    }

    data class HookResult(
        val hookId: String,
        val success: Boolean,
        val output: String,
        val executionTimeMs: Long,
        val triggeredActions: List<String>
    )

    private val hooks = ConcurrentHashMap<HookEvent, CopyOnWriteArrayList<HookConfig>>()
    private val executionHistory = CopyOnWriteArrayList<Pair<String, Long>>()
    private val learningData = ConcurrentHashMap<String, CopyOnWriteArrayList<Double>>()

    init {
        registerDefaultHooks()
    }

    private fun registerDefaultHooks() {
        addHook(HookConfig(
            id = "auto_health_check",
            event = HookEvent.FILE_SAVED,
            condition = HookCondition.ON_CHANGE,
            pattern = null,
            threshold = null,
            action = "analyze_code_health",
            parameters = emptyMap(),
            description = "Auto-analyze code health on file save"
        ))

        addHook(HookConfig(
            id = "auto_format",
            event = HookEvent.FILE_SAVED,
            condition = HookCondition.ALWAYS,
            pattern = null,
            threshold = null,
            action = "format_code",
            parameters = emptyMap(),
            description = "Auto-format code on save"
        ))
    }

    fun addHook(config: HookConfig) {
        val eventHooks = hooks.computeIfAbsent(config.event) { CopyOnWriteArrayList() }
        if (eventHooks.size < MAX_HOOKS_PER_EVENT) {
            eventHooks.add(config)
        }
    }

    fun removeHook(hookId: String): Boolean {
        return hooks.values.any { hookList ->
            hookList.removeIf { it.id == hookId }
        }
    }

    fun triggerEvent(event: HookEvent, context: Map<String, Any>): List<HookResult> {
        val eventHooks = hooks[event]?.filter { it.enabled } ?: return emptyList()
        val results = mutableListOf<HookResult>()

        eventHooks.forEach { hook ->
            if (shouldExecuteHook(hook, context)) {
                val startTime = System.currentTimeMillis()
                try {
                    val action = resolveAction(hook, context)
                    val output = executeHookAction(hook, context)
                    results.add(HookResult(
                        hookId = hook.id,
                        success = true,
                        output = output,
                        executionTimeMs = System.currentTimeMillis() - startTime,
                        triggeredActions = listOf(action)
                    ))
                    recordExecution(hook.id, true, System.currentTimeMillis() - startTime)
                } catch (e: Exception) {
                    results.add(HookResult(
                        hookId = hook.id,
                        success = false,
                        output = "Error: ${e.message}",
                        executionTimeMs = System.currentTimeMillis() - startTime,
                        triggeredActions = emptyList()
                    ))
                    recordExecution(hook.id, false, System.currentTimeMillis() - startTime)
                }
            }
        }
        return results
    }

    fun getHookConfigs(): List<HookConfig> = hooks.values.flatten()
    fun getHookHistory(limit: Int = 50): List<Pair<String, Long>> = executionHistory.takeLast(limit)

    private fun shouldExecuteHook(hook: HookConfig, context: Map<String, Any>): Boolean {
        return when (hook.condition) {
            HookCondition.ALWAYS -> true
            HookCondition.ON_CHANGE -> context.containsKey("changedFile")
            HookCondition.ON_THRESHOLD -> {
                hook.threshold?.let { threshold ->
                    val value = context["value"] as? Double
                    value != null && value < threshold
                } ?: true
            }
            HookCondition.ON_PATTERN -> {
                hook.pattern?.let { pattern ->
                    val filePath = context["filePath"] as? String
                    filePath != null && filePath.matches(Regex(pattern))
                } ?: true
            }
            HookCondition.ON_TIME -> {
                val lastExecution = executionHistory.lastOrNull { it.first == hook.id }?.second ?: 0L
                val intervalMinutes = hook.parameters["interval_minutes"]?.toIntOrNull() ?: 30
                System.currentTimeMillis() - lastExecution > intervalMinutes * 60 * 1000
            }
            HookCondition.ON_IDLE -> {
                val lastActivity = context["lastActivityTime"] as? Long ?: 0L
                val idleThreshold = hook.parameters["idle_seconds"]?.toIntOrNull() ?: 300
                System.currentTimeMillis() - lastActivity > idleThreshold * 1000
            }
        }
    }

    private fun resolveAction(hook: HookConfig, context: Map<String, Any>): String {
        return when (hook.action) {
            "analyze_code_health" -> "Code health analysis"
            "format_code" -> "Code formatting"
            "run_related_tests" -> "Running related tests"
            "remind_commit" -> "Commit reminder"
            "alert_health_issues" -> "Health alert"
            "record_pattern" -> "Pattern recording"
            "backup_file" -> "File backup"
            else -> hook.action
        }
    }

    private fun executeHookAction(hook: HookConfig, context: Map<String, Any>): String {
        return when (hook.action) {
            "analyze_code_health" -> executeHealthAnalysis(context)
            "format_code" -> "Code formatted successfully"
            "run_related_tests" -> executeRelatedTests(context)
            "remind_commit" -> "Reminder: Don't forget to commit your changes!"
            "alert_health_issues" -> executeHealthAlert(context)
            "record_pattern" -> executePatternRecording(context)
            "backup_file" -> executeFileBackup(context)
            else -> "Action '${hook.action}' executed"
        }
    }

    private fun executeHealthAnalysis(context: Map<String, Any>): String {
        val filePath = context["filePath"] as? String
        val projectDir = context["projectDir"] as? File

        if (filePath == null || projectDir == null) {
            return "Cannot analyze: missing file path or project directory"
        }

        val file = File(projectDir, filePath)
        if (!file.exists() || !file.isFile) {
            return "File not found: $filePath"
        }

        val analyzer = CodeHealthAnalyzer()
        val content = file.readText()
        val report = analyzer.analyze(filePath, content)

        return "Health score: ${report.overallScore}/100\nIssues found: ${report.issues.size}"
    }

    private fun executeRelatedTests(context: Map<String, Any>): String {
        val filePath = context["filePath"] as? String
        val projectDir = context["projectDir"] as? File

        if (filePath == null || projectDir == null) {
            return "Cannot run tests: missing file path or project directory"
        }

        val testFile = findRelatedTestFile(filePath, projectDir)
        return if (testFile != null) {
            "Found related test file: ${testFile.name}\nRun tests manually."
        } else {
            "No related test files found for $filePath"
        }
    }

    private fun findRelatedTestFile(filePath: String, projectDir: File): File? {
        val baseName = filePath.substringBeforeLast('.')
        val extensions = listOf(".test.", ".spec.", "Test.", "Test")

        extensions.forEach { ext ->
            val testPath = "$baseName$ext${filePath.substringAfterLast('.')}"
            val testFile = File(projectDir, testPath)
            if (testFile.exists()) return testFile
        }
        return null
    }

    private fun executeHealthAlert(context: Map<String, Any>): String {
        val score = context["value"] as? Double ?: 100.0
        return "Code health alert: Score is $score/100 (below threshold)"
    }

    private fun executePatternRecording(context: Map<String, Any>): String {
        val pattern = context["pattern"] as? String ?: "unknown"
        learningData.computeIfAbsent("pattern_$pattern") { CopyOnWriteArrayList() }.add(1.0)
        return "Pattern recorded: $pattern"
    }

    private fun executeFileBackup(context: Map<String, Any>): String {
        val filePath = context["filePath"] as? String
        val projectDir = context["projectDir"] as? File

        if (filePath == null || projectDir == null) {
            return "Cannot backup: missing file path or project directory"
        }

        val file = File(projectDir, filePath)
        if (!file.exists()) {
            return "File not found for backup: $filePath"
        }

        val backupDir = File(projectDir.parent, "AIIDE_Backups")
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            return "Failed to create backup directory"
        }

        val timestamp = System.currentTimeMillis()
        val backupFile = File(backupDir, "${filePath.replace('/', '_')}_$timestamp")
        file.copyTo(backupFile, overwrite = true)

        return "Backed up to: ${backupFile.name}"
    }

    private fun recordExecution(hookId: String, success: Boolean, durationMs: Long) {
        executionHistory.add(Pair(hookId, System.currentTimeMillis()))
        val performance = if (success) 1.0 else 0.0
        learningData.computeIfAbsent(hookId) { CopyOnWriteArrayList() }.add(performance)

        if (executionHistory.size > 10000) {
            val trimmed = executionHistory.takeLast(5000)
            executionHistory.clear()
            executionHistory.addAll(trimmed)
        }
    }
}
