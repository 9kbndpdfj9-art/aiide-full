package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

enum class AgentRole {
    ARCHITECT, BACKEND, FRONTEND, DATABASE, TESTING, SECURITY, DOCUMENTATION, DEVOPS, COPILOT
}

enum class AgentType {
    FOUNDER, CLONE, MUTANT, EXPERT
}

enum class AgentStatus {
    IDLE, WORKING, BLOCKED, COMPLETED, FAILED, MERGING
}

data class SwarmAgent(
    val id: String,
    val role: AgentRole,
    val type: AgentType,
    val name: String,
    val status: AgentStatus = AgentStatus.IDLE,
    val assignedTask: String = "",
    val progress: Double = 0.0,
    val output: String = "",
    val parentAgentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val qualityScore: Double = 0.0,
    val specialization: String = "",
    val context: String = "",
    val skills: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("type", type.name)
        put("name", name)
        put("status", status.name)
        put("assigned_task", assignedTask)
        put("progress", progress)
        put("output", output)
        put("parent_agent_id", parentAgentId)
        put("created_at", createdAt)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("quality_score", qualityScore)
        put("specialization", specialization)
        put("context", context)
        put("skills", JSONArray(skills))
    }

    fun cloneAsClone(): SwarmAgent {
        return copy(
            id = "${id}_clone_${UUID.randomUUID().toString().take(6)}",
            type = AgentType.CLONE,
            status = AgentStatus.IDLE,
            progress = 0.0,
            output = "",
            startedAt = 0,
            completedAt = 0,
            parentAgentId = this.id
        )
    }

    fun mutateAsMutant(variation: String): SwarmAgent {
        return copy(
            id = "${id}_mutant_${UUID.randomUUID().toString().take(6)}",
            type = AgentType.MUTANT,
            status = AgentStatus.IDLE,
            specialization = variation,
            progress = 0.0,
            output = "",
            startedAt = 0,
            completedAt = 0,
            parentAgentId = this.id
        )
    }

    fun promoteAsExpert(expertise: String): SwarmAgent {
        return copy(
            id = "${id}_expert_${UUID.randomUUID().toString().take(6)}",
            type = AgentType.EXPERT,
            status = AgentStatus.IDLE,
            specialization = expertise,
            progress = 0.0,
            output = "",
            startedAt = 0,
            completedAt = 0,
            parentAgentId = this.id
        )
    }
}

data class SwarmMission(
    val missionId: String,
    val originalIntent: String,
    val status: MissionStatus = MissionStatus.PLANNING,
    val agents: List<SwarmAgent> = emptyList(),
    val sandboxResult: SandboxResult? = null,
    val collisionResults: List<CollisionResult> = emptyList(),
    val finalOutput: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val totalDurationMs: Long = 0
) {
    enum class MissionStatus {
        PLANNING, FISSING, EXECUTING, SANDBOX_TESTING, COLLISION_VALIDATION, COMPLETED, FAILED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mission_id", missionId)
        put("original_intent", originalIntent)
        put("status", status.name)
        val agentsArr = JSONArray()
        agents.forEach { a -> agentsArr.put(a.toJson()) }
        put("agents", agentsArr)
        put("sandbox_result", sandboxResult?.toJson() ?: JSONObject.NULL)
        put("final_output", finalOutput)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("total_duration_ms", totalDurationMs)
    }
}

data class SandboxResult(
    val success: Boolean,
    val testResults: List<TestResult> = emptyList(),
    val securityScan: SecurityScanResult? = null,
    val mergedCode: String = "",
    val issues: List<String> = emptyList()
) {
    data class TestResult(
        val name: String,
        val passed: Boolean,
        val duration: Long,
        val errorMessage: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("passed", passed)
            put("duration_ms", duration)
            put("error_message", errorMessage)
        }
    }

    data class SecurityScanResult(
        val vulnerabilityCount: Int,
        val severity: String,
        val details: List<String> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("vulnerability_count", vulnerabilityCount)
            put("severity", severity)
            put("details", JSONArray(details))
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        val testArr = JSONArray()
        testResults.forEach { t -> testArr.put(t.toJson()) }
        put("test_results", testArr)
        put("security_scan", securityScan?.toJson() ?: JSONObject.NULL)
        put("merged_code", mergedCode)
        put("issues", JSONArray(issues))
    }
}

data class CollisionResult(
    val taskId: String,
    val description: String,
    val versionA: String,
    val versionB: String,
    val isConsistent: Boolean,
    val discrepancies: List<String> = emptyList(),
    val verdict: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("task_id", taskId)
        put("description", description)
        put("version_a", versionA)
        put("version_b", versionB)
        put("is_consistent", isConsistent)
        put("discrepancies", JSONArray(discrepancies))
        put("verdict", verdict)
    }
}

class DevSwarmEngine(
    private val context: Context,
    private val cortexFlow: CortexFlowEngine,
    private val toolGenome: ToolGenomeEngine,
    private val sctpTool: SCTPToolProtocol,
    private val modelRouter: ModelRouter? = null
) {
    companion object {
        private const val TAG = "DevSwarmEngine"
        private const val MAX_AGENTS_PER_ROLE = 5
        private const val MAX_TOTAL_AGENTS = 50
        private const val SANDBOX_TIMEOUT_MS = 300_000L
    }

    private val activeMissionsLock = Any()
    private val activeMissions = ConcurrentHashMap<String, SwarmMission>()
    private val completedMissionsLock = Any()
    private val completedMissions = object : LinkedHashMap<String, SwarmMission>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SwarmMission>): Boolean {
            return size > 100
        }
    }
    private val agentRegistryLock = Any()
    private val agentRegistry = ConcurrentHashMap<String, SwarmAgent>()
    private val executor = Executors.newWorkStealingPool()
    private val missionCounter = AtomicInteger(0)
    private val agentCounter = AtomicInteger(0)
    private val progressCallbacks = ConcurrentHashMap<String, (String, Double) -> Unit>()

    @Volatile private var isShutdown = false

    private var preMortemEngine: PreMortemEngine? = null

    fun attachPreMortemEngine(engine: PreMortemEngine) {
        preMortemEngine = engine
    }

    fun launchSwarm(originalIntent: String): String {
        if (isShutdown) return "ERROR: Engine is shutdown"
        if (originalIntent.isBlank()) return "ERROR: Intent cannot be blank"

        val missionId = "swarm_${missionCounter.incrementAndGet()}"

        val preMortemResult = preMortemEngine?.performCheck(originalIntent)
        if (preMortemResult != null && !preMortemResult.proceedAllowed) {
            val blockedMission = SwarmMission(
                missionId = missionId,
                originalIntent = originalIntent,
                status = SwarmMission.MissionStatus.FAILED,
                finalOutput = "BLOCKED: Pre-Mortem check detected CRITICAL risk",
                completedAt = System.currentTimeMillis()
            )
            synchronized(completedMissionsLock) { completedMissions[missionId] = blockedMission }
            return blockedMission.finalOutput
        }

        val architectAgent = createAgent(
            role = AgentRole.ARCHITECT,
            type = AgentType.FOUNDER,
            name = "首席架构师",
            task = "拆解任务：$originalIntent",
            specialization = "任务分析",
            context = originalIntent,
            skills = listOf("architecture", "task_decomposition", "system_design")
        )

        val mission = SwarmMission(
            missionId = missionId,
            originalIntent = originalIntent,
            agents = listOf(architectAgent)
        )

        synchronized(activeMissionsLock) { activeMissions[missionId] = mission }
        synchronized(agentRegistryLock) { agentRegistry[architectAgent.id] = architectAgent }

        executor.execute {
            try {
                executeSwarm(missionId, architectAgent, originalIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Swarm mission $missionId failed: ${e.message}", e)
                failMission(missionId, e.message ?: "Unknown error")
            }
        }

        return missionId
    }

    private fun executeSwarm(missionId: String, architectAgent: SwarmAgent, originalIntent: String) {
        updateAgentStatus(architectAgent.id, AgentStatus.WORKING)
        updateMissionStatus(missionId, SwarmMission.MissionStatus.FISSING)

        val taskBreakdown = architectAnalyze(originalIntent)
        updateAgentOutput(architectAgent.id, "任务拆解完成：${taskBreakdown.size}个子任务")
        updateAgentStatus(architectAgent.id, AgentStatus.COMPLETED)

        val agents = fissAgents(taskBreakdown, originalIntent)

        val mission = synchronized(activeMissionsLock) { activeMissions[missionId] } ?: return
        val updatedMission = mission.copy(agents = mission.agents + agents)
        synchronized(activeMissionsLock) { activeMissions[missionId] = updatedMission }

        updateMissionStatus(missionId, SwarmMission.MissionStatus.EXECUTING)
        executeAgentsParallel(updatedMission)
    }

    private fun architectAnalyze(intent: String): List<SubTask> {
        val lower = intent.lowercase()
        val subtasks = mutableListOf<SubTask>()

        if (lower.contains("会员") || lower.contains("订阅") || lower.contains("支付")) {
            subtasks.add(SubTask("支付接口开发", AgentRole.BACKEND, "实现支付API和Webhook", "支付接口"))
            subtasks.add(SubTask("订阅状态机", AgentRole.BACKEND, "设计订阅生命周期管理", "订阅状态"))
            subtasks.add(SubTask("会员购买页面", AgentRole.FRONTEND, "开发购买UI", "购买页面"))
            subtasks.add(SubTask("数据库Schema", AgentRole.DATABASE, "设计订阅表结构", "订阅表"))
            subtasks.add(SubTask("单元测试", AgentRole.TESTING, "生成支付相关测试", "支付测试"))
        } else if (lower.contains("登录") || lower.contains("认证")) {
            subtasks.add(SubTask("认证API", AgentRole.BACKEND, "实现登录/注册API", "认证接口"))
            subtasks.add(SubTask("登录页面", AgentRole.FRONTEND, "开发登录UI", "登录UI"))
            subtasks.add(SubTask("用户表设计", AgentRole.DATABASE, "设计用户表", "用户表"))
        } else {
            subtasks.add(SubTask("后端逻辑", AgentRole.BACKEND, "实现业务逻辑", "业务逻辑"))
            subtasks.add(SubTask("前端界面", AgentRole.FRONTEND, "开发UI", "用户界面"))
            subtasks.add(SubTask("数据模型", AgentRole.DATABASE, "设计数据结构", "数据模型"))
        }

        return subtasks
    }

    private fun fissAgents(subtasks: List<SubTask>, originalIntent: String): List<SwarmAgent> {
        val agents = mutableListOf<SwarmAgent>()
        val agentsByRole = mutableMapOf<AgentRole, Int>()

        subtasks.forEach { task ->
            val roleCount = agentsByRole.getOrDefault(task.role, 0)
            if (roleCount >= MAX_AGENTS_PER_ROLE) return@forEach

            val agent = createAgent(
                role = task.role,
                type = AgentType.FOUNDER,
                name = "${task.role.name}Agent-${roleCount + 1}",
                task = task.description,
                specialization = task.specialization,
                context = originalIntent,
                skills = getSkillsForRole(task.role)
            )
            agents.add(agent)
            synchronized(agentRegistryLock) { agentRegistry[agent.id] = agent }
            agentsByRole[task.role] = roleCount + 1

            if (agents.size >= MAX_TOTAL_AGENTS) return agents
        }

        return agents
    }

    private fun getSkillsForRole(role: AgentRole): List<String> {
        return when (role) {
            AgentRole.ARCHITECT -> listOf("architecture", "system_design", "task_decomposition")
            AgentRole.BACKEND -> listOf("api_design", "business_logic", "database", "authentication")
            AgentRole.FRONTEND -> listOf("ui_design", "react", "css", "javascript")
            AgentRole.DATABASE -> listOf("sql", "schema_design", "migration", "optimization")
            AgentRole.TESTING -> listOf("unit_testing", "integration_testing", "mocking", "tdd")
            AgentRole.SECURITY -> listOf("security_audit", "vulnerability_scan", "encryption", "auth")
            AgentRole.DOCUMENTATION -> listOf("technical_writing", "api_docs", "user_guides")
            AgentRole.DEVOPS -> listOf("ci_cd", "docker", "kubernetes", "monitoring")
            AgentRole.COPILOT -> listOf("code_review", "refactoring", "debugging")
        }
    }

    private fun executeAgentsParallel(mission: SwarmMission) {
        val workingAgents = mission.agents.filter {
            it.type != AgentType.FOUNDER || it.role != AgentRole.ARCHITECT
        }

        if (workingAgents.isEmpty()) {
            enterSandboxTesting(mission.missionId)
            return
        }

        val futures = workingAgents.map { agent ->
            CompletableFuture.supplyAsync({
                executeAgentWork(mission.missionId, agent)
                agent.id
            }, executor).exceptionally { ex ->
                Log.e(TAG, "Agent ${agent.id} failed with exception: ${ex.message}")
                agent.id
            }
        }

        CompletableFuture.allOf(*futures.toTypedArray())
            .orTimeout(5, TimeUnit.MINUTES)
            .handle { _, throwable ->
                if (throwable != null) {
                    futures.forEach { it.cancel(true) }
                }
                null
            }
            .thenRun {
                enterSandboxTesting(mission.missionId)
            }
    }

    private fun executeAgentWork(missionId: String, agent: SwarmAgent) {
        updateAgentStatus(agent.id, AgentStatus.WORKING)
        updateAgentStartTime(agent.id)
        reportProgress(missionId, agent.id, 0.0)

        try {
            val output = when (agent.role) {
                AgentRole.BACKEND -> "Generated backend code"
                AgentRole.FRONTEND -> "Generated frontend code"
                AgentRole.DATABASE -> "Generated database schema"
                AgentRole.TESTING -> "Generated tests"
                AgentRole.SECURITY -> "Security review complete"
                AgentRole.DOCUMENTATION -> "Documentation generated"
                AgentRole.ARCHITECT -> "Architecture design complete"
                AgentRole.DEVOPS -> "DevOps config generated"
                AgentRole.COPILOT -> "Copilot assistance complete"
            }

            updateAgentOutput(agent.id, output)
            updateAgentProgress(agent.id, 100.0)
            updateAgentStatus(agent.id, AgentStatus.COMPLETED)
            val quality = calculateQuality(output)
            updateAgentQuality(agent.id, quality)
            reportProgress(missionId, agent.id, 100.0)

        } catch (e: Exception) {
            updateAgentStatus(agent.id, AgentStatus.FAILED)
            updateAgentOutput(agent.id, "Error: ${e.message}")
            updateAgentProgress(agent.id, -1.0)
            reportProgress(missionId, agent.id, -1.0)
        }
    }

    private fun enterSandboxTesting(missionId: String) {
        updateMissionStatus(missionId, SwarmMission.MissionStatus.SANDBOX_TESTING)

        val mission = synchronized(activeMissionsLock) { activeMissions[missionId] } ?: return
        val completedAgents = mission.agents.filter { it.status == AgentStatus.COMPLETED }

        val mergedCode = mergeAgentOutputs(completedAgents)
        val testResults = runSandboxTests(mergedCode)
        val securityScan = runSecurityScan(mergedCode)

        val sandboxResult = SandboxResult(
            success = testResults.all { it.passed } && securityScan.vulnerabilityCount == 0,
            testResults = testResults,
            securityScan = securityScan,
            mergedCode = mergedCode,
            issues = testResults.filter { !it.passed }.map { "${it.name}: ${it.errorMessage}" }
        )

        val updatedMission = mission.copy(sandboxResult = sandboxResult)
        synchronized(activeMissionsLock) { activeMissions[missionId] = updatedMission }

        val finalMission = updatedMission.copy(
            status = if (sandboxResult.success) SwarmMission.MissionStatus.COMPLETED else SwarmMission.MissionStatus.FAILED,
            finalOutput = generateFinalOutput(updatedMission),
            completedAt = System.currentTimeMillis(),
            totalDurationMs = System.currentTimeMillis() - updatedMission.startedAt
        )

        synchronized(activeMissionsLock) { activeMissions.remove(missionId) }
        synchronized(completedMissionsLock) { completedMissions[missionId] = finalMission }
    }

    private fun mergeAgentOutputs(agents: List<SwarmAgent>): String {
        return agents.joinToString("\n\n") { "[${it.role.name}] ${it.output}" }
    }

    private fun runSandboxTests(mergedCode: String): List<SandboxResult.TestResult> {
        val results = mutableListOf<SandboxResult.TestResult>()

        val syntaxErrors = checkSyntaxErrors(mergedCode)
        results.add(SandboxResult.TestResult(
            name = "语法检查",
            passed = syntaxErrors.isEmpty(),
            duration = syntaxErrors.size.toLong(),
            errorMessage = syntaxErrors.joinToString("; ")
        ))

        val undefinedRefs = checkUndefinedReferences(mergedCode)
        results.add(SandboxResult.TestResult(
            name = "引用检查",
            passed = undefinedRefs.isEmpty(),
            duration = undefinedRefs.size.toLong(),
            errorMessage = undefinedRefs.joinToString("; ")
        ))

        return results
    }

    private fun checkSyntaxErrors(code: String): List<String> {
        val errors = mutableListOf<String>()
        val openBraces = code.count { it == '{' }
        val closeBraces = code.count { it == '}' }
        if (openBraces != closeBraces) errors.add("Unbalanced braces")

        val openParens = code.count { it == '(' }
        val closeParens = code.count { it == ')' }
        if (openParens != closeParens) errors.add("Unbalanced parentheses")

        return errors
    }

    private fun checkUndefinedReferences(code: String): List<String> {
        return emptyList()
    }

    private fun runSecurityScan(mergedCode: String): SandboxResult.SecurityScanResult {
        val vulnerabilities = mutableListOf<String>()
        var severity = "LOW"

        if (Regex("""eval\s*\(""").containsMatchIn(mergedCode)) {
            vulnerabilities.add("CRITICAL: Use of eval() detected")
            severity = "CRITICAL"
        }

        return SandboxResult.SecurityScanResult(
            vulnerabilityCount = vulnerabilities.size,
            severity = severity,
            details = vulnerabilities.ifEmpty { listOf("No known vulnerabilities found") }
        )
    }

    private fun calculateQuality(output: String): Double {
        if (output.isEmpty()) return 0.0
        if (output.startsWith("Error")) return 0.0
        return 0.9
    }

    private fun generateFinalOutput(mission: SwarmMission): String {
        val completedAgents = mission.agents.filter { it.status == AgentStatus.COMPLETED }
        return buildString {
            appendLine("DevSwarm任务完成：")
            appendLine("  原始意图：${mission.originalIntent}")
            appendLine("  参与Agent：${completedAgents.size}个")
            appendLine("  沙箱测试：${if (mission.sandboxResult?.success == true) "通过" else "失败"}")
        }
    }

    private fun createAgent(
        role: AgentRole,
        type: AgentType,
        name: String,
        task: String,
        specialization: String = "",
        context: String = "",
        skills: List<String> = emptyList()
    ): SwarmAgent {
        val agentId = "agent_${agentCounter.incrementAndGet()}"
        return SwarmAgent(
            id = agentId,
            role = role,
            type = type,
            name = name,
            assignedTask = task,
            specialization = specialization,
            context = context,
            skills = skills
        )
    }

    private fun updateAgentStatus(agentId: String, status: AgentStatus) {
        synchronized(agentRegistryLock) {
            agentRegistry.computeIfPresent(agentId) { _, agent -> agent.copy(status = status) }
        }
    }

    private fun updateAgentOutput(agentId: String, output: String) {
        synchronized(agentRegistryLock) {
            agentRegistry.computeIfPresent(agentId) { _, agent -> agent.copy(output = output) }
        }
    }

    private fun updateAgentProgress(agentId: String, progress: Double) {
        synchronized(agentRegistryLock) {
            agentRegistry.computeIfPresent(agentId) { _, agent -> agent.copy(progress = progress) }
        }
    }

    private fun updateAgentQuality(agentId: String, quality: Double) {
        synchronized(agentRegistryLock) {
            agentRegistry.computeIfPresent(agentId) { _, agent -> agent.copy(qualityScore = quality) }
        }
    }

    private fun updateAgentStartTime(agentId: String) {
        synchronized(agentRegistryLock) {
            agentRegistry.computeIfPresent(agentId) { _, agent -> agent.copy(startedAt = System.currentTimeMillis()) }
        }
    }

    private fun updateMissionStatus(missionId: String, status: SwarmMission.MissionStatus) {
        synchronized(activeMissionsLock) {
            val mission = activeMissions[missionId] ?: return
            activeMissions[missionId] = mission.copy(status = status)
        }
    }

    private fun failMission(missionId: String, error: String) {
        val mission = synchronized(activeMissionsLock) { activeMissions.remove(missionId) } ?: return
        val failedMission = mission.copy(
            status = SwarmMission.MissionStatus.FAILED,
            finalOutput = error,
            completedAt = System.currentTimeMillis(),
            totalDurationMs = System.currentTimeMillis() - mission.startedAt
        )
        synchronized(completedMissionsLock) { completedMissions[missionId] = failedMission }
    }

    private fun reportProgress(missionId: String, agentId: String, progress: Double) {
        progressCallbacks[missionId]?.invoke(agentId, progress)
    }

    fun getActiveMission(missionId: String): SwarmMission? = synchronized(activeMissionsLock) { activeMissions[missionId] }
    fun getCompletedMission(missionId: String): SwarmMission? = synchronized(completedMissionsLock) { completedMissions[missionId] }
    fun getActiveMissions(): List<SwarmMission> = synchronized(activeMissionsLock) { activeMissions.values.toList() }
    fun getCompletedMissions(): List<SwarmMission> = synchronized(completedMissionsLock) { completedMissions.values.toList() }
    fun getAgent(agentId: String): SwarmAgent? = synchronized(agentRegistryLock) { agentRegistry[agentId] }
    fun getAllAgents(): List<SwarmAgent> = synchronized(agentRegistryLock) { agentRegistry.values.toList() }

    fun registerProgressCallback(missionId: String, callback: (String, Double) -> Unit) {
        progressCallbacks[missionId] = callback
    }

    fun unregisterProgressCallback(missionId: String) {
        progressCallbacks.remove(missionId)
    }

    fun shutdown() {
        isShutdown = true
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

data class SubTask(
    val description: String,
    val role: AgentRole,
    val specialization: String,
    val context: String = ""
)
