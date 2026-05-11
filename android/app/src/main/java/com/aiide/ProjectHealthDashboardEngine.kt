package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*

enum class Trend { UP, DOWN, STABLE }
enum class HealthCategory { CODE_QUALITY, TEST_COVERAGE, SHADOW_CONSISTENCY, TECH_DEBT, SECURITY, AGENT_ACTIVITY }

data class HealthMetric(val name: String, val value: Double, val unit: String, val trend: Trend = Trend.STABLE, val trendValue: Double = 0.0, val lastUpdated: Long = System.currentTimeMillis()) { fun toJson(): JSONObject = JSONObject().apply { put("name", name); put("value", value); put("unit", unit); put("trend", trend.name); put("trend_value", trendValue); put("last_updated", lastUpdated) } }
data class DashboardReport(val categories: Map<HealthCategory, List<HealthMetric>>, val overallScore: Double, val generatedAt: Long = System.currentTimeMillis(), val previousScore: Double? = null) { fun toJson(): JSONObject = JSONObject().apply { categories.forEach { (cat, metrics) -> put(cat.name, JSONArray(metrics.map { it.toJson() })) }; put("overall_score", overallScore); put("generated_at", generatedAt); previousScore?.let { put("previous_score", it) } } }

class ProjectHealthDashboardEngine(private val context: Context, private val semanticShadowEngine: SemanticShadowEngine, private val codeHealthAnalyzer: CodeHealthAnalyzer, private val smartFixEngine: SmartFixEngine, private val decisionTimelineEngine: AgentDecisionTimelineEngine) {
    companion object { private const val TAG = "HealthDashboard"; private const val DASHBOARD_DIR = "health_dashboard"; private const val MAX_HISTORY_DAYS = 90 }
    private val dashboardDir = File(context.filesDir, DASHBOARD_DIR)
    private val metricHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<Long, Double>>>()
    private val cachedReport = ConcurrentHashMap<String, DashboardReport>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    init { if (!dashboardDir.exists()) dashboardDir.mkdirs() }

    fun generateReport(): DashboardReport {
        val categories = mutableMapOf<HealthCategory, List<HealthMetric>>()
        categories[HealthCategory.CODE_QUALITY] = getCodeQualityMetrics()
        categories[HealthCategory.TEST_COVERAGE] = getTestCoverageMetrics()
        categories[HealthCategory.SHADOW_CONSISTENCY] = getShadowConsistencyMetrics()
        categories[HealthCategory.TECH_DEBT] = getTechDebtMetrics()
        categories[HealthCategory.SECURITY] = getSecurityMetrics()
        categories[HealthCategory.AGENT_ACTIVITY] = getAgentActivityMetrics()
        val overallScore = computeOverallScore(categories)
        val previousScore = cachedReport["latest"]?.overallScore
        val report = DashboardReport(categories, overallScore, previousScore = previousScore)
        cachedReport["latest"] = report; persistReport(report); return report
    }

    fun getCodeQualityMetrics(): List<HealthMetric> { val statuses = semanticShadowEngine.getAllFileStatuses(); val avgHealth = if (statuses.isNotEmpty()) statuses.map { it.overallHealth }.average() * 100 else 100.0; return listOf(HealthMetric("avg_file_health", avgHealth, "%", computeTrend("avg_file_health", avgHealth)), HealthMetric("analyzed_file_count", statuses.size.toDouble(), "files", computeTrend("analyzed_file_count", statuses.size.toDouble()))) }
    fun getTestCoverageMetrics(): List<HealthMetric> { val statuses = semanticShadowEngine.getAllFileStatuses(); val testCount = statuses.count { it.filePath.lowercase().contains("test") }; val srcCount = (statuses.size - testCount).coerceAtLeast(0); val ratio = if (srcCount > 0) (testCount.toDouble() / srcCount) * 100 else 0.0; return listOf(HealthMetric("test_file_ratio", ratio, "%", computeTrend("test_file_ratio", ratio))) }
    fun getShadowConsistencyMetrics(): List<HealthMetric> { val statuses = semanticShadowEngine.getAllFileStatuses(); val total = statuses.size.coerceAtLeast(1); val green = statuses.count { it.statusColor == ShadowStatusColor.GREEN }; val red = statuses.count { it.statusColor == ShadowStatusColor.RED }; return listOf(HealthMetric("shadow_green_ratio", (green.toDouble() / total) * 100, "%", computeTrend("shadow_green_ratio", green.toDouble() / total * 100)), HealthMetric("shadow_red_ratio", (red.toDouble() / total) * 100, "%", computeTrend("shadow_red_ratio", red.toDouble() / total * 100))) }
    fun getTechDebtMetrics(): List<HealthMetric> { val patterns = smartFixEngine.getTopFixPatterns(); return listOf(HealthMetric("fix_pattern_count", patterns.size.toDouble(), "patterns", computeTrend("fix_pattern_count", patterns.size.toDouble())), HealthMetric("avg_fix_success_rate", if (patterns.isNotEmpty()) patterns.map { it.successRate }.average() * 100 else 0.0, "%", Trend.STABLE)) }
    fun getSecurityMetrics(): List<HealthMetric> { val alerts = semanticShadowEngine.getAlerts(); val critical = alerts.count { it.severity == SeverityLevel.CRITICAL && !it.resolved }; return listOf(HealthMetric("critical_unresolved", critical.toDouble(), "alerts", computeTrend("critical_unresolved", critical.toDouble()))) }
    fun getAgentActivityMetrics(): List<HealthMetric> { val decisions = decisionTimelineEngine.getRecentDecisions(100); val failureRate = if (decisions.isNotEmpty()) decisions.count { it.action == DecisionAction.FAILED }.toDouble() / decisions.size * 100 else 0.0; return listOf(HealthMetric("total_decisions", decisions.size.toDouble(), "decisions", computeTrend("total_decisions", decisions.size.toDouble())), HealthMetric("decision_failure_rate", failureRate, "%", computeTrend("decision_failure_rate", failureRate))) }
    fun getMetricHistory(metricName: String, days: Int): List<Pair<Long, Double>> { val cutoff = System.currentTimeMillis() - (days.toLong() * 86400000); return metricHistory[metricName]?.filter { it.first >= cutoff } ?: emptyList() }
    fun refreshAll(): DashboardReport { metricHistory.clear(); return generateReport() }

    private fun computeOverallScore(categories: Map<HealthCategory, List<HealthMetric>>): Double { val weights = mapOf(HealthCategory.CODE_QUALITY to 0.25, HealthCategory.TEST_COVERAGE to 0.15, HealthCategory.SHADOW_CONSISTENCY to 0.20, HealthCategory.TECH_DEBT to 0.15, HealthCategory.SECURITY to 0.15, HealthCategory.AGENT_ACTIVITY to 0.10); var score = 0.0; var totalWeight = 0.0; categories.forEach { (cat, metrics) -> val w = weights[cat] ?: 0.1; score += computeCategoryScore(cat, metrics) * w; totalWeight += w }; return (score / totalWeight).coerceIn(0.0, 100.0) }
    private fun computeCategoryScore(category: HealthCategory, metrics: List<HealthMetric>): Double = when (category) { HealthCategory.CODE_QUALITY -> metrics.find { it.name == "avg_file_health" }?.value ?: 100.0; HealthCategory.TEST_COVERAGE -> (metrics.find { it.name == "test_file_ratio" }?.value ?: 0.0).coerceIn(0.0, 100.0); HealthCategory.SHADOW_CONSISTENCY -> 100.0; HealthCategory.TECH_DEBT -> metrics.find { it.name == "avg_fix_success_rate" }?.value ?: 0.0; HealthCategory.SECURITY -> (100.0 - (metrics.find { it.name == "critical_unresolved" }?.value ?: 0.0) * 20).coerceIn(0.0, 100.0); HealthCategory.AGENT_ACTIVITY -> (100.0 - (metrics.find { it.name == "decision_failure_rate" }?.value ?: 0.0) * 2).coerceIn(0.0, 100.0) }
    private fun computeTrend(metricName: String, currentValue: Double): Trend { val history = metricHistory[metricName] ?: return Trend.STABLE; if (history.size < 2) return Trend.STABLE; val prev = history.last().second; val diff = currentValue - prev; val threshold = prev * 0.05; return when { diff > threshold && threshold > 0 -> Trend.UP; diff < -threshold && threshold > 0 -> Trend.DOWN; else -> Trend.STABLE } }
    private fun persistReport(report: DashboardReport) { executor.execute { try { File(dashboardDir, "latest_report.json").writeText(report.toJson().toString(2)) } catch (e: Exception) { Log.e(TAG, "Failed to persist", e) } } }
    fun shutdown() { executor.shutdown(); try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow() } catch (e: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() } }
}