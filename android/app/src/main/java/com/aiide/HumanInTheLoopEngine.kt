package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class InterventionPhase { THINKING, RESPONDING, ACTING, CHECKING }
enum class InterventionStatus { PENDING, APPROVED, REJECTED, MODIFIED }

data class InterventionPoint(
    val id: String, val cycleId: String, val phase: InterventionPhase, val description: String,
    val createdAt: Long = System.currentTimeMillis(), val status: InterventionStatus = InterventionStatus.PENDING,
    val userMessage: String? = null, val autoProceedAt: Long? = null, val riskLevel: RiskLevel = RiskLevel.MEDIUM
) { fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("cycle_id", cycleId); put("phase", phase.name); put("description", description); put("created_at", createdAt); put("status", status.name); userMessage?.let { put("user_message", it) }; autoProceedAt?.let { put("auto_proceed_at", it) }; put("risk_level", riskLevel.name) } }

data class InterventionConfig(
    val enabledPhases: Set<InterventionPhase> = InterventionPhase.values().toSet(),
    val defaultTimeout: Long = 30000L, val autoApproveLowRisk: Boolean = true,
    val requireApprovalFor: Set<String> = setOf("SCHEMA_CHANGE", "FILE_DELETE", "PRODUCTION_CONFIG")
) { fun toJson(): JSONObject = JSONObject().apply { put("enabled_phases", JSONArray(enabledPhases.map { it.name })); put("default_timeout", defaultTimeout); put("auto_approve_low_risk", autoApproveLowRisk); put("require_approval_for", JSONArray(requireApprovalFor)) } }

data class AgentStatus(val cycleId: String, val currentPhase: InterventionPhase, val currentTask: String, val completedSteps: List<String> = emptyList(), val nextPlannedStep: String = "", val isPaused: Boolean = false)

class HumanInTheLoopEngine(private val context: Context) {
    companion object { private const val TAG = "HumanInTheLoop"; private const val HITL_DIR = "hitl_interventions"; private const val CONFIG_PREFS = "hitl_config"; private const val KEY_CONFIG = "intervention_config" }

    private val hitlDir = File(context.filesDir, HITL_DIR)
    private val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val interventionCache = ConcurrentHashMap<String, InterventionPoint>()
    private val cycleStatusCache = ConcurrentHashMap<String, AgentStatus>()
    private val cycleInterventions = ConcurrentHashMap<String, CopyOnWriteArrayList<InterventionPoint>>()
    private val pendingTimeouts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val recordCounter = AtomicLong(0)
    private val scheduler = Executors.newScheduledThreadPool(1)
    private val executor = Executors.newSingleThreadExecutor()
    private var config = loadConfig()
    @Volatile private var isShutdown = false

    init { if (!hitlDir.exists()) hitlDir.mkdirs(); loadPersistedInterventions() }

    fun createInterventionPoint(cycleId: String, phase: InterventionPhase, description: String, riskLevel: RiskLevel = RiskLevel.MEDIUM): InterventionPoint {
        if (phase !in config.enabledPhases) return createAutoApprovedPoint(cycleId, phase, description, riskLevel)
        val id = "intv_${recordCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}"
        val autoProceedAt = if (config.autoApproveLowRisk && riskLevel == RiskLevel.LOW) System.currentTimeMillis() + config.defaultTimeout else null
        val point = InterventionPoint(id = id, cycleId = cycleId, phase = phase, description = description, status = InterventionStatus.PENDING, autoProceedAt = autoProceedAt, riskLevel = riskLevel)
        interventionCache[id] = point; cycleInterventions.computeIfAbsent(cycleId) { CopyOnWriteArrayList() }.add(point)
        if (autoProceedAt != null) scheduleAutoProceed(id, config.defaultTimeout); persistIntervention(point); return point
    }

    fun approveIntervention(pointId: String, userModification: String?): InterventionPoint? { val existing = interventionCache[pointId] ?: return null; val status = if (userModification != null) InterventionStatus.MODIFIED else InterventionStatus.APPROVED; val updated = existing.copy(status = status, userMessage = userModification); interventionCache[pointId] = updated; cancelAutoProceed(pointId); persistIntervention(updated); return updated }
    fun rejectIntervention(pointId: String, reason: String): InterventionPoint? { val existing = interventionCache[pointId] ?: return null; val updated = existing.copy(status = InterventionStatus.REJECTED, userMessage = reason); interventionCache[pointId] = updated; cancelAutoProceed(pointId); persistIntervention(updated); return updated }
    fun getAgentStatus(cycleId: String): AgentStatus = cycleStatusCache[cycleId] ?: AgentStatus(cycleId = cycleId, currentPhase = InterventionPhase.THINKING, currentTask = "")
    fun pauseAgent(cycleId: String): Boolean { val status = cycleStatusCache[cycleId] ?: return false; cycleStatusCache[cycleId] = status.copy(isPaused = true); return true }
    fun resumeAgent(cycleId: String): Boolean { val status = cycleStatusCache[cycleId] ?: return false; cycleStatusCache[cycleId] = status.copy(isPaused = false); return true }
    fun injectOpinion(cycleId: String, opinion: String): Boolean { val status = cycleStatusCache[cycleId] ?: return false; createInterventionPoint(cycleId = cycleId, phase = status.currentPhase, description = "User injected opinion: $opinion", riskLevel = RiskLevel.LOW); return true }
    fun getInterventionHistory(cycleId: String): List<InterventionPoint> = cycleInterventions[cycleId]?.toList()?.sortedBy { it.createdAt } ?: emptyList()
    fun configureIntervention(newConfig: InterventionConfig) { config = newConfig; persistConfig(newConfig) }
    fun getConfig(): InterventionConfig = config
    fun updateAgentStatus(cycleId: String, phase: InterventionPhase, currentTask: String, completedSteps: List<String>, nextPlannedStep: String) { val existing = cycleStatusCache[cycleId]; val updated = AgentStatus(cycleId = cycleId, currentPhase = phase, currentTask = currentTask, completedSteps = completedSteps, nextPlannedStep = nextPlannedStep, isPaused = existing?.isPaused ?: false); cycleStatusCache[cycleId] = updated }

    private fun createAutoApprovedPoint(cycleId: String, phase: InterventionPhase, description: String, riskLevel: RiskLevel): InterventionPoint { val id = "intv_${recordCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}"; val point = InterventionPoint(id = id, cycleId = cycleId, phase = phase, description = description, status = InterventionStatus.APPROVED, riskLevel = riskLevel); interventionCache[id] = point; cycleInterventions.computeIfAbsent(cycleId) { CopyOnWriteArrayList() }.add(point); persistIntervention(point); return point }
    private fun scheduleAutoProceed(pointId: String, delayMs: Long) { if (isShutdown) return; try { val future = scheduler.schedule({ val point = interventionCache[pointId]; if (point != null && point.status == InterventionStatus.PENDING) approveIntervention(pointId, null) }, delayMs, TimeUnit.MILLISECONDS); pendingTimeouts[pointId] = future } catch (e: Exception) { Log.e(TAG, "Failed to schedule auto-proceed", e) } }
    private fun cancelAutoProceed(pointId: String) { val future = pendingTimeouts.remove(pointId); future?.cancel(false) }
    private fun persistIntervention(point: InterventionPoint) { executor.execute { try { File(hitlDir, "${point.id}.json").writeText(point.toJson().toString(2)) } catch (e: Exception) { Log.e(TAG, "Failed to persist", e) } } }
    private fun persistConfig(configToSave: InterventionConfig) { try { prefs.edit().putString(KEY_CONFIG, configToSave.toJson().toString()).apply() } catch (e: Exception) { Log.e(TAG, "Failed to persist config", e) } }
    private fun loadConfig(): InterventionConfig { try { val jsonStr = prefs.getString(KEY_CONFIG, null); if (jsonStr != null) return InterventionConfig(JSONObject(jsonStr).getJSONArray("enabled_phases").let { arr -> (0 until arr.length()).map { InterventionPhase.valueOf(arr.getString(it)) }.toSet() }, JSONObject(jsonStr).optLong("default_timeout", 30000L), JSONObject(jsonStr).optBoolean("auto_approve_low_risk", true), JSONObject(jsonStr).optJSONArray("require_approval_for")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: setOf()) } } catch (e: Exception) { Log.e(TAG, "Failed to load config", e) }; return InterventionConfig() }
    private fun loadPersistedInterventions() { hitlDir.listFiles()?.forEach { file -> if (file.name.endsWith(".json")) { try { val json = JSONObject(file.readText()); val point = InterventionPoint(json.optString("id", ""), json.optString("cycle_id", ""), InterventionPhase.valueOf(json.optString("phase", "THINKING")), json.optString("description", ""), json.optLong("created_at", System.currentTimeMillis()), InterventionStatus.valueOf(json.optString("status", "PENDING")), json.optString("user_message").ifEmpty { null }, if (json.has("auto_proceed_at")) json.optLong("auto_proceed_at") else null, RiskLevel.valueOf(json.optString("risk_level", "MEDIUM"))); interventionCache[point.id] = point; cycleInterventions.computeIfAbsent(point.cycleId) { CopyOnWriteArrayList() }.add(point) } catch (e: Exception) { Log.e(TAG, "Failed to load: ${file.name}", e) } } } }

    fun shutdown() { isShutdown = true; pendingTimeouts.values.forEach { it.cancel(false) }; pendingTimeouts.clear(); scheduler.shutdown(); executor.shutdown(); try { if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) scheduler.shutdownNow(); if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow() } catch (e: InterruptedException) { scheduler.shutdownNow(); executor.shutdownNow(); Thread.currentThread().interrupt() } }
}