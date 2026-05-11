package com.aiide

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

enum class CognitivePhase {
    THINKING, RESPONDING, ACTING, CHECKING, DELIVERING
}

data class ThinkingAnalysis(
    val needsClarification: Boolean,
    val isDirectlyExecutable: Boolean,
    val complexity: Double = 0.0,
    val requiredTools: List<String> = emptyList(),
    val ambiguities: List<String> = emptyList(),
    val suggestedActions: List<String> = emptyList()
)

data class VerificationResult(
    val allPassed: Boolean,
    val canAutoFix: Boolean,
    val failures: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)

data class CognitiveStep(
    val id: String,
    val phase: CognitivePhase,
    val title: String,
    val description: String,
    val isCollapsed: Boolean = true,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val details: Map<String, String> = emptyMap(),
    val children: List<CognitiveStep> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("phase", phase.name)
        put("title", title)
        put("description", description)
        put("is_collapsed", isCollapsed)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("details", JSONObject(details))
        val childrenArr = JSONArray()
        children.forEach { c -> childrenArr.put(c.toJson()) }
        put("children", childrenArr)
    }
}

data class CognitiveCycleRecord(
    val cycleId: String,
    val userIntent: String,
    val steps: List<CognitiveStep> = emptyList(),
    val status: CycleStatus = CycleStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val totalDurationMs: Long = 0,
    val toolCalls: Int = 0,
    val tokensConsumed: Int = 0,
    val finalResult: String = "",
    val currentPhase: CognitivePhase = CognitivePhase.THINKING,
    val iterationCount: Int = 0
) {
    enum class CycleStatus {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("cycle_id", cycleId)
        put("user_intent", userIntent)
        val stepsArr = JSONArray()
        steps.forEach { s -> stepsArr.put(s.toJson()) }
        put("steps", stepsArr)
        put("status", status.name)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("total_duration_ms", totalDurationMs)
        put("tool_calls", toolCalls)
        put("tokens_consumed", tokensConsumed)
        put("final_result", finalResult)
        put("current_phase", currentPhase.name)
        put("iteration_count", iterationCount)
    }
}

data class ToolCall(
    val id: String,
    val toolName: String,
    val parameters: Map<String, String>,
    val context: String
)

data class ToolResult(
    val id: String,
    val success: Boolean,
    val output: String = "",
    val errorMessage: String = ""
)

class CortexFlowEngine(
    private val context: Context,
    private val toolGenome: ToolGenomeEngine,
    private val sctpTool: SCTPToolProtocol
) {
    companion object {
        private const val TAG = "CortexFlowEngine"
        private const val MAX_RETRIES = 3
        private const val MAX_ITERATIONS = 10
        private const val MAX_COMPLETED_CYCLES = 200
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("cortex_flow", Context.MODE_PRIVATE)
    private val activeCycles = ConcurrentHashMap<String, CognitiveCycleRecord>()
    private val completedCycles = object : LinkedHashMap<String, CognitiveCycleRecord>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CognitiveCycleRecord>): Boolean {
            return size > MAX_COMPLETED_CYCLES
        }
    }
    private val completedLock = Any()
    private val cycleCounter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var thinkingRouter: ThinkingRouterEngine? = null
    private var preMortemEngine: PreMortemEngine? = null

    fun attachThinkingRouter(router: ThinkingRouterEngine) {
        thinkingRouter = router
    }

    fun attachPreMortemEngine(engine: PreMortemEngine) {
        preMortemEngine = engine
    }

    private data class CycleState(
        var currentPhase: CognitivePhase = CognitivePhase.THINKING,
        var iterationCount: Int = 0,
        var retryCount: Int = 0,
        var ambiguities: MutableList<String> = mutableListOf(),
        var pendingActions: MutableList<String> = mutableListOf(),
        var toolResults: MutableList<ToolResult> = mutableListOf(),
        var analysisResult: ThinkingAnalysis? = null
    )

    fun startCycle(userIntent: String): String {
        val cycleId = "cycle_${cycleCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}"
        val cycle = CognitiveCycleRecord(cycleId = cycleId, userIntent = userIntent)
        activeCycles[cycleId] = cycle
        return cycleId
    }

    fun addStep(cycleId: String, step: CognitiveStep): CognitiveCycleRecord {
        val cycle = activeCycles[cycleId] ?: return CognitiveCycleRecord(cycleId, "")
        val updatedCycle = cycle.copy(
            steps = cycle.steps + step,
            toolCalls = cycle.toolCalls + (if (step.phase == CognitivePhase.ACTING) 1 else 0)
        )
        activeCycles[cycleId] = updatedCycle
        return updatedCycle
    }

    fun executeFullCycle(
        userIntent: String,
        onEchoReady: suspend (String) -> Boolean,
        onNeedQuestion: suspend (String) -> String?,
        onToolCall: suspend (ToolCall) -> ToolResult,
        onComplete: suspend (CognitiveCycleRecord) -> Unit
    ) {
        val cycleId = startCycle(userIntent)
        val state = CycleState()

        scope.launch {
            try {
                while (state.currentPhase != CognitivePhase.DELIVERING &&
                       state.iterationCount < MAX_ITERATIONS &&
                       state.retryCount < MAX_RETRIES) {

                    state.iterationCount++
                    Log.d(TAG, "Cycle $cycleId: Phase=${state.currentPhase.name}, Iteration=${state.iterationCount}")

                    when (state.currentPhase) {
                        CognitivePhase.THINKING -> {
                            state.currentPhase = executeThinkingPhase(cycleId, userIntent, state)
                        }
                        CognitivePhase.RESPONDING -> {
                            state.currentPhase = executeRespondingPhase(cycleId, userIntent, state, onEchoReady, onNeedQuestion)
                        }
                        CognitivePhase.ACTING -> {
                            state.currentPhase = executeActingPhase(cycleId, state, onToolCall)
                        }
                        CognitivePhase.CHECKING -> {
                            state.currentPhase = executeCheckingPhase(cycleId, state)
                        }
                        CognitivePhase.DELIVERING -> {
                            break
                        }
                    }
                }

                executeDeliveringPhase(cycleId, userIntent, state)
                val finalCycle = activeCycles[cycleId]?.copy(
                    status = CognitiveCycleRecord.CycleStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    totalDurationMs = System.currentTimeMillis() - (activeCycles[cycleId]?.startedAt ?: System.currentTimeMillis())
                )
                finalCycle?.let {
                    activeCycles.remove(cycleId)
                    synchronized(completedLock) { completedCycles[cycleId] = it }
                    withContext(Dispatchers.Main) {
                        onComplete(it)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Cycle $cycleId failed: ${e.message}", e)
                val failedCycle = activeCycles[cycleId]?.copy(
                    status = CognitiveCycleRecord.CycleStatus.FAILED,
                    completedAt = System.currentTimeMillis(),
                    finalResult = "Failed: ${e.message}"
                )
                failedCycle?.let {
                    activeCycles.remove(cycleId)
                    synchronized(completedLock) { completedCycles[cycleId] = it }
                }
            }
        }
    }

    private fun executeThinkingPhase(cycleId: String, userIntent: String, state: CycleState): CognitivePhase {
        val thinkingStep = CognitiveStep.Builder(
            phase = CognitivePhase.THINKING,
            title = "理解阶段"
        )
        thinkingStep.addDetail("Intent Classifier", "判定中")

        val routeDecision = thinkingRouter?.route(userIntent)
        if (routeDecision != null) {
            thinkingStep.addDetail("Thinking Path", routeDecision.path.name)
            thinkingStep.addDetail("Route Confidence", "${String.format("%.2f", routeDecision.confidence)}")
            thinkingStep.addDetail("Estimated Steps", "${routeDecision.estimatedSteps}")
            thinkingStep.addDetail("Estimated Tokens", "${routeDecision.estimatedTokens}")
        }

        val preMortemResult = preMortemEngine?.performCheck(userIntent)
        if (preMortemResult != null) {
            thinkingStep.addDetail("Pre-Mortem Risk", preMortemResult.overallRisk.name)
            thinkingStep.addDetail("Proceed Allowed", "${preMortemResult.proceedAllowed}")
            if (!preMortemResult.proceedAllowed) {
                thinkingStep.addDetail("Blocked", "Pre-Mortem check blocked execution")
                addStep(cycleId, thinkingStep.build().copy(completedAt = System.currentTimeMillis()))
                return CognitivePhase.RESPONDING
            }
        }

        val analysis = performDeepAnalysis(userIntent)
        state.analysisResult = analysis
        thinkingStep.addDetail("Complexity", "${analysis.complexity}")
        thinkingStep.addDetail("Required Tools", analysis.requiredTools.joinToString(", "))
        
        if (analysis.ambiguities.isNotEmpty()) {
            thinkingStep.addDetail("Ambiguities", analysis.ambiguities.joinToString("; "))
            state.ambiguities.addAll(analysis.ambiguities)
        }

        addStep(cycleId, thinkingStep.build().copy(completedAt = System.currentTimeMillis()))

        return when {
            analysis.needsClarification -> CognitivePhase.RESPONDING
            analysis.isDirectlyExecutable -> CognitivePhase.ACTING
            else -> CognitivePhase.RESPONDING
        }
    }

    private suspend fun executeRespondingPhase(
        cycleId: String,
        userIntent: String,
        state: CycleState,
        onEchoReady: suspend (String) -> Boolean,
        onNeedQuestion: suspend (String) -> String?
    ): CognitivePhase {
        val echo = generateUnderstandingEcho(userIntent, state)
        val respondingStep = CognitiveStep.Builder(
            phase = CognitivePhase.RESPONDING,
            title = "理解回响"
        )
        respondingStep.description = echo
        respondingStep.addDetail("Echo", echo)

        addStep(cycleId, respondingStep.build().copy(completedAt = System.currentTimeMillis()))

        val userConfirmed = onEchoReady(echo)
        if (!userConfirmed && state.ambiguities.isNotEmpty()) {
            val question = generateClarificationQuestion(state.ambiguities)
            val respondingStep2 = CognitiveStep.Builder(
                phase = CognitivePhase.RESPONDING,
                title = "澄清问题"
            )
            respondingStep2.description = question
            addStep(cycleId, respondingStep2.build().copy(completedAt = System.currentTimeMillis()))

            val userAnswer = onNeedQuestion(question)
            if (userAnswer != null && userAnswer.isNotEmpty()) {
                state.ambiguities.clear()
                return CognitivePhase.THINKING
            }
        }

        return CognitivePhase.ACTING
    }

    private suspend fun executeActingPhase(
        cycleId: String,
        state: CycleState,
        onToolCall: suspend (ToolCall) -> ToolResult
    ): CognitivePhase {
        val analysis = state.analysisResult ?: return CognitivePhase.CHECKING

        val actingStep = CognitiveStep.Builder(
            phase = CognitivePhase.ACTING,
            title = "执行阶段"
        )

        val deferredResults = analysis.requiredTools.map { toolName ->
            val toolCall = ToolCall(
                id = "tc_${UUID.randomUUID().toString().take(6)}",
                toolName = toolName,
                parameters = emptyMap(),
                context = "为意图 '$cycleId' 执行 $toolName"
            )
            coroutineScope {
                async(Dispatchers.Default) {
                    val result = onToolCall(toolCall)
                    toolGenome.recordToolCall(
                        toolName = toolName,
                        success = result.success,
                        latencyMs = 300,
                        tokensConsumed = 120
                    )
                    result
                }
            }
        }

        val results = deferredResults.awaitAll()
        state.toolResults.addAll(results)

        analysis.requiredTools.forEach { toolName ->
            actingStep.addDetail("Tool: $toolName", "Called")
        }

        addStep(cycleId, actingStep.build().copy(completedAt = System.currentTimeMillis()))

        return CognitivePhase.CHECKING
    }

    private fun executeCheckingPhase(cycleId: String, state: CycleState): CognitivePhase {
        val verification = verifyResults(state)
        
        val checkingStep = CognitiveStep.Builder(
            phase = CognitivePhase.CHECKING,
            title = "验证阶段"
        )
        checkingStep.description = if (verification.allPassed) "影子自检：全绿" else "发现 ${verification.failures.size} 个问题"
        checkingStep.addDetail("All Passed", "${verification.allPassed}")
        checkingStep.addDetail("Can Auto Fix", "${verification.canAutoFix}")
        
        addStep(cycleId, checkingStep.build().copy(completedAt = System.currentTimeMillis()))

        return when {
            verification.allPassed -> CognitivePhase.DELIVERING
            verification.canAutoFix -> {
                state.retryCount++
                CognitivePhase.ACTING
            }
            else -> CognitivePhase.RESPONDING
        }
    }

    private fun executeDeliveringPhase(cycleId: String, userIntent: String, state: CycleState) {
        val deliveringStep = CognitiveStep.Builder(
            phase = CognitivePhase.DELIVERING,
            title = "交付阶段"
        )
        deliveringStep.description = "成果已交付"
        deliveringStep.addDetail("Iterations", "${state.iterationCount}")
        deliveringStep.addDetail("Retries", "${state.retryCount}")
        deliveringStep.addDetail("Tool Calls", "${state.toolResults.size}")
        
        addStep(cycleId, deliveringStep.build().copy(completedAt = System.currentTimeMillis()))
    }

    private fun performDeepAnalysis(userIntent: String): ThinkingAnalysis {
        val lower = userIntent.lowercase()
        
        val needsClarification = lower.length < 20 || 
            lower.contains("等") || 
            lower.contains("类似") ||
            lower.contains("大概")
        
        val isDirectlyExecutable = lower.contains("创建") || 
            lower.contains("添加") || 
            lower.contains("删除") ||
            lower.contains("修改")
        
        val requiredTools = mutableListOf<String>()
        if (lower.contains("函数") || lower.contains("方法")) requiredTools.add("semcore_extract_fingerprint")
        if (lower.contains("测试")) requiredTools.add("test_gen_agent")
        if (lower.contains("验证") || lower.contains("检查")) requiredTools.add("shadow_validator")
        
        val ambiguities = mutableListOf<String>()
        if (lower.contains("支付")) {
            ambiguities.add("支付方式：微信/支付宝/银行卡？")
            ambiguities.add("是否需要退款功能？")
        }
        if (lower.contains("登录")) {
            ambiguities.add("认证方式：手机号/邮箱/第三方？")
            ambiguities.add("JWT过期时间？")
        }
        
        return ThinkingAnalysis(
            needsClarification = needsClarification,
            isDirectlyExecutable = isDirectlyExecutable,
            complexity = if (requiredTools.size > 2) 0.8 else 0.5,
            requiredTools = requiredTools.ifEmpty { listOf("code_gen_agent") },
            ambiguities = ambiguities
        )
    }

    private fun generateUnderstandingEcho(userIntent: String, state: CycleState): String {
        val analysis = state.analysisResult
        return buildString {
            append("我理解您的意图是：$userIntent")
            if (analysis != null) {
                append("\n复杂度评估：${analysis.complexity}")
                append("\n需要工具：${analysis.requiredTools.joinToString(", ")}")
            }
            if (state.ambiguities.isNotEmpty()) {
                append("\n\n发现 ${state.ambiguities.size} 个需要澄清的点：")
                state.ambiguities.forEach { append("\n- $it") }
            }
        }
    }

    private fun generateClarificationQuestion(ambiguities: List<String>): String {
        return buildString {
            append("在开始之前，请确认以下几点：\n")
            ambiguities.take(3).forEachIndexed { index, ambiguity ->
                append("${index + 1}. $ambiguity\n")
            }
            append("\n或直接说按最常见方案做")
        }
    }

    private fun verifyResults(state: CycleState): VerificationResult {
        val failures = mutableListOf<String>()
        
        state.toolResults.forEach { result ->
            if (!result.success) {
                failures.add("工具调用失败：${result.errorMessage}")
            }
        }
        
        return VerificationResult(
            allPassed = failures.isEmpty(),
            canAutoFix = failures.size <= 1,
            failures = failures
        )
    }

    fun getActiveCycle(cycleId: String): CognitiveCycleRecord? = activeCycles[cycleId]

    fun getCompletedCycle(cycleId: String): CognitiveCycleRecord? = synchronized(completedLock) { completedCycles[cycleId] }

    fun getActiveCycles(): List<CognitiveCycleRecord> = activeCycles.values.toList()

    fun getToolCallStats(): Map<String, Any> {
        val allCycles = synchronized(completedLock) { completedCycles.values.toList() } + activeCycles.values.toList()
        val totalToolCalls = allCycles.sumOf { it.toolCalls }
        val totalTokens = allCycles.sumOf { it.tokensConsumed }
        
        return mapOf(
            "total_cycles" to allCycles.size,
            "total_tool_calls" to totalToolCalls,
            "total_tokens_consumed" to totalTokens,
            "avg_tokens_per_call" to if (totalToolCalls > 0) totalTokens / totalToolCalls else 0
        )
    }

    fun shutdown() {
        scope.cancel()
    }
}
