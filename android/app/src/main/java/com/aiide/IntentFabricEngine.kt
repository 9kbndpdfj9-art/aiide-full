package com.aiide

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

enum class IntentType {
    TASK, QUERY, CREATION, MODIFICATION, ANALYSIS, ORCHESTRATION
}

enum class IntentStatus {
    DRAFT, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED
}

data class Constraint(
    val type: String,
    val value: Any,
    val description: String = ""
)

data class IntentNode(
    val id: String,
    val name: String,
    val type: IntentType,
    val description: String,
    val status: IntentStatus,
    val constraints: List<Constraint>,
    val createdAt: Long,
    val updatedAt: Long,
    val metadata: MutableMap<String, Any> = ConcurrentHashMap(),
    val participants: MutableList<String> = mutableListOf()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("description", description)
        put("status", status.name)
        put("constraints", JSONArray(constraints.map { c ->
            JSONObject().apply {
                put("type", c.type)
                put("value", c.value.toString())
                put("description", c.description)
            }
        }))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("metadata", JSONObject(metadata))
        put("participants", JSONArray(participants))
    }
}

data class Contract(
    val intentId: String,
    val parties: List<String>,
    val terms: Map<String, Any>,
    val signatures: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)

data class MultiPartySynthesis(
    val synthesisId: String,
    val intentId: String,
    val parties: List<String>,
    val contributions: Map<String, String>,
    val resolved: Boolean = false,
    val result: String? = null
)

class IntentFabricEngine(
    private val context: Context,
    private val modelRouter: ModelRouter? = null
) {
    private val intents = ConcurrentHashMap<String, IntentNode>()
    private val contracts = ConcurrentHashMap<String, Contract>()
    private val syntheses = ConcurrentHashMap<String, MultiPartySynthesis>()
    private val intentListeners = ConcurrentHashMap<String, (IntentNode) -> Unit>()
    private val auditLog = ConcurrentHashMap<String, MutableList<AuditEntry>>()

    init {
        loadIntents()
    }

    private fun loadIntents() {
        try {
            val prefs = context.getSharedPreferences("intent_fabric", Context.MODE_PRIVATE)
            prefs.getString("intents", null)?.let {
                val json = JSONArray(it)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val intent = IntentNode(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = IntentType.valueOf(obj.getString("type")),
                        description = obj.getString("description"),
                        status = IntentStatus.valueOf(obj.getString("status")),
                        constraints = (0 until obj.getJSONArray("constraints").length()).map { j ->
                            val c = obj.getJSONArray("constraints").getJSONObject(j)
                            Constraint(c.getString("type"), c.get("value"), c.optString("description", ""))
                        },
                        createdAt = obj.getLong("createdAt"),
                        updatedAt = obj.getLong("updatedAt")
                    )
                    intents[intent.id] = intent
                }
            }
        } catch (e: Exception) {
            Log.e("IntentFabric", "Failed to load intents: ${e.message}")
        }
    }

    private fun saveIntents() {
        try {
            val prefs = context.getSharedPreferences("intent_fabric", Context.MODE_PRIVATE)
            val json = JSONArray()
            intents.values.forEach { intent ->
                json.put(intent.toJSON())
            }
            prefs.edit().putString("intents", json.toString()).apply()
        } catch (e: Exception) {
            Log.e("IntentFabric", "Failed to save intents: ${e.message}")
        }
    }

    fun createIntent(
        name: String,
        type: IntentType,
        description: String,
        constraints: List<Constraint> = emptyList(),
        metadata: Map<String, Any> = emptyMap()
    ): IntentNode {
        val id = "intent_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        
        val intent = IntentNode(
            id = id,
            name = name,
            type = type,
            description = description,
            status = IntentStatus.DRAFT,
            constraints = constraints,
            createdAt = now,
            updatedAt = now,
            metadata = ConcurrentHashMap(metadata)
        )
        
        intents[id] = intent
        auditLog[id] = mutableListOf(AuditEntry(now, "CREATED", "System", "Intent created"))
        saveIntents()
        
        intentListeners[id]?.invoke(intent)
        
        Log.i("IntentFabric", "Created intent: $id")
        return intent
    }

    fun activateIntent(intentId: String): Boolean {
        val intent = intents[intentId] ?: return false
        
        if (!validateConstraints(intent)) {
            Log.w("IntentFabric", "Intent $intentId failed constraint validation")
            return false
        }
        
        intents[intentId] = intent.copy(status = IntentStatus.ACTIVE, updatedAt = System.currentTimeMillis())
        addAuditEntry(intentId, "ACTIVATED", "System", "Intent activated")
        saveIntents()
        
        intentListeners[intentId]?.invoke(intents[intentId]!!)
        return true
    }

    fun completeIntent(intentId: String): Boolean {
        val intent = intents[intentId] ?: return false
        
        intents[intentId] = intent.copy(status = IntentStatus.COMPLETED, updatedAt = System.currentTimeMillis())
        addAuditEntry(intentId, "COMPLETED", "System", "Intent completed")
        saveIntents()
        
        intentListeners[intentId]?.invoke(intents[intentId]!!)
        return true
    }

    fun failIntent(intentId: String, reason: String): Boolean {
        val intent = intents[intentId] ?: return false
        
        intents[intentId] = intent.copy(
            status = IntentStatus.FAILED,
            updatedAt = System.currentTimeMillis()
        ).also { it.metadata["failureReason"] = reason }
        
        addAuditEntry(intentId, "FAILED", "System", reason)
        saveIntents()
        
        intentListeners[intentId]?.invoke(intents[intentId]!!)
        return true
    }

    private fun validateConstraints(intent: IntentNode): Boolean {
        return intent.constraints.all { constraint ->
            when (constraint.type) {
                "require_model" -> modelRouter?.isModelAvailable(constraint.value.toString()) == true
                "max_tokens" -> (constraint.value as? Number)?.toInt()?.let { it <= 8192 } ?: true
                "min_confidence" -> (constraint.value as? Number)?.toDouble()?.let { it >= 0.5 } ?: true
                "deadline" -> (constraint.value as? Number)?.toLong()?.let { it > System.currentTimeMillis() } ?: true
                else -> true
            }
        }
    }

    fun addParticipant(intentId: String, participantId: String) {
        intents[intentId]?.let { intent ->
            if (!intent.participants.contains(participantId)) {
                val updated = intent.copy(
                    participants = (intent.participants + participantId).toMutableList(),
                    updatedAt = System.currentTimeMillis()
                )
                intents[intentId] = updated
                addAuditEntry(intentId, "PARTICIPANT_ADDED", participantId, "Participant added")
                saveIntents()
            }
        }
    }

    fun removeParticipant(intentId: String, participantId: String) {
        intents[intentId]?.let { intent ->
            val updated = intent.copy(
                participants = intent.participants.filter { it != participantId }.toMutableList(),
                updatedAt = System.currentTimeMillis()
            )
            intents[intentId] = updated
            addAuditEntry(intentId, "PARTICIPANT_REMOVED", participantId, "Participant removed")
            saveIntents()
        }
    }

    fun createContract(
        intentId: String,
        parties: List<String>,
        terms: Map<String, Any>
    ): Contract? {
        if (!intents.containsKey(intentId)) return null
        
        val contract = Contract(
            intentId = intentId,
            parties = parties,
            terms = terms,
            signatures = emptyMap()
        )
        
        contracts[contract.intentId] = contract
        addAuditEntry(intentId, "CONTRACT_CREATED", "System", "Contract created with ${parties.size} parties")
        
        return contract
    }

    fun signContract(intentId: String, partyId: String, signature: String): Boolean {
        val contract = contracts[intentId] ?: return false
        
        if (!contract.parties.contains(partyId)) return false
        
        contracts[intentId] = contract.copy(
            signatures = contract.signatures + (partyId to signature)
        )
        
        addAuditEntry(intentId, "CONTRACT_SIGNED", partyId, "Contract signed")
        return true
    }

    fun startMultiPartySynthesis(intentId: String): String? {
        val intent = intents[intentId] ?: return null
        
        val synthesisId = "synthesis_${UUID.randomUUID()}"
        val synthesis = MultiPartySynthesis(
            synthesisId = synthesisId,
            intentId = intentId,
            parties = intent.participants,
            contributions = emptyMap()
        )
        
        syntheses[synthesisId] = synthesis
        addAuditEntry(intentId, "SYNTHESIS_STARTED", "System", "Multi-party synthesis started")
        
        return synthesisId
    }

    fun addSynthesisContribution(
        synthesisId: String,
        partyId: String,
        contribution: String
    ): Boolean {
        val synthesis = syntheses[synthesisId] ?: return false
        
        if (!synthesis.parties.contains(partyId)) return false
        
        syntheses[synthesisId] = synthesis.copy(
            contributions = synthesis.contributions + (partyId to contribution)
        )
        
        addAuditEntry(
            synthesis.intentId,
            "CONTRIBUTION_ADDED",
            partyId,
            "Contribution added (${contribution.length} chars)"
        )
        
        checkSynthesisCompletion(synthesisId)
        
        return true
    }

    private fun checkSynthesisCompletion(synthesisId: String) {
        val synthesis = syntheses[synthesisId] ?: return
        
        if (synthesis.contributions.size >= synthesis.parties.size) {
            val result = synthesizeContributions(synthesis)
            syntheses[synthesisId] = synthesis.copy(resolved = true, result = result)
            addAuditEntry(
                synthesis.intentId,
                "SYNTHESIS_COMPLETED",
                "System",
                "Multi-party synthesis completed"
            )
        }
    }

    private fun synthesizeContributions(synthesis: MultiPartySynthesis): String {
        val contributions = synthesis.contributions.values.joinToString("\n\n---\n\n")
        return "Synthesized from ${synthesis.parties.size} parties:\n\n$contributions"
    }

    fun subscribeToIntent(intentId: String, listener: (IntentNode) -> Unit) {
        intentListeners[intentId] = listener
    }

    fun getIntent(intentId: String): IntentNode? = intents[intentId]

    fun getAllIntents(status: IntentStatus? = null): List<IntentNode> {
        return if (status != null) {
            intents.values.filter { it.status == status }
        } else {
            intents.values.toList()
        }
    }

    fun getAuditLog(intentId: String): List<AuditEntry> {
        return auditLog[intentId] ?: emptyList()
    }

    private fun addAuditEntry(intentId: String, action: String, actor: String, details: String) {
        val entry = AuditEntry(System.currentTimeMillis(), action, actor, details)
        auditLog.getOrPut(intentId) { mutableListOf() }.add(entry)
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalIntents" to intents.size,
            "activeIntents" to intents.values.count { it.status == IntentStatus.ACTIVE },
            "completedIntents" to intents.values.count { it.status == IntentStatus.COMPLETED },
            "totalContracts" to contracts.size,
            "signedContracts" to contracts.values.count { it.signatures.size == it.parties.size },
            "pendingSyntheses" to syntheses.values.count { !it.resolved }
        )
    }
}

data class AuditEntry(
    val timestamp: Long,
    val action: String,
    val actor: String,
    val details: String
)
