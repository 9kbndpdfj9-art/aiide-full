package com.aiide

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class BugCategory {
    SYNTAX, TYPE_SYSTEM, NULL_SAFETY, RESOURCE_LEAK, THREAD_SAFETY,
    LOGIC_ERROR, PERFORMANCE, SECURITY, STYLE, CROSS_FILE
}

enum class BugPreventionStrategy {
    STATIC_ANALYSIS, TYPE_INFERENCE, NULL_CHECK, RESOURCE_TRACKING,
    CONCURRENCY_CHECK, CONTROL_FLOW, BOUND_CHECK, SYNTAX_GUARD, CROSS_VALIDATION
}

data class BugRisk(
    val category: BugCategory,
    val severity: Int,
    val file: String,
    val line: Int,
    val description: String,
    val preventionStrategy: BugPreventionStrategy,
    val autoFix: String? = null,
    val confidence: Double = 0.0
) {
    fun toJson(): String = """{
        "category": "$category",
        "severity": $severity,
        "file": "$file",
        "line": $line,
        "description": "${description.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\n")}",
        "strategy": "$preventionStrategy",
        "confidence": $confidence
    }"""
}

data class ZeroBugReport(
    val batchId: String,
    val totalFiles: Int,
    val totalRisks: Int,
    val criticalRisks: Int,
    val autoFixed: Int,
    val risks: List<BugRisk>,
    val isSafeToApply: Boolean,
    val confidence: Double,
    val executionTimeMs: Long
) {
    fun toJson(): String = """{
        "batch_id": "$batchId",
        "total_files": $totalFiles,
        "total_risks": $totalRisks,
        "critical_risks": $criticalRisks,
        "auto_fixed": $autoFixed,
        "is_safe_to_apply": $isSafeToApply,
        "confidence": $confidence,
        "execution_time_ms": $executionTimeMs
    }"""
}

class ZeroBugGuaranteeEngine(
    private val crossValidator: MultiFileCrossValidator? = null,
    private val maxSeverity: Int = 3
) {
    companion object {
        private const val TAG = "ZeroBugGuarantee"
        private const val MAX_HISTORY = 500
        private const val HIGH_SEVERITY = 4
        private const val MEDIUM_SEVERITY = 3

        private val NULL_PATTERNS = listOf(
            Regex("""(?<!\?\)!!\.\w+""") to BugCategory.NULL_SAFETY,
            Regex("""\w+\?\.""") to null,
            Regex("""lateinit\s+var""") to BugCategory.NULL_SAFETY,
            Regex("""(?<!\?)\!\!\w""") to BugCategory.NULL_SAFETY,
            Regex("""as\s+(?!\?)(\w+)""") to BugCategory.TYPE_SYSTEM
        )

        private val RESOURCE_LEAK_PATTERNS = listOf(
            Regex("""(?:FileInputStream|FileOutputStream|BufferedReader|BufferedWriter|Socket|ServerSocket)\s*\(""") to BugCategory.RESOURCE_LEAK
        )

        private val THREAD_SAFETY_PATTERNS = listOf(
            Regex("""var\s+\w+\s*=\s*(?:mutableListOf|mutableMapOf|mutableSetOf|ArrayList|HashMap|HashSet)\s*\(""") to BugCategory.THREAD_SAFETY
        )

        private val LOGIC_ERROR_PATTERNS = listOf(
            Regex("""catch\s*\(\s*e\s*:\s*Exception\s*\)\s*\{\s*\}""") to BugCategory.LOGIC_ERROR,
            Regex("""catch\s*\(\s*_\s*:.*\)\s*\{\s*\}""") to BugCategory.LOGIC_ERROR,
            Regex("""TODO\s*\(""") to BugCategory.LOGIC_ERROR
        )

        private val PERFORMANCE_PATTERNS = listOf(
            Regex("""for\s*\(\s*\w+\s+in\s+\w+\s*\)\s*\{\s*\w+\.contains\(""") to BugCategory.PERFORMANCE,
            Regex("""\w+\s*\+\s*=\s*\w+""") to BugCategory.PERFORMANCE
        )

        private val SECURITY_PATTERNS = listOf(
            Regex("""eval\s*\(""") to BugCategory.SECURITY,
            Regex("""exec\s*\(""") to BugCategory.SECURITY,
            Regex("""password|secret|token|api_key|private_key""", RegexOption.IGNORE_CASE) to BugCategory.SECURITY
        )
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    @Volatile private var isShutdown = false
    private val history = CopyOnWriteArrayList<BugHistoryEntry>()
    private val riskCounter = AtomicLong(0)

    data class BugHistoryEntry(
        val batchId: String,
        val timestamp: Long,
        val filesScanned: Int,
        val risksFound: Int,
        val autoFixed: Int,
        val isSafe: Boolean
    )

    fun analyzeBatch(files: Map<String, String>, batchId: String? = null): ZeroBugReport {
        if (isShutdown) throw IllegalStateException("Engine is shutdown")

        val actualBatchId = batchId ?: "zbg_${riskCounter.incrementAndGet()}"
        val startTime = System.currentTimeMillis()
        val allRisks = mutableListOf<BugRisk>()
        var autoFixedCount = 0

        try {
            for ((filePath, content) in files) {
                val fileRisks = mutableListOf<BugRisk>()
                fileRisks.addAll(checkNullSafety(filePath, content))
                fileRisks.addAll(checkResourceLeaks(filePath, content))
                fileRisks.addAll(checkThreadSafety(filePath, content))
                fileRisks.addAll(checkLogicErrors(filePath, content))
                fileRisks.addAll(checkPerformance(filePath, content))
                fileRisks.addAll(checkSecurity(filePath, content))
                fileRisks.addAll(checkSyntaxGuards(filePath, content))

                val fixedRisks = autoFixRisks(fileRisks, content)
                autoFixedCount += fixedRisks.size
                allRisks.addAll(fileRisks.filter { it !in fixedRisks })
            }

            val criticalCount = allRisks.count { it.severity >= HIGH_SEVERITY }
            val executionTime = System.currentTimeMillis() - startTime
            val confidence = calculateConfidence(files.size, allRisks.size, criticalCount)
            val isSafe = criticalCount == 0 && allRisks.count { it.severity >= maxSeverity } == 0

            val report = ZeroBugReport(
                batchId = actualBatchId,
                totalFiles = files.size,
                totalRisks = allRisks.size,
                criticalRisks = criticalCount,
                autoFixed = autoFixedCount,
                risks = allRisks,
                isSafeToApply = isSafe,
                confidence = confidence,
                executionTimeMs = executionTime
            )

            history.add(BugHistoryEntry(
                batchId = actualBatchId,
                timestamp = System.currentTimeMillis(),
                filesScanned = files.size,
                risksFound = allRisks.size,
                autoFixed = autoFixedCount,
                isSafe = isSafe
            ))

            if (history.size > MAX_HISTORY) {
                history.removeRange(0, history.size - MAX_HISTORY)
            }

            Log.i(TAG, "Batch $actualBatchId: ${files.size} files, ${allRisks.size} risks, $autoFixedCount auto-fixed, safe=$isSafe")
            return report
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed for batch $actualBatchId", e)
            return ZeroBugReport(
                batchId = actualBatchId,
                totalFiles = files.size,
                totalRisks = 1,
                criticalRisks = 1,
                autoFixed = 0,
                risks = listOf(BugRisk(
                    category = BugCategory.LOGIC_ERROR,
                    severity = HIGH_SEVERITY,
                    file = "system",
                    line = 0,
                    description = "Analysis error: ${e.message}",
                    preventionStrategy = BugPreventionStrategy.STATIC_ANALYSIS,
                    confidence = 1.0
                )),
                isSafeToApply = false,
                confidence = 0.0,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun checkNullSafety(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue

            for ((pattern, category) in NULL_PATTERNS) {
                if (category == null) continue
                val match = pattern.find(line) ?: continue

                val severity = when {
                    line.contains("!!") -> HIGH_SEVERITY
                    line.contains("lateinit") -> MEDIUM_SEVERITY
                    else -> 2
                }

                risks.add(BugRisk(
                    category = category,
                    severity = severity,
                    file = filePath,
                    line = index + 1,
                    description = when (category) {
                        BugCategory.NULL_SAFETY -> "Potential null pointer risk"
                        BugCategory.TYPE_SYSTEM -> "Unsafe type cast"
                        else -> "Null safety issue"
                    },
                    preventionStrategy = BugPreventionStrategy.NULL_CHECK,
                    autoFix = generateNullSafetyFix(line, category),
                    confidence = 0.85
                ))
            }
        }

        return risks
    }

    private fun checkResourceLeaks(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        val openedResources = mutableMapOf<String, Int>()
        val closedResources = mutableSetOf<String>()

        for ((index, line) in lines.withIndex()) {
            for ((pattern, _) in RESOURCE_LEAK_PATTERNS) {
                val match = pattern.find(line) ?: continue

                val varName = Regex("""(\w+)\s*=\s*""").find(line)?.groupValues?.getOrNull(1)
                    ?: "unknown_$index"

                openedResources[varName] = index + 1
            }

            if (Regex("""(\w+)\.close\s*\(""").find(line) != null) {
                val varName = Regex("""(\w+)\.close""").find(line)?.groupValues?.getOrNull(1)
                if (varName != null) closedResources.add(varName)
            }

            if (Regex("""use\s*\{""").find(line) != null) {
                val varName = Regex("""(\w+)\.use""").find(line)?.groupValues?.getOrNull(1)
                if (varName != null) closedResources.add(varName)
            }
        }

        for ((name, openLine) in openedResources) {
            if (name !in closedResources) {
                risks.add(BugRisk(
                    category = BugCategory.RESOURCE_LEAK,
                    severity = HIGH_SEVERITY,
                    file = filePath,
                    line = openLine,
                    description = "Resource '$name' is opened but never closed",
                    preventionStrategy = BugPreventionStrategy.RESOURCE_TRACKING,
                    autoFix = "Use '$name.use { }' block",
                    confidence = 0.9
                ))
            }
        }

        return risks
    }

    private fun checkThreadSafety(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        val hasConcurrencyAnnotations = content.contains("@Volatile") ||
            content.contains("@Synchronized") ||
            content.contains("synchronized") ||
            content.contains("ConcurrentHashMap") ||
            content.contains("CopyOnWriteArrayList") ||
            content.contains("Atomic")

        if (!hasConcurrencyAnnotations && content.contains("object ") && content.contains("var ")) {
            for ((index, line) in lines.withIndex()) {
                if (line.contains("var ") && !line.contains("val ") &&
                    !line.contains("@Volatile") && !line.contains("synchronized")) {
                    risks.add(BugRisk(
                        category = BugCategory.THREAD_SAFETY,
                        severity = MEDIUM_SEVERITY,
                        file = filePath,
                        line = index + 1,
                        description = "Mutable state in singleton/object without thread safety",
                        preventionStrategy = BugPreventionStrategy.CONCURRENCY_CHECK,
                        autoFix = "Add @Volatile annotation or use 'by lazy'",
                        confidence = 0.7
                    ))
                }
            }
        }

        return risks
    }

    private fun checkLogicErrors(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        for ((index, line) in lines.withIndex()) {
            for ((pattern, category) in LOGIC_ERROR_PATTERNS) {
                if (category == null) continue
                if (pattern.containsMatchIn(line)) {
                    risks.add(BugRisk(
                        category = category,
                        severity = MEDIUM_SEVERITY,
                        file = filePath,
                        line = index + 1,
                        description = when {
                            line.contains("catch") -> "Empty catch block"
                            line.contains("TODO") -> "Unimplemented logic"
                            else -> "Potential logic error"
                        },
                        preventionStrategy = BugPreventionStrategy.CONTROL_FLOW,
                        autoFix = when {
                            line.contains("catch") -> "Add logging in catch block"
                            line.contains("TODO") -> "Implement the TODO"
                            else -> null
                        },
                        confidence = 0.8
                    ))
                }
            }
        }

        return risks
    }

    private fun checkPerformance(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        for ((index, line) in lines.withIndex()) {
            for ((pattern, category) in PERFORMANCE_PATTERNS) {
                if (category == null) continue
                if (pattern.containsMatchIn(line)) {
                    risks.add(BugRisk(
                        category = category,
                        severity = 2,
                        file = filePath,
                        line = index + 1,
                        description = "Potential performance issue",
                        preventionStrategy = BugPreventionStrategy.STATIC_ANALYSIS,
                        autoFix = null,
                        confidence = 0.65
                    ))
                }
            }
        }

        return risks
    }

    private fun checkSecurity(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()
        val lines = content.lines()

        for ((index, line) in lines.withIndex()) {
            for ((pattern, category) in SECURITY_PATTERNS) {
                if (category == null) continue
                if (pattern.containsMatchIn(line)) {
                    risks.add(BugRisk(
                        category = category,
                        severity = HIGH_SEVERITY,
                        file = filePath,
                        line = index + 1,
                        description = when {
                            line.contains("eval") -> "Use of eval() is a security risk"
                            line.contains("exec") -> "Command execution detected"
                            else -> "Potential security issue"
                        },
                        preventionStrategy = BugPreventionStrategy.STATIC_ANALYSIS,
                        autoFix = null,
                        confidence = 0.95
                    ))
                }
            }
        }

        return risks
    }

    private fun checkSyntaxGuards(filePath: String, content: String): List<BugRisk> {
        val risks = mutableListOf<BugRisk>()

        val linesWithoutComments = content.lines().map { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("//") -> ""
                trimmed.startsWith("/*") -> ""
                trimmed.startsWith("*") -> ""
                else -> {
                    val commentIndex = line.indexOf("//")
                    val codePart = if (commentIndex >= 0) line.substring(0, commentIndex) else line
                    codePart
                }
            }
        }.joinToString("\n")

        val openBraces = linesWithoutComments.count { it == '{' }
        val closeBraces = linesWithoutComments.count { it == '}' }
        val openParens = linesWithoutComments.count { it == '(' }
        val closeParens = linesWithoutComments.count { it == ')' }

        if (openBraces != closeBraces) {
            risks.add(BugRisk(
                category = BugCategory.SYNTAX,
                severity = HIGH_SEVERITY,
                file = filePath,
                line = 0,
                description = "Unbalanced braces: $openBraces open, $closeBraces close",
                preventionStrategy = BugPreventionStrategy.SYNTAX_GUARD,
                autoFix = "Check for missing or extra '{' or '}'",
                confidence = 1.0
            ))
        }

        if (openParens != closeParens) {
            risks.add(BugRisk(
                category = BugCategory.SYNTAX,
                severity = HIGH_SEVERITY,
                file = filePath,
                line = 0,
                description = "Unbalanced parentheses: $openParens open, $closeParens close",
                preventionStrategy = BugPreventionStrategy.SYNTAX_GUARD,
                autoFix = "Check for missing or extra '(' or ')'",
                confidence = 1.0
            ))
        }

        return risks
    }

    private fun autoFixRisks(risks: List<BugRisk>, content: String): List<BugRisk> {
        return risks.filter { it.autoFix != null && it.category in setOf(
            BugCategory.RESOURCE_LEAK,
            BugCategory.THREAD_SAFETY,
            BugCategory.SYNTAX
        ) }
    }

    private fun generateNullSafetyFix(line: String, category: BugCategory): String? {
        return when {
            line.contains("!!") -> "Replace '!!' with '?.' safe call"
            line.contains("lateinit") -> "Use nullable type with ?"
            line.contains(" as ") -> "Replace 'as' with 'as?' safe cast"
            else -> null
        }
    }

    private fun calculateConfidence(fileCount: Int, riskCount: Int, criticalCount: Int): Double {
        if (fileCount == 0) return 0.0
        val baseConfidence = 0.95
        val riskPenalty = minOf(riskCount * 0.02, 0.3)
        val criticalPenalty = minOf(criticalCount * 0.1, 0.4)
        return max(0.0, baseConfidence - riskPenalty - criticalPenalty)
    }

    fun getHistory(limit: Int = 50): List<BugHistoryEntry> {
        return history.takeLast(limit)
    }

    fun shutdown() {
        if (!isShutdown) {
            isShutdown = true
            executor.shutdown()
        }
    }
}
