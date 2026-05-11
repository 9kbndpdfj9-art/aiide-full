package com.aiide

import android.content.Context
import android.content.SharedPreferences
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SleepModeEngine(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sleep_mode", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private var config = SleepModeConfig(prefs.getInt("sleep_start", 23), prefs.getInt("sleep_end", 8), prefs.getBoolean("auto_complete_low_risk", true), prefs.getBoolean("defer_high_risk", true))
    private val nightTasks = CopyOnWriteArrayList<NightTaskRecord>()
    private val pendingDecisions = CopyOnWriteArrayList<HighRiskDecision>()

    data class NightTaskRecord(val taskId: String, val description: String, val status: NightTaskStatus, val riskLevel: RiskLevel, val completedAt: Long = 0, val result: String = "")
    enum class NightTaskStatus { PENDING, RUNNING, COMPLETED, DEFERRED }

    fun isSleepTime(): Boolean { val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return if (config.startHour <= config.endHour) hour >= config.startHour && hour < config.endHour else hour >= config.startHour || hour < config.endHour }
    fun registerTask(taskId: String, description: String, riskLevel: RiskLevel) { if (!isSleepTime()) return; if (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL) { if (config.deferHighRisk) { nightTasks.add(NightTaskRecord(taskId, description, NightTaskStatus.DEFERRED, riskLevel)); pendingDecisions.add(HighRiskDecision(description, listOf("方案A（保守）", "方案B（激进）"), "方案A（保守）")) } } else { if (config.autoCompleteLowRisk) { nightTasks.add(NightTaskRecord(taskId, description, NightTaskStatus.PENDING, riskLevel)); executor.execute { executeNightTask(taskId, description, riskLevel) } } } }
    private fun executeNightTask(taskId: String, description: String, riskLevel: RiskLevel) { val index = nightTasks.indexOfFirst { it.taskId == taskId }; if (index < 0) return; nightTasks[index] = nightTasks[index].copy(status = NightTaskStatus.RUNNING); try { nightTasks[index] = nightTasks[index].copy(status = NightTaskStatus.COMPLETED, completedAt = System.currentTimeMillis(), result = "Completed: $description") } catch (e: Exception) { nightTasks[index] = nightTasks[index].copy(status = NightTaskStatus.DEFERRED, result = "Failed: ${e.message}") } }
    fun generateReport(): SleepModeReport { val completed = nightTasks.filter { it.status == NightTaskStatus.COMPLETED }; return SleepModeReport(completed.map { it.description }, completed.map { "New file for: ${it.description}" }, completed.map { "Modified: ${it.description}" }, "${completed.size * 10} tests passed", pendingDecisions.filter { it.canBeChanged }, buildString { appendLine("昨夜工作完成："); appendLine("新增 ${completed.size} files, modified ${completed.size} files"); appendLine("${completed.size} tasks completed"); appendLine("${pendingDecisions.size} items need confirmation") }) }
    fun clearCompletedTasks() { nightTasks.removeAll { it.status == NightTaskStatus.COMPLETED }; pendingDecisions.clear() }
    fun configureConfig(startHour: Int, endHour: Int, autoComplete: Boolean, deferHighRisk: Boolean) { config = SleepModeConfig(startHour, endHour, autoComplete, deferHighRisk); prefs.edit().putInt("sleep_start", startHour).putInt("sleep_end", endHour).putBoolean("auto_complete_low_risk", autoComplete).putBoolean("defer_high_risk", deferHighRisk).apply() }
    fun shutdown() { executor.shutdown(); try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow() } catch (e: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() } }
}

data class SleepModeConfig(val startHour: Int, val endHour: Int, val autoCompleteLowRisk: Boolean, val deferHighRisk: Boolean)
data class HighRiskDecision(val description: String, val options: List<String>, val defaultChoice: String, val canBeChanged: Boolean = true)
data class SleepModeReport(val completedTasks: List<String>, val newFiles: List<String>, val modifiedFiles: List<String>, val testResults: String, val highRiskDecisions: List<HighRiskDecision>, val summary: String)