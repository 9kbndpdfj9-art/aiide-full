package com.aiide

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class QualityReport(
    val batchId: String,
    val fileCount: Int,
    val overallScore: Double,
    val dimensionScores: Map<String, Double>,
    val issues: List<QualityIssue>,
    val passed: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("batchId", batchId)
        put("fileCount", fileCount)
        put("overallScore", overallScore)
        put("dimensionScores", JSONObject(dimensionScores))
        put("issues", JSONArray(issues.map { it.toJSON() }))
        put("passed", passed)
        put("timestamp", timestamp)
    }
}

data class QualityIssue(
    val severity: IssueSeverity,
    val dimension: String,
    val message: String,
    val filePath: String,
    val line: Int = 0
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("severity", severity.name)
        put("dimension", dimension)
        put("message", message)
        put("filePath", filePath)
        put("line", line)
    }
}

data class DimensionScore(
    val dimension: String,
    val score: Double,
    val weight: Double,
    val details: Map<String, Any> = emptyMap()
)

class CodeQualityGate(
    private val minScore: Double = 0.6,
    private val maxCriticalIssues: Int = 0,
    private val zeroBugEngine: ZeroBugGuaranteeEngine? = null
) {
    private val dimensionWeights = mapOf(
        "correctness" to 0.35,
        "maintainability" to 0.25,
        "performance" to 0.15,
        "security" to 0.15,
        "testability" to 0.10
    )
    
    private val reports = ConcurrentHashMap<String, QualityReport>()

    fun evaluateBatch(
        files: Map<String, String>,
        batchId: String? = null
    ): QualityReport {
        val id = batchId ?: "batch_${System.currentTimeMillis()}"
        
        val dimensionScores = mutableMapOf<String, Double>()
        val allIssues = mutableListOf<QualityIssue>()
        
        dimensionWeights.keys.forEach { dimension ->
            val (score, issues) = evaluateDimension(dimension, files)
            dimensionScores[dimension] = score
            allIssues.addAll(issues)
        }
        
        val overallScore = calculateOverallScore(dimensionScores)
        val criticalIssues = allIssues.count { it.severity == IssueSeverity.CRITICAL }
        
        val passed = overallScore >= minScore && criticalIssues <= maxCriticalIssues
        
        val report = QualityReport(
            batchId = id,
            fileCount = files.size,
            overallScore = overallScore,
            dimensionScores = dimensionScores,
            issues = allIssues,
            passed = passed
        )
        
        reports[id] = report
        
        return report
    }

    private fun evaluateDimension(
        dimension: String,
        files: Map<String, String>
    ): Pair<Double, List<QualityIssue>> {
        val issues = mutableListOf<QualityIssue>()
        
        when (dimension) {
            "correctness" -> {
                files.forEach { (path, content) ->
                    issues.addAll(checkCorrectness(path, content))
                }
            }
            "maintainability" -> {
                files.forEach { (path, content) ->
                    issues.addAll(checkMaintainability(path, content))
                }
            }
            "performance" -> {
                files.forEach { (path, content) ->
                    issues.addAll(checkPerformance(path, content))
                }
            }
            "security" -> {
                files.forEach { (path, content) ->
                    issues.addAll(checkSecurity(path, content))
                }
            }
            "testability" -> {
                files.forEach { (path, content) ->
                    issues.addAll(checkTestability(path, content))
                }
            }
        }
        
        val severityWeights = mapOf(
            IssueSeverity.CRITICAL to 0.4,
            IssueSeverity.HIGH to 0.25,
            IssueSeverity.MEDIUM to 0.15,
            IssueSeverity.LOW to 0.05
        )
        
        val totalPenalty = issues.sumOf { issue ->
            severityWeights[issue.severity] ?: 0.1
        }
        
        val score = (1.0 - (totalPenalty / files.size).coerceIn(0.0, 1.0))
        
        return Pair(score, issues)
    }

    private fun checkCorrectness(path: String, content: String): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        if (openBraces != closeBraces) {
            issues.add(QualityIssue(
                IssueSeverity.CRITICAL, "correctness",
                "Mismatched braces in $path",
                path
            ))
        }
        
        val openParens = content.count { it == '(' }
        val closeParens = content.count { it == ')' }
        if (openParens != closeParens) {
            issues.add(QualityIssue(
                IssueSeverity.CRITICAL, "correctness",
                "Mismatched parentheses in $path",
                path
            ))
        }
        
        if (content.contains("null") && content.contains(".")) {
            val nullChecks = Regex("\\.\\w+\\(").findAll(content).count()
            val safeCalls = Regex("\\?\\.").findAll(content).count()
            if (nullChecks > safeCalls * 2) {
                issues.add(QualityIssue(
                    IssueSeverity.MEDIUM, "correctness",
                    "Potential NPE risk - consider using safe calls",
                    path
                ))
            }
        }
        
        if (content.contains("as!") || content.contains("as \")) {
            issues.add(QualityIssue(
                IssueSeverity.HIGH, "correctness",
                "Unsafe cast detected",
                path
            ))
        }
        
        zeroBugEngine?.let { engine ->
            val bugReport = engine.analyze(content, path)
            bugReport.bugs.forEach { bug ->
                issues.add(QualityIssue(
                    IssueSeverity.HIGH, "correctness",
                    "Bug detected: ${bug.description}",
                    path, bug.line
                ))
            }
        }
        
        return issues
    }

    private fun checkMaintainability(path: String, content: String): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        
        val lines = content.split("\n")
        if (lines.size > 300) {
            issues.add(QualityIssue(
                IssueSeverity.MEDIUM, "maintainability",
                "File has ${lines.size} lines - consider splitting",
                path
            ))
        }
        
        val functionMatches = Regex("fun\\s+\\w+").findAll(content)
        if (functionMatches.count() > 15) {
            issues.add(QualityIssue(
                IssueSeverity.MEDIUM, "maintainability",
                "File has ${functionMatches.count()} functions - consider refactoring",
                path
            ))
        }
        
        val cyclomaticComplexity = calculateCyclomaticComplexity(content)
        if (cyclomaticComplexity > 10) {
            issues.add(QualityIssue(
                IssueSeverity.HIGH, "maintainability",
                "High cyclomatic complexity ($cyclomaticComplexity)",
                path
            ))
        }
        
        if (content.matches(Regex(".{200,}"))) {
            val longLines = lines.filter { it.length > 120 }
            if (longLines.size > lines.size * 0.2) {
                issues.add(QualityIssue(
                    IssueSeverity.LOW, "maintainability",
                    "${longLines.size} lines exceed 120 characters",
                    path
                ))
            }
        }
        
        return issues
    }

    private fun calculateCyclomaticComplexity(content: String): Int {
        var complexity = 1
        complexity += Regex("\\bif\\b").findAll(content).count()
        complexity += Regex("\\bwhen\\b").findAll(content).count()
        complexity += Regex("\\bfor\\b").findAll(content).count()
        complexity += Regex("\\bwhile\\b").findAll(content).count()
        complexity += Regex("\\b&&\\b").findAll(content).count()
        complexity += Regex("\\b\\|\\|\\b").findAll(content).count()
        complexity += Regex("\\bcatch\\b").findAll(content).count()
        return complexity
    }

    private fun checkPerformance(path: String, content: String): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        
        if (content.contains("StringBuilder") && content.contains("+ \"")) {
            issues.add(QualityIssue(
                IssueSeverity.LOW, "performance",
                "Mixed StringBuilder and string concatenation",
                path
            ))
        }
        
        if (content.contains("ArrayList") && !content.contains("List<") && !content.contains("MutableList")) {
            issues.add(QualityIssue(
                IssueSeverity.LOW, "performance",
                "Consider using List interface for better flexibility",
                path
            ))
        }
        
        if (content.contains("notifyDataSetChanged") && !content.contains("DiffUtil")) {
            issues.add(QualityIssue(
                IssueSeverity.MEDIUM, "performance",
                "Consider using DiffUtil for better RecyclerView performance",
                path
            ))
        }
        
        return issues
    }

    private fun checkSecurity(path: String, content: String): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        
        if (content.contains(Regex("password\\s*=\\s*\"[^"]+\"")) ||
            content.contains(Regex("apiKey\\s*=\\s*\"[^"]+\""))) {
            issues.add(QualityIssue(
                IssueSeverity.CRITICAL, "security",
                "Hardcoded credentials detected",
                path
            ))
        }
        
        if (content.contains("eval(") || content.contains("Runtime.getRuntime().exec")) {
            issues.add(QualityIssue(
                IssueSeverity.HIGH, "security",
                "Potentially dangerous code execution",
                path
            ))
        }
        
        if (content.contains("Log.") && !content.contains("BuildConfig.DEBUG") && content.matches(Regex(".{0,20}Log\\..{0,20}"))) {
            issues.add(QualityIssue(
                IssueSeverity.LOW, "security",
                "Logging in production code - ensure sensitive data is not logged",
                path
            ))
        }
        
        return issues
    }

    private fun checkTestability(path: String, content: String): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        
        if (content.contains("object ") && !content.contains("Test") && !content.contains("test")) {
            issues.add(QualityIssue(
                IssueSeverity.MEDIUM, "testability",
                "Singleton detected - may impact testability",
                path
            ))
        }
        
        val directContextUsage = Regex("context\\.").findAll(content).count()
        val functionCount = Regex("fun ").findAll(content).count()
        if (directContextUsage > functionCount * 2) {
            issues.add(QualityIssue(
                IssueSeverity.MEDIUM, "testability",
                "Heavy context dependency may hinder testing",
                path
            ))
        }
        
        return issues
    }

    private fun calculateOverallScore(dimensionScores: Map<String, Double>): Double {
        return dimensionScores.entries.sumOf { (dimension, score) ->
            val weight = dimensionWeights[dimension] ?: 0.0
            score * weight
        }
    }

    fun getReport(batchId: String): QualityReport? = reports[batchId]

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalReports" to reports.size,
            "avgOverallScore" to reports.values.map { it.overallScore }.average().let {
                if (it.isNaN()) 0.0 else it
            },
            "passRate" to reports.values.count { it.passed }.toDouble() / reports.size.coerceAtLeast(1)
        )
    }
}
