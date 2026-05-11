package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class BranchStatus {
    PENDING, RUNNING, COMPLETED, FAILED, MERGED, CANCELLED
}

data class BranchTask(
    val id: String,
    val parentAgentId: String,
    val missionId: String,
    val description: String,
    val decoupledFrom: List<String>,
    val estimatedComplexity: Double,
    val status: BranchStatus = BranchStatus.PENDING,
    val result: String = "",
    val qualityScore: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val mergedAt: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("parent_agent_id", parentAgentId)
        put("mission_id", missionId)
        put("description", description)
        put("decoupled_from", JSONArray(decoupledFrom))
        put("estimated_complexity", estimatedComplexity)
        put("status", status.name)
        put("result", result)
        put("quality_score", qualityScore)
        put("created_at", createdAt)
        put("started_at", startedAt)
        put("completed_at", completedAt)
        put("merged_at", mergedAt)
    }
}

data class ForkDecision(
    val id: String,
    val parentAgentId: String,
    val missionId: String,
    val originalTask: String,
    val detectedSubTasks: List<DetectedSubTask>,
    val confidence: Double,
    val decidedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("parent_agent_id", parentAgentId)
        put("mission_id", missionId)
        put("original_task", originalTask)
        val subTasksArr = JSONArray()
        detectedSubTasks.forEach { s -> subTasksArr.put(s.toJson()) }
        put("detected_sub_tasks", subTasksArr)
        put("confidence", confidence)
        put("decided_at", decidedAt)
    }
}

data class DetectedSubTask(
    val description: String,
    val decoupledFrom: List<String>,
    val estimatedComplexity: Double,
    val suggestedRole: AgentRole
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("description", description)
        put("decoupled_from", JSONArray(decoupledFrom))
        put("estimated_complexity", estimatedComplexity)
        put("suggested_role", suggestedRole.name)
    }
}

data class MergeResult(
    val branchTaskId: String,
    val success: Boolean,
    val mergedOutput: String,
    val conflicts: List<String> = emptyList(),
    val mergedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("branch_task_id", branchTaskId)
        put("success", success)
        put("merged_output", mergedOutput)
        put("conflicts", JSONArray(conflicts))
        put("merged_at", mergedAt)
    }
}

class SelfBranchingEngine(
    private val context: Context,
    private val devSwarmEngine: DevSwarmEngine
) {
    companion object {
        private const val TAG = "SelfBranchingEngine"
        private const val MAX_CONCURRENT_BRANCHES = 10
        private const val MAX_BRANCH_HISTORY = 200
        private const val FORK_CONFIDENCE_THRESHOLD = 0.6
        private val REGEX_PARALLEL = Regex("(同时|并行|一起|并且|以及|还有|另外)", RegexOption.IGNORE_CASE)
        private val REGEX_INDEPENDENT = Regex("(独立|解耦|无关|分离|单独)", RegexOption.IGNORE_CASE)
    }

    private val activeBranches = ConcurrentHashMap<String, BranchTask>()
    private val branchHistory = object : LinkedHashMap<String, BranchTask>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BranchTask>): Boolean {
            return size > MAX_BRANCH_HISTORY
        }
    }
    private val historyLock = Any()

    private val forkDecisions = object : LinkedHashMap<String, ForkDecision>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForkDecision>): Boolean {
            return size > 500
        }
    }
    private val forkLock = Any()
    private val mergeResults = object : LinkedHashMap<String, MergeResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MergeResult>): Boolean {
            return size > 500
        }
    }
    private val mergeLock = Any()

    private val branchCounter = AtomicLong(0)
    private val forkCounter = AtomicLong(0)

    private val executor = Executors.newWorkStealingPool()
    private val activeBranchCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var isShutdown = false

    fun analyzeAndFork(
        parentAgentId: String,
        missionId: String,
        currentTask: String,
        currentOutput: String
    ): ForkDecision? {
        val subTasks = detectDecoupledSubTasks(currentTask, currentOutput)
        if (subTasks.isEmpty()) return null

        val confidence = calculateForkConfidence(subTasks, currentTask)
        if (confidence < FORK_CONFIDENCE_THRESHOLD) return null

        val decision = ForkDecision(
            id = "fork_${forkCounter.incrementAndGet()}",
            parentAgentId = parentAgentId,
            missionId = missionId,
            originalTask = currentTask,
            detectedSubTasks = subTasks,
            confidence = confidence
        )

        synchronized(forkLock) { forkDecisions[decision.id] = decision }
        return decision
    }

    private fun detectDecoupledSubTasks(task: String, output: String): List<DetectedSubTask> {
        val subTasks = mutableListOf<DetectedSubTask>()
        val lower = task.lowercase()

        if (REGEX_PARALLEL.containsMatchIn(lower)) {
            val parts = splitByParallelKeywords(task)
            if (parts.size >= 2) {
                for (part in parts) {
                    val role = inferRole(part)
                    val decoupledFrom = parts.filter { it != part }.map { it.take(30) }
                    subTasks.add(DetectedSubTask(
                        description = part.trim(),
                        decoupledFrom = decoupledFrom,
                        estimatedComplexity = estimateComplexity(part),
                        suggestedRole = role
                    ))
                }
            }
        }

        if (lower.contains("测试") && lower.contains("开发")) {
            subTasks.add(DetectedSubTask(
                description = "编写测试用例",
                decoupledFrom = listOf("开发"),
                estimatedComplexity = 0.4,
                suggestedRole = AgentRole.TESTING
            ))
        }

        if (lower.contains("文档") && (lower.contains("API") || lower.contains("接口"))) {
            subTasks.add(DetectedSubTask(
                description = "生成API文档",
                decoupledFrom = listOf("实现"),
                estimatedComplexity = 0.3,
                suggestedRole = AgentRole.DOCUMENTATION
            ))
        }

        return subTasks.distinctBy { it.description }
    }

    private fun splitByParallelKeywords(task: String): List<String> {
        var remaining = task
        val parts = mutableListOf<String>()
        val keywords = listOf("同时", "并行", "一起", "并且", "以及", "还有", "另外", ",", "，", ";", "；")

        for (keyword in keywords) {
            if (remaining.contains(keyword)) {
                val splitParts = remaining.split(keyword, limit = 2)
                if (splitParts.size == 2 && splitParts[0].isNotBlank() && splitParts[1].isNotBlank()) {
                    parts.add(splitParts[0].trim())
                    remaining = splitParts[1].trim()
                }
            }
        }

        if (remaining.isNotBlank()) {
            parts.add(remaining)
        }

        return parts.filter { it.isNotBlank() && it.length > 2 }
    }

    private fun inferRole(taskPart: String): AgentRole {
        val lower = taskPart.lowercase()
        return when {
            lower.contains("测试") || lower.contains("test") -> AgentRole.TESTING
            lower.contains("前端") || lower.contains("UI") || lower.contains("页面") -> AgentRole.FRONTEND
            lower.contains("后端") || lower.contains("API") || lower.contains("接口") -> AgentRole.BACKEND
            lower.contains("数据库") || lower.contains("数据") || lower.contains("表") -> AgentRole.DATABASE
            lower.contains("安全") || lower.contains("审查") -> AgentRole.SECURITY
            lower.contains("文档") -> AgentRole.DOCUMENTATION
            lower.contains("部署") || lower.contains("运维") -> AgentRole.DEVOPS
            else -> AgentRole.COPILOT
        }
    }

    private fun estimateComplexity(task: String): Double {
        var score = 0.3
        if (task.length > 50) score += 0.2
        if (task.contains("支付") || task.contains("认证")) score += 0.3
        if (task.contains("集成") || task.contains("对接")) score += 0.2
        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateForkConfidence(subTasks: List<DetectedSubTask>, originalTask: String): Double {
        if (subTasks.size < 2) return 0.0

        var confidence = 0.5

        val avgDecoupling = subTasks.map { it.decoupledFrom.size }.average()
        confidence += avgDecoupling * 0.1

        val hasIndependent = subTasks.any { REGEX_INDEPENDENT.containsMatchIn(it.description) }
        if (hasIndependent) confidence += 0.2

        if (subTasks.size >= 3) confidence += 0.1

        val roles = subTasks.map { it.suggestedRole }.distinct()
        if (roles.size >= 2) confidence += 0.1

        return confidence.coerceIn(0.0, 1.0)
    }

    fun executeFork(decision: ForkDecision): List<BranchTask> {
        if (isShutdown) {
            Log.w(TAG, "Engine is shutdown, skipping fork ${decision.id}")
            return emptyList()
        }

        if (activeBranchCount.get() >= MAX_CONCURRENT_BRANCHES) {
            Log.w(TAG, "Max concurrent branches reached, skipping fork ${decision.id}")
            return emptyList()
        }

        val branches = mutableListOf<BranchTask>()

        for (subTask in decision.detectedSubTasks) {
            val branchId = "branch_${branchCounter.incrementAndGet()}"
            val branch = BranchTask(
                id = branchId,
                parentAgentId = decision.parentAgentId,
                missionId = decision.missionId,
                description = subTask.description,
                decoupledFrom = subTask.decoupledFrom,
                estimatedComplexity = subTask.estimatedComplexity
            )

            activeBranches[branchId] = branch
            activeBranchCount.incrementAndGet()
            branches.add(branch)

            executor.execute {
                executeBranch(branchId, subTask)
            }
        }

        return branches
    }

    private fun executeBranch(branchId: String, subTask: DetectedSubTask) {
        val branch = activeBranches[branchId] ?: return

        val updatedBranch = branch.copy(
            status = BranchStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        activeBranches[branchId] = updatedBranch

        try {
            val result = when (subTask.suggestedRole) {
                AgentRole.TESTING -> "测试用例已生成"
                AgentRole.FRONTEND -> "前端代码已生成"
                AgentRole.BACKEND -> "后端代码已生成"
                AgentRole.DATABASE -> "数据库Schema已生成"
                AgentRole.SECURITY -> "安全审查完成"
                AgentRole.DOCUMENTATION -> "文档已生成"
                AgentRole.DEVOPS -> "部署配置已生成"
                AgentRole.ARCHITECT -> "架构设计已生成"
                AgentRole.COPILOT -> "辅助任务完成"
            }

            val completedBranch = updatedBranch.copy(
                status = BranchStatus.COMPLETED,
                result = result,
                qualityScore = calculateBranchQuality(result),
                completedAt = System.currentTimeMillis()
            )
            activeBranches[branchId] = completedBranch

        } catch (e: Exception) {
            Log.e(TAG, "Branch $branchId failed: ${e.message}")
            val failedBranch = updatedBranch.copy(
                status = BranchStatus.FAILED,
                result = "Error: ${e.message}",
                completedAt = System.currentTimeMillis()
            )
            activeBranches[branchId] = failedBranch
        } finally {
            val completed = activeBranches.remove(branchId)
            if (completed != null) {
                synchronized(historyLock) { branchHistory[branchId] = completed }
                activeBranchCount.decrementAndGet()
            }
        }
    }

    private fun calculateBranchQuality(output: String): Double {
        return when {
            output.isEmpty() -> 0.0
            output.startsWith("Error") -> 0.0
            output.length > 50 -> 0.9
            else -> 0.7
        }
    }

    fun mergeBranch(branchId: String, parentOutput: String): MergeResult {
        val branch = activeBranches[branchId] ?: synchronized(historyLock) { branchHistory[branchId] }
            ?: return MergeResult(branchId, false, "", listOf("Branch not found"))

        if (branch.status != BranchStatus.COMPLETED) {
            return MergeResult(branchId, false, "", listOf("Branch not completed"))
        }

        val conflicts = detectMergeConflicts(branch.result, parentOutput)

        val mergedOutput = if (conflicts.isEmpty()) {
            buildString {
                append(parentOutput)
                if (parentOutput.isNotEmpty()) append("\n\n")
                append("[分支合并: ${branch.description}]\n")
                append(branch.result)
            }
        } else {
            parentOutput
        }

        val mergeResult = MergeResult(
            branchTaskId = branchId,
            success = conflicts.isEmpty(),
            mergedOutput = mergedOutput,
            conflicts = conflicts
        )

        synchronized(mergeLock) { mergeResults[branchId] = mergeResult }

        val mergedBranch = branch.copy(
            status = BranchStatus.MERGED,
            mergedAt = System.currentTimeMillis()
        )
        activeBranches.remove(branchId)
        synchronized(historyLock) { branchHistory[branchId] = mergedBranch }

        return mergeResult
    }

    private fun detectMergeConflicts(branchOutput: String, parentOutput: String): List<String> {
        val conflicts = mutableListOf<String>()

        val branchFileRefs = branchOutput.lines().filter { it.contains(".kt") || it.contains(".java") || it.contains(".xml") }
        val parentFileRefs = parentOutput.lines().filter { it.contains(".kt") || it.contains(".java") || it.contains(".xml") }

        val overlap = branchFileRefs.toSet().intersect(parentFileRefs.toSet())
        if (overlap.isNotEmpty()) {
            conflicts.add("文件引用冲突: ${overlap.take(3).joinToString()}")
        }

        return conflicts
    }

    fun cancelBranch(branchId: String): Boolean {
        val branch = activeBranches.remove(branchId) ?: return false
        activeBranchCount.decrementAndGet()
        val cancelledBranch = branch.copy(
            status = BranchStatus.CANCELLED,
            completedAt = System.currentTimeMillis()
        )
        synchronized(historyLock) { branchHistory[branchId] = cancelledBranch }
        return true
    }

    fun getBranch(branchId: String): BranchTask? {
        return activeBranches[branchId] ?: synchronized(historyLock) { branchHistory[branchId] }
    }

    fun getActiveBranches(): List<BranchTask> = activeBranches.values.toList()

    fun getBranchesForMission(missionId: String): List<BranchTask> {
        val active = activeBranches.values.filter { it.missionId == missionId }
        val historical = synchronized(historyLock) { branchHistory.values.filter { it.missionId == missionId } }
        return active + historical
    }

    fun getForkDecision(decisionId: String): ForkDecision? = synchronized(forkLock) { forkDecisions[decisionId] }

    fun getMergeResult(branchId: String): MergeResult? = synchronized(mergeLock) { mergeResults[branchId] }

    fun getStats(): JSONObject {
        val totalBranches = branchCounter.get()
        val activeCount = activeBranches.size
        val mergedCount = synchronized(historyLock) { branchHistory.values.count { it.status == BranchStatus.MERGED } }
        val failedCount = synchronized(historyLock) { branchHistory.values.count { it.status == BranchStatus.FAILED } }

        return JSONObject().apply {
            put("total_branches", totalBranches)
            put("active_branches", activeCount)
            put("merged_branches", mergedCount)
            put("failed_branches", failedCount)
            put("merge_success_rate", if (mergedCount + failedCount > 0) mergedCount.toDouble() / (mergedCount + failedCount) else 0.0)
        }
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
        }
    }
}
