package com.aiide

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

enum class CapsulePhase {
    IDLE, THINKING, RESPONDING, ACTING, CHECKING, DELIVERING, WAITING_FOR_INPUT, BRANCHING, MERGING, ERROR
}

data class AgentStatus(
    val phase: CapsulePhase,
    val message: String = "",
    val progress: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("phase", phase.name)
        put("message", message)
        put("progress", progress)
        put("timestamp", timestamp)
        put("metadata", JSONObject(metadata))
    }
}

class AgentStatusCapsuleEngine(
    private val context: Context,
    private val cortexFlowEngine: CortexFlowEngine,
    private val devSwarmEngine: DevSwarmEngine,
    private val selfBranchingEngine: SelfBranchingEngine
) {
    private val statusHistory = ConcurrentHashMap<String, List<AgentStatus>>()
    private val currentStatuses = ConcurrentHashMap<String, AgentStatus>()
    private val phaseListeners = ConcurrentHashMap<String, (AgentStatus) -> Unit>()
    private val statusQueue = ConcurrentHashMap<String, MutableList<AgentStatus>>()

    fun registerAgent(agentId: String, initialPhase: CapsulePhase = CapsulePhase.IDLE) {
        val status = AgentStatus(initialPhase)
        currentStatuses[agentId] = status
        statusHistory[agentId] = listOf(status)
        statusQueue[agentId] = mutableListOf()
        
        Log.i("AgentStatusCapsule", "Registered agent: $agentId")
    }

    fun unregisterAgent(agentId: String) {
        currentStatuses.remove(agentId)
        statusHistory.remove(agentId)
        statusQueue.remove(agentId)
        phaseListeners.remove(agentId)
        
        Log.i("AgentStatusCapsule", "Unregistered agent: $agentId")
    }

    fun updateStatus(agentId: String, phase: CapsulePhase, message: String = "", metadata: Map<String, Any> = emptyMap()) {
        val status = AgentStatus(phase, message, calculateProgress(phase), metadata = metadata)
        
        currentStatuses[agentId] = status
        
        val history = statusHistory[agentId]?.toMutableList() ?: mutableListOf()
        history.add(status)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }
        statusHistory[agentId] = history
        
        phaseListeners[agentId]?.invoke(status)
        
        persistStatus(agentId, status)
    }

    private fun calculateProgress(phase: CapsulePhase): Double {
        return when (phase) {
            CapsulePhase.IDLE -> 0.0
            CapsulePhase.THINKING -> 0.2
            CapsulePhase.RESPONDING -> 0.4
            CapsulePhase.ACTING -> 0.6
            CapsulePhase.CHECKING -> 0.7
            CapsulePhase.DELIVERING -> 0.9
            CapsulePhase.WAITING_FOR_INPUT -> 0.5
            CapsulePhase.BRANCHING -> 0.6
            CapsulePhase.MERGING -> 0.8
            CapsulePhase.ERROR -> 0.0
        }
    }

    fun getStatus(agentId: String): AgentStatus? {
        return currentStatuses[agentId]
    }

    fun getHistory(agentId: String, limit: Int = MAX_HISTORY_SIZE): List<AgentStatus> {
        return statusHistory[agentId]?.takeLast(limit) ?: emptyList()
    }

    fun getAllStatuses(): Map<String, AgentStatus> {
        return currentStatuses.toMap()
    }

    fun subscribeToPhase(agentId: String, listener: (AgentStatus) -> Unit) {
        phaseListeners[agentId] = listener
    }

    fun syncFromCortexFlow(agentId: String, cortexState: Map<String, Any>) {
        val phase = when (cortexState["state"] as? String) {
            "analyzing" -> CapsulePhase.THINKING
            "generating" -> CapsulePhase.RESPONDING
            "executing" -> CapsulePhase.ACTING
            "validating" -> CapsulePhase.CHECKING
            else -> CapsulePhase.IDLE
        }
        
        updateStatus(
            agentId,
            phase,
            cortexState["message"] as? String ?: "",
            mapOf("source" to "CortexFlow", "cortexState" to cortexState)
        )
    }

    fun syncFromDevSwarm(agentId: String, swarmState: Map<String, Any>) {
        val agents = swarmState["activeAgents"] as? Int ?: 0
        val phase = when {
            agents == 0 -> CapsulePhase.IDLE
            agents > 3 -> CapsulePhase.ACTING
            else -> CapsulePhase.RESPONDING
        }
        
        updateStatus(
            agentId,
            phase,
            "DevSwarm: $agents active agents",
            mapOf("source" to "DevSwarm", "swarmState" to swarmState)
        )
    }

    fun syncFromSelfBranching(agentId: String, branchState: Map<String, Any>) {
        val branches = branchState["activeBranches"] as? Int ?: 0
        val phase = when (branchState["status"] as? String) {
            "branching" -> CapsulePhase.BRANCHING
            "merging" -> CapsulePhase.MERGING
            else -> if (branches > 0) CapsulePhase.ACTING else CapsulePhase.IDLE
        }
        
        updateStatus(
            agentId,
            phase,
            "SelfBranching: $branches branches",
            mapOf("source" to "SelfBranching", "branchState" to branchState)
        )
    }

    private fun persistStatus(agentId: String, status: AgentStatus) {
        try {
            val prefs = context.getSharedPreferences("agent_status_cache", Context.MODE_PRIVATE)
            val json = prefs.getString(agentId, null)?.let { JSONObject(it) } ?: JSONObject()
            json.put("lastStatus", status.toJSON())
            prefs.edit().putString(agentId, json.toString()).apply()
        } catch (e: Exception) {
            Log.e("AgentStatusCapsule", "Failed to persist status: ${e.message}")
        }
    }

    fun queueStatusUpdate(agentId: String, phase: CapsulePhase, message: String = "") {
        val status = AgentStatus(phase, message)
        statusQueue[agentId]?.add(status)
    }

    fun flushQueue(agentId: String) {
        val queue = statusQueue[agentId] ?: return
        queue.forEach { status ->
            currentStatuses[agentId] = status
            val history = statusHistory[agentId]?.toMutableList() ?: mutableListOf()
            history.add(status)
            statusHistory[agentId] = history
        }
        queue.clear()
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "registeredAgents" to currentStatuses.size,
            "totalHistoryEntries" to statusHistory.values.sumOf { it.size },
            "activePhaseCounts" to currentStatuses.values.groupBy { it.phase }.mapValues { it.value.size },
            "queuedUpdates" to statusQueue.values.sumOf { it.size }
        )
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 100
    }
}
