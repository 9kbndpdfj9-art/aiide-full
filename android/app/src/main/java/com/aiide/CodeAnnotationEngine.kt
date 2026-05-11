package com.aiide

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class AnnotationColor { GREEN, YELLOW, RED, BLUE, GRAY }
enum class AnnotationSource { SHADOW_CONSISTENCY, CODE_HEALTH, TEST_COVERAGE, SECURITY, AGENT_ACTIVITY, MANUAL }

data class LineAnnotation(
    val filePath: String, val startLine: Int, val endLine: Int, val color: AnnotationColor,
    val source: AnnotationSource, val message: String, val detail: String = "", val priority: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) { fun toJson(): JSONObject = JSONObject().apply {
    put("file_path", filePath); put("start_line", startLine); put("end_line", endLine)
    put("color", color.name.lowercase()); put("source", source.name)
    put("message", message); put("detail", detail); put("priority", priority); put("updated_at", updatedAt)
}}

data class FileAnnotationSummary(
    val filePath: String, val totalLines: Int, val greenLines: Int, val yellowLines: Int, val redLines: Int,
    val blueLines: Int, val grayLines: Int, val overallHealth: Double,
    val topAnnotations: List<LineAnnotation> = emptyList(), val updatedAt: Long = System.currentTimeMillis()
) { fun toJson(): JSONObject = JSONObject().apply {
    put("file_path", filePath); put("total_lines", totalLines); put("green_lines", greenLines)
    put("yellow_lines", yellowLines); put("red_lines", redLines); put("blue_lines", blueLines)
    put("gray_lines", grayLines); put("overall_health", overallHealth)
    put("top_annotations", JSONArray(topAnnotations.map { it.toJson() })); put("updated_at", updatedAt)
}}

class CodeAnnotationEngine(
    private val context: Context, private val semanticShadowEngine: SemanticShadowEngine,
    private val codeHealthAnalyzer: CodeHealthAnalyzer
) {
    companion object { private const val TAG = "CodeAnnotationEngine"; private const val MAX_ANNOTATIONS_PER_FILE = 100; private const val CACHE_TTL_MS = 60000 }
    private val annotationCache = ConcurrentHashMap<String, List<LineAnnotation>>()
    private val summaryCache = ConcurrentHashMap<String, FileAnnotationSummary>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()
    private val updateCounter = AtomicLong(0)

    fun getAnnotationsForFile(filePath: String): List<LineAnnotation> {
        val cached = getValidCache(filePath); if (cached != null) return cached
        val annotations = mutableListOf<LineAnnotation>()
        annotations.addAll(getShadowAnnotations(filePath)); annotations.addAll(getHealthAnnotations(filePath))
        val deduplicated = deduplicateAnnotations(annotations).sortedByDescending { it.priority }.take(MAX_ANNOTATIONS_PER_FILE)
        annotationCache[filePath] = deduplicated; cacheTimestamps[filePath] = System.currentTimeMillis(); return deduplicated
    }

    fun getFileSummary(filePath: String): FileAnnotationSummary {
        val cachedSummary = summaryCache[filePath]; val cacheTime = cacheTimestamps[filePath] ?: 0
        if (cachedSummary != null && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) return cachedSummary
        val annotations = getAnnotationsForFile(filePath)
        val lineColorCount = mutableMapOf<AnnotationColor, Int>(); for (color in AnnotationColor.values()) lineColorCount[color] = 0
        val coveredLines = mutableSetOf<Int>()
        for (annotation in annotations) { for (line in annotation.startLine..annotation.endLine) { if (line !in coveredLines) { lineColorCount[annotation.color] = (lineColorCount[annotation.color] ?: 0) + 1; coveredLines.add(line) } } }
        val totalAnnotated = coveredLines.size
        val healthScore = if (totalAnnotated > 0) { val greenRatio = (lineColorCount[AnnotationColor.GREEN] ?: 0).toDouble() / totalAnnotated; val redPenalty = (lineColorCount[AnnotationColor.RED] ?: 0).toDouble() / totalAnnotated * 0.5; (greenRatio - redPenalty).coerceIn(0.0, 1.0) } else 1.0
        val summary = FileAnnotationSummary(filePath, totalAnnotated, lineColorCount[AnnotationColor.GREEN] ?: 0, lineColorCount[AnnotationColor.YELLOW] ?: 0, lineColorCount[AnnotationColor.RED] ?: 0, lineColorCount[AnnotationColor.BLUE] ?: 0, lineColorCount[AnnotationColor.GRAY] ?: 0, healthScore, annotations.take(10))
        summaryCache[filePath] = summary; return summary
    }

    private fun getShadowAnnotations(filePath: String): List<LineAnnotation> {
        val annotations = mutableListOf<LineAnnotation>(); try { val status = semanticShadowEngine.getFileStatus(filePath); val health = status.overallHealth; val isDrift = status.statusColor == ShadowStatusColor.RED || status.statusColor == ShadowStatusColor.YELLOW; annotations.add(LineAnnotation(filePath, 1, 1, when { isDrift && status.statusColor == ShadowStatusColor.RED -> AnnotationColor.RED; health < 0.7 -> AnnotationColor.YELLOW; else -> AnnotationColor.GREEN }, AnnotationSource.SHADOW_CONSISTENCY, when { isDrift && status.statusColor == ShadowStatusColor.RED -> "语义漂移已检测"; health < 0.7 -> "影子一致性偏低"; else -> "影子一致性正常" }, "健康度: ${String.format("%.2f", health)}, 告警数: ${status.alertCount}", if (isDrift && status.statusColor == ShadowStatusColor.RED) 10 else 3)) } catch (e: Exception) { Log.w(TAG, "Failed: ${e.message}") }; return annotations
    }

    private fun getHealthAnnotations(filePath: String): List<LineAnnotation> {
        val annotations = mutableListOf<LineAnnotation>(); try { val file = File(getProjectDir(), filePath); if (file.exists() && file.length() < 1048576) { val content = file.readText(); val health = codeHealthAnalyzer.analyze(filePath, content); val score = health.overallScore / 100.0; for (issue in health.issues.take(20)) { annotations.add(LineAnnotation(filePath, issue.line, issue.line, when (issue.severity) { "critical", "error" -> AnnotationColor.RED; "warning" -> AnnotationColor.YELLOW; "info" -> AnnotationColor.BLUE; else -> AnnotationColor.GRAY }, AnnotationSource.CODE_HEALTH, issue.message.take(100), issue.suggestion, when (issue.severity) { "critical" -> 10; "error" -> 8; "warning" -> 5; else -> 2 })) }; if (score < 0.5) annotations.add(LineAnnotation(filePath, 1, 1, AnnotationColor.RED, AnnotationSource.CODE_HEALTH, "代码健康度低", "综合评分: ${String.format("%.2f", score)}", 7)) } } catch (e: Exception) { Log.w(TAG, "Failed: ${e.message}") }; return annotations
    }

    private fun getProjectDir(): File { val dir = File(context.getExternalFilesDir(null), "AIIDEProjects"); if (!dir.exists()) dir.mkdirs(); return dir }
    private fun deduplicateAnnotations(annotations: List<LineAnnotation>): List<LineAnnotation> { val merged = mutableListOf<LineAnnotation>(); val byLine = annotations.groupBy { it.startLine to it.endLine }; for ((_, group) in byLine) { if (group.size == 1) merged.add(group[0]) else { val hp = group.maxByOrNull { it.priority } ?: group[0]; merged.add(hp.copy(message = group.map { "[${it.source.name}] ${it.message}" }.joinToString("; ").take(200))) } }; return merged }
    private fun getValidCache(filePath: String): List<LineAnnotation>? { val ct = cacheTimestamps[filePath] ?: return null; return if (System.currentTimeMillis() - ct > CACHE_TTL_MS) null else annotationCache[filePath] }
    fun addManualAnnotation(filePath: String, startLine: Int, endLine: Int, color: AnnotationColor, message: String): LineAnnotation { val annotation = LineAnnotation(filePath, startLine, endLine, color, AnnotationSource.MANUAL, message, priority = 6); val current = annotationCache[filePath] ?: emptyList(); annotationCache[filePath] = (current + annotation).take(MAX_ANNOTATIONS_PER_FILE); cacheTimestamps[filePath] = System.currentTimeMillis(); summaryCache.remove(filePath); updateCounter.incrementAndGet(); return annotation }
    fun removeManualAnnotation(filePath: String, startLine: Int, endLine: Int): Boolean { val current = annotationCache[filePath] ?: return false; val filtered = current.filterNot { it.source == AnnotationSource.MANUAL && it.startLine == startLine && it.endLine == endLine }; if (filtered.size == current.size) return false; annotationCache[filePath] = filtered; cacheTimestamps[filePath] = System.currentTimeMillis(); summaryCache.remove(filePath); return true }
    fun invalidateCache(filePath: String? = null) { if (filePath != null) { annotationCache.remove(filePath); summaryCache.remove(filePath); cacheTimestamps.remove(filePath) } else { annotationCache.clear(); summaryCache.clear(); cacheTimestamps.clear() } }
    fun getBatchSummaries(filePaths: List<String>): List<FileAnnotationSummary> = filePaths.map { getFileSummary(it) }
    fun getStats(): JSONObject = JSONObject().apply { put("cached_files", annotationCache.size); put("total_updates", updateCounter.get()) }
}