package com.aiide

import android.util.Log
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class VerificationStatus {
    PENDING, PASSED, FAILED, SKIPPED, ROLLED_BACK
}

data class AtomicStep(
    val id: String,
    val chainId: String,
    val index: Int,
    val description: String,
    val action: String,
    val inputSnapshot: String = "",
    val outputSnapshot: String = "",
    val status: VerificationStatus = VerificationStatus.PENDING,
    val verificationRule: String = "",
    val errorMessage: String = "",
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val rolledBackAt: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("chain_id", chainId)
        put("index", index)
        put("description", description)
        put("action", action)
        put("status", status.name)
        put("verification_rule", verificationRule)
        put("error_message", errorMessage)
    }
}

data class VerificationChain(
    val chainId: String,
    val missionId: String,
    val steps: List<AtomicStep> = emptyList(),
    val status: ChainStatus = ChainStatus.PENDING,
    val currentStepIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val totalPassed: Int = 0,
    val totalFailed: Int = 0,
    val totalRolledBack: Int = 0
) {
    enum class ChainStatus {
        PENDING, RUNNING, COMPLETED, FAILED, PARTIALLY_COMPLETED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain_id", chainId)
        put("mission_id", missionId)
        put("status", status.name)
        put("current_step_index", currentStepIndex)
        put("total_passed", totalPassed)
        put("total_failed", totalFailed)
    }
}

data class VerificationRule(
    val name: String,
    val description: String,
    val checkType: String,
    val parameters: Map<String, String> = emptyMap()
)

class AtomicVerificationChain {
    companion object {
        private const val TAG = "AtomicVerificationChain"
        private const val MAX_CHAINS = 200
    }

    private val activeChains = ConcurrentHashMap<String, VerificationChain>()
    private val completedChains = object : LinkedHashMap<String, VerificationChain>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VerificationChain>): Boolean {
            return size > MAX_CHAINS
        }
    }
    private val completedLock = Any()
    private val chainCounter = AtomicLong(0)
    private val stepCounter = AtomicLong(0)

    private val builtInRules = mapOf(
        "syntax_check" to VerificationRule("syntax_check", "语法检查", "static", mapOf("level" to "error")),
        "type_check" to VerificationRule("type_check", "类型检查", "static", mapOf("level" to "error")),
        "null_safety" to VerificationRule("null_safety", "空指针安全", "static", mapOf("level" to "warning")),
        "runtime_exception" to VerificationRule("runtime_exception", "运行时异常检测", "dynamic", mapOf("timeout_ms" to "5000"))
    )

    fun createChain(missionId: String, stepDefinitions: List<StepDefinition>): VerificationChain {
        val chainId = "chain_${chainCounter.incrementAndGet()}"
        val steps = stepDefinitions.mapIndexed { index, def ->
            AtomicStep(
                id = "step_${stepCounter.incrementAndGet()}",
                chainId = chainId,
                index = index,
                description = def.description,
                action = def.action,
                verificationRule = def.verificationRule
            )
        }

        val chain = VerificationChain(
            chainId = chainId,
            missionId = missionId,
            steps = steps
        )

        activeChains[chainId] = chain
        return chain
    }

    fun executeStep(
        chainId: String,
        stepIndex: Int,
        executor: (AtomicStep) -> StepResult
    ): VerificationChain {
        val chain = activeChains[chainId] ?: return VerificationChain(chainId, "")
        if (stepIndex >= chain.steps.size) return chain

        val step = chain.steps[stepIndex]
        val result = executor(step)

        val verified = result.success

        val updatedSteps = chain.steps.toMutableList()
        updatedSteps[stepIndex] = step.copy(
            status = if (verified) VerificationStatus.PASSED else VerificationStatus.FAILED,
            outputSnapshot = result.output.take(500),
            errorMessage = result.errorMessage,
            completedAt = System.currentTimeMillis()
        )

        var currentChain = chain.copy(
            steps = updatedSteps,
            currentStepIndex = stepIndex,
            status = VerificationChain.ChainStatus.RUNNING,
            totalPassed = if (verified) chain.totalPassed + 1 else chain.totalPassed,
            totalFailed = if (!verified) chain.totalFailed + 1 else chain.totalFailed
        )

        activeChains[chainId] = currentChain
        return currentChain
    }

    fun executeChain(
        chainId: String,
        executor: (AtomicStep) -> StepResult,
        onStepComplete: ((AtomicStep, VerificationChain) -> Unit)? = null
    ): VerificationChain {
        val chain = activeChains[chainId] ?: return VerificationChain(chainId, "")
        var currentChain = chain.copy(status = VerificationChain.ChainStatus.RUNNING)
        activeChains[chainId] = currentChain

        for (i in currentChain.steps.indices) {
            currentChain = executeStep(chainId, i, executor)
            val step = currentChain.steps[i]
            onStepComplete?.invoke(step, currentChain)

            if (step.status == VerificationStatus.FAILED) {
                currentChain = currentChain.copy(
                    status = VerificationChain.ChainStatus.FAILED,
                    completedAt = System.currentTimeMillis()
                )
                activeChains.remove(chainId)
                synchronized(completedLock) { completedChains[chainId] = currentChain }
                return currentChain
            }
        }

        currentChain = currentChain.copy(
            status = VerificationChain.ChainStatus.COMPLETED,
            completedAt = System.currentTimeMillis()
        )

        activeChains.remove(chainId)
        synchronized(completedLock) { completedChains[chainId] = currentChain }
        return currentChain
    }

    fun getChain(chainId: String): VerificationChain? {
        return activeChains[chainId] ?: synchronized(completedLock) { completedChains[chainId] }
    }

    fun getActiveChains(): List<VerificationChain> = activeChains.values.toList()

    fun getCompletedChains(limit: Int = 50): List<VerificationChain> {
        synchronized(completedLock) {
            return completedChains.values.toList().takeLast(limit)
        }
    }

    fun getVerificationRules(): List<VerificationRule> = builtInRules.values.toList()

    fun getStats(): JSONObject {
        val totalChains = chainCounter.get()
        val activeCount = activeChains.size
        val completedCount = synchronized(completedLock) { completedChains.size }
        val passedChains = synchronized(completedLock) {
            completedChains.values.count { it.status == VerificationChain.ChainStatus.COMPLETED }
        }

        return JSONObject().apply {
            put("total_chains", totalChains)
            put("active_chains", activeCount)
            put("completed_chains", completedCount)
            put("success_rate", if (completedCount > 0) passedChains.toDouble() / completedCount else 0.0)
        }
    }
}

data class StepDefinition(
    val description: String,
    val action: String,
    val verificationRule: String = ""
)

data class StepResult(
    val success: Boolean,
    val output: String = "",
    val errorMessage: String = ""
)
