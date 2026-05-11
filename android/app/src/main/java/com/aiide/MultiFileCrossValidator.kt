package com.aiide

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class CrossValidationSeverity {
    CRITICAL, WARNING, INFO
}

enum class CrossValidationType {
    IMPORT_MISMATCH, SIGNATURE_MISMATCH, MISSING_DEPENDENCY, CIRCULAR_DEPENDENCY,
    TYPE_INCOMPATIBLE, ACCESS_VIOLATION, RESOURCE_MISSING, NAMESPACE_CONFLICT
}

data class CrossValidationIssue(
    val type: CrossValidationType,
    val severity: CrossValidationSeverity,
    val sourceFile: String,
    val targetFile: String?,
    val line: Int,
    val message: String,
    val suggestion: String,
    val autoFixable: Boolean,
    val autoFix: String? = null
) {
    fun toJson(): String = """{
        "type": "$type",
        "severity": "$severity",
        "source_file": "$sourceFile",
        "target_file": "${targetFile ?: ""}",
        "line": $line,
        "message": "${message.replace("\"", "\\\"")}",
        "suggestion": "${suggestion.replace("\"", "\\\"")}",
        "auto_fixable": $autoFixable
    }"""
}

data class CrossValidationResult(
    val batchId: String,
    val files: List<String>,
    val issues: List<CrossValidationIssue>,
    val passed: Boolean,
    val criticalCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val executionTimeMs: Long
)

class MultiFileCrossValidator(
    private val workspaceRoot: String = "/workspace"
) {
    companion object {
        private const val TAG = "MultiFileCrossValidator"
        private const val MAX_FILES_PER_BATCH = 100
        private const val MAX_ISSUES_PER_BATCH = 500
        private const val MAX_HISTORY = 500

        private val IMPORT_PATTERNS = listOf(
            Regex("""^\s*import\s+([^\s;]+)"""),
            Regex("""^\s*from\s+['\"]([^'\"]+)['\"]\s+import\s+(.+)"""),
            Regex("""^\s*require\s*\(\s*['\"]([^'\"]+)['\"]\s*\)"""),
            Regex("""^\s*package\s+([^\s;]+)""")
        )

        private val CLASS_DEFINITION_PATTERNS = listOf(
            Regex("""(?:public|private|protected)?\s*(?:abstract|sealed|data|open)?\s*class\s+(\w+)"""),
            Regex("""(?:public|private|protected)?\s*(?:abstract|open)?\s*interface\s+(\w+)"""),
            Regex("""class\s+(\w+)"""),
            Regex("""interface\s+(\w+)"""),
            Regex("""def\s+(\w+)"""),
            Regex("""function\s+(\w+)""")
        )
    }

    private val executor = Executors.newFixedThreadPool(4)
    @Volatile private var isShutdown = false
    private val validationCache = ConcurrentHashMap<String, CrossValidationResult>()
    private val validationHistory = CopyOnWriteArrayList<ValidationHistoryEntry>()
    private val validationCounter = AtomicLong(0)

    data class ValidationHistoryEntry(
        val batchId: String,
        val timestamp: Long,
        val fileCount: Int,
        val issueCount: Int,
        val passed: Boolean
    )

    fun validateBatch(files: Map<String, String>, batchId: String? = null): CrossValidationResult {
        if (isShutdown) throw IllegalStateException("Validator is shutdown")
        if (files.size > MAX_FILES_PER_BATCH) {
            throw IllegalArgumentException("Too many files: ${files.size} (max: $MAX_FILES_PER_BATCH)")
        }

        val actualBatchId = batchId ?: "batch_${validationCounter.incrementAndGet()}"
        val startTime = System.currentTimeMillis()
        val allIssues = mutableListOf<CrossValidationIssue>()

        try {
            allIssues.addAll(checkImports(files))
            allIssues.addAll(checkCircularDependencies(files))

            val criticalCount = allIssues.count { it.severity == CrossValidationSeverity.CRITICAL }
            val warningCount = allIssues.count { it.severity == CrossValidationSeverity.WARNING }
            val infoCount = allIssues.count { it.severity == CrossValidationSeverity.INFO }
            val executionTime = System.currentTimeMillis() - startTime

            val result = CrossValidationResult(
                batchId = actualBatchId,
                files = files.keys.toList(),
                issues = allIssues.take(MAX_ISSUES_PER_BATCH),
                passed = criticalCount == 0,
                criticalCount = criticalCount,
                warningCount = warningCount,
                infoCount = infoCount,
                executionTimeMs = executionTime
            )

            validationCache[actualBatchId] = result
            validationHistory.add(ValidationHistoryEntry(
                batchId = actualBatchId,
                timestamp = System.currentTimeMillis(),
                fileCount = files.size,
                issueCount = allIssues.size,
                passed = result.passed
            ))

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Validation failed for batch $actualBatchId", e)
            return CrossValidationResult(
                batchId = actualBatchId,
                files = files.keys.toList(),
                issues = listOf(CrossValidationIssue(
                    type = CrossValidationType.MISSING_DEPENDENCY,
                    severity = CrossValidationSeverity.CRITICAL,
                    sourceFile = "system",
                    targetFile = null,
                    line = 0,
                    message = "Validation error: ${e.message}",
                    suggestion = "Please check file contents",
                    autoFixable = false
                )),
                passed = false,
                criticalCount = 1,
                warningCount = 0,
                infoCount = 0,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun checkImports(files: Map<String, String>): List<CrossValidationIssue> {
        val issues = mutableListOf<CrossValidationIssue>()
        val definedClasses = mutableSetOf<String>()

        for ((_, content) in files) {
            for (pattern in CLASS_DEFINITION_PATTERNS) {
                pattern.findAll(content).forEach { match ->
                    definedClasses.add(match.groupValues[1])
                }
            }
        }

        return issues
    }

    private fun checkCircularDependencies(files: Map<String, String>): List<CrossValidationIssue> {
        return emptyList()
    }

    fun getCachedResult(batchId: String): CrossValidationResult? = validationCache[batchId]

    fun getValidationHistory(limit: Int = 50): List<ValidationHistoryEntry> {
        return validationHistory.takeLast(limit)
    }

    fun clearCache() {
        validationCache.clear()
    }

    fun shutdown() {
        if (!isShutdown) {
            isShutdown = true
            executor.shutdown()
        }
    }
}
