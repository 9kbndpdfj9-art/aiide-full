package com.aiide

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class RefactorType {
    EXTRACT_METHOD, EXTRACT_CLASS, RENAME, SIMPLIFY, INLINE,
    DECOMPOSE, REPLACE_CONDITIONAL, MOVE_METHOD, PULL_UP, PUSH_DOWN
}

enum class ImpactLevel { LOW, MEDIUM, HIGH }

data class RefactorSuggestion(
    val id: String,
    val type: RefactorType,
    val filePath: String,
    val targetCode: String,
    val suggestion: String,
    val reason: String,
    val estimatedImpact: ImpactLevel,
    val complexity: Int,
    val codeExample: String
)

data class CodeSmell(
    val type: String,
    val description: String,
    val filePath: String,
    val line: Int,
    val severity: Int,
    val details: String
)

data class RefactorReport(
    val filePath: String,
    val smells: List<CodeSmell>,
    val suggestions: List<RefactorSuggestion>,
    val overallQuality: Int,
    val priority: String
)

class RefactorRecommendationEngine {

    companion object {
        private const val TAG = "RefactorRecommendationEngine"
        private const val LONG_METHOD_THRESHOLD = 50
        private const val LARGE_CLASS_LINE_THRESHOLD = 500
        private const val LONG_PARAM_THRESHOLD = 4
    }

    private val suggestionCounter = ConcurrentHashMap<String, Long>()

    fun analyzeFile(content: String, filePath: String): RefactorReport {
        val smells = getCodeSmells(content, filePath)
        val suggestions = getSuggestions(RefactorReport(filePath, smells, emptyList(), 0, "low"))
        val quality = calculateQuality(smells, content)
        val priority = determinePriority(smells, quality)
        return RefactorReport(filePath, smells, suggestions, quality, priority)
    }

    fun getCodeSmells(content: String, filePath: String): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        smells.addAll(detectLongMethod(content, filePath))
        smells.addAll(detectLargeClass(content, filePath))
        smells.addAll(detectDuplicateCode(content, filePath))
        smells.addAll(detectDeadCode(content, filePath))
        return smells.sortedByDescending { it.severity }
    }

    private fun detectLongMethod(content: String, filePath: String): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        val lines = content.lines()
        var inFunction = false
        var functionStart = 0
        var braceCount = 0
        var functionName = ""

        val functionRegex = Regex("""(?:fun|function|def)\s+(\w+)\s*\([^)]*\)\s*(?::\s*\w+)?\s*\{""")

        lines.forEachIndexed { index, line ->
            val match = functionRegex.find(line)
            if (match != null && !inFunction) {
                inFunction = true
                functionStart = index
                functionName = match.groupValues[1]
                braceCount = line.count { it == '{' } - line.count { it == '}' }
            } else if (inFunction) {
                braceCount += line.count { it == '{' } - line.count { it == '}' }
                if (braceCount <= 0) {
                    val methodLength = index - functionStart + 1
                    if (methodLength > LONG_METHOD_THRESHOLD) {
                        smells.add(CodeSmell(
                            type = "long_method",
                            description = "Method '$functionName' is $methodLength lines",
                            filePath = filePath,
                            line = functionStart + 1,
                            severity = if (methodLength > 100) 5 else if (methodLength > 75) 4 else 3,
                            details = "Consider breaking this method into smaller, focused methods."
                        ))
                    }
                    inFunction = false
                }
            }
        }
        return smells
    }

    private fun detectLargeClass(content: String, filePath: String): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        val lines = content.lines()
        val totalLines = lines.size
        val methodCount = Regex("""(?:fun|function|def)\s+(\w+)""").findAll(content).count()

        if (totalLines > LARGE_CLASS_LINE_THRESHOLD) {
            val className = Regex("""(?:class|object|interface)\s+(\w+)""").find(content)?.groupValues?.get(1) ?: "Unknown"
            smells.add(CodeSmell(
                type = "large_class",
                description = "Class '$className' has $totalLines lines",
                filePath = filePath,
                line = 1,
                severity = if (totalLines > 1000) 5 else 4,
                details = "Large classes often violate the Single Responsibility Principle."
            ))
        }
        return smells
    }

    private fun detectDuplicateCode(content: String, filePath: String): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        val lines = content.lines()
        val seenBlocks = mutableMapOf<String, Int>()
        val blockSize = 5

        for (i in 0 until lines.size - blockSize) {
            val block = lines.subList(i, i + blockSize)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
                .joinToString("\n")

            if (block.lines().size >= 3) {
                val key = block.hashCode().toString()
                if (seenBlocks.containsKey(key)) {
                    smells.add(CodeSmell(
                        type = "duplicate_code",
                        description = "Duplicate code block at lines ${seenBlocks[key]!! + 1}-${i + 1}",
                        filePath = filePath,
                        line = i + 1,
                        severity = 4,
                        details = "Duplicate code violates DRY principle."
                    ))
                } else {
                    seenBlocks[key] = i
                }
            }
        }
        return smells.distinctBy { it.line }
    }

    private fun detectDeadCode(content: String, filePath: String): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        val unusedFunctionRegex = Regex("""private\s+fun\s+(\w+)\s*\(""")

        unusedFunctionRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val callPattern = Regex("""\b${Regex.escape(name)}\b""")
            val matches = callPattern.findAll(content).toList()
            if (matches.size <= 1) {
                smells.add(CodeSmell(
                    type = "dead_code",
                    description = "Private function '$name' appears unused",
                    filePath = filePath,
                    line = content.lines().indexOfFirst { it.contains("fun $name") } + 1,
                    severity = 3,
                    details = "Unused code adds maintenance burden."
                ))
            }
        }
        return smells
    }

    fun getSuggestions(report: RefactorReport): List<RefactorSuggestion> {
        return report.smells.map { smell ->
            createExtractMethodSuggestion(smell, report.filePath)
        }
    }

    private fun createExtractMethodSuggestion(smell: CodeSmell, filePath: String): RefactorSuggestion {
        return RefactorSuggestion(
            id = generateSuggestionId("extract_method"),
            type = RefactorType.EXTRACT_METHOD,
            filePath = filePath,
            targetCode = smell.description,
            suggestion = "Extract the identified code block into a separate method",
            reason = smell.details,
            estimatedImpact = ImpactLevel.MEDIUM,
            complexity = 2,
            codeExample = "fun extractedMethod() { /* extracted code */ }"
        )
    }

    private fun calculateQuality(smells: List<CodeSmell>, content: String): Int {
        if (content.isEmpty()) return 100
        var quality = 100
        quality -= smells.sumOf { it.severity } * 3
        return quality.coerceIn(0, 100)
    }

    private fun determinePriority(smells: List<CodeSmell>, quality: Int): String {
        val criticalCount = smells.count { it.severity >= 4 }
        return when {
            criticalCount > 0 || quality < 30 -> "critical"
            quality < 50 -> "high"
            smells.size > 5 || quality < 70 -> "medium"
            else -> "low"
        }
    }

    private fun generateSuggestionId(type: String): String {
        val counter = suggestionCounter.merge(type, 1L) { a, b -> a + b } ?: 1L
        return "refactor_${type}_${System.currentTimeMillis()}_$counter"
    }
}
