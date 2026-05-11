package com.aiide

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RefinementSuggestion(
    val type: String,
    val file: String,
    val line: Int,
    val column: Int = 0,
    val length: Int = 0,
    val original: String,
    val refined: String,
    val reason: String,
    val confidence: Double,
    val autoApplyable: Boolean
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"type\": \"${type.escapeJson()}\",")
        append("\"file\": \"${file.escapeJson()}\",")
        append("\"line\": $line,")
        append("\"column\": $column,")
        append("\"length\": $length,")
        append("\"original\": \"${original.escapeJson()}\",")
        append("\"refined\": \"${refined.escapeJson()}\",")
        append("\"reason\": \"${reason.escapeJson()}\",")
        append("\"confidence\": $confidence,")
        append("\"auto_applyable\": $autoApplyable")
        append("}")
    }

    private fun String.escapeJson() = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

data class RefinementResult(
    val batchId: String,
    val files: Map<String, String>,
    val suggestions: List<RefinementSuggestion>,
    val appliedSuggestions: Int,
    val totalSuggestions: Int,
    val qualityBefore: Double,
    val qualityAfter: Double,
    val executionTimeMs: Long
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"batch_id\": \"${batchId.escapeJson()}\",")
        append("\"files\": ${files.size},")
        append("\"total_suggestions\": $totalSuggestions,")
        append("\"applied_suggestions\": $appliedSuggestions,")
        append("\"quality_before\": $qualityBefore,")
        append("\"quality_after\": $qualityAfter,")
        append("\"execution_time_ms\": $executionTimeMs")
        append("}")
    }

    private fun String.escapeJson() = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

class CodeRefiner {
    companion object {
        private const val TAG = "CodeRefiner"
        private const val MAX_FILES_PER_BATCH = 50

        private val EMPTY_CATCH_REGEX = Regex("""catch\\s*\\(\\s*[^)]*\\)\\s*\\{\\s*\\}""")
        private val PRINT_STATEMENT_REGEX = Regex("""(?:print|println|console\\.log)\\s*\(""")

        private val REFINEMENT_RULES = listOf(
            RefinementRule(
                pattern = Regex("""var\\s+(\\w+)\\s*=\\s*mutableListOf\\(\)"""),
                replacement = "val $1 = mutableListOf()",
                reason = "Use val for collections that are only mutated through methods",
                type = "immutability"
            ),
            RefinementRule(
                pattern = Regex("""fun\\s+(\\w+)\\s*:\\s*Unit\\s*\("""),
                replacement = "fun $1()",
                reason = "Return type Unit is redundant",
                type = "redundancy"
            ),
            RefinementRule(
                pattern = Regex("""return\\s+Unit\\b"""),
                replacement = "",
                reason = "Returning Unit explicitly is redundant",
                type = "redundancy"
            ),
            RefinementRule(
                pattern = Regex("""if\\s*\\(\\s*(\\w+)\\s*==\\s*null\\s*\\)\\s*return\\s+null\\b"""),
                replacement = "$1 ?: return null",
                reason = "Use elvis operator for null checks",
                type = "idiomatic"
            ),
            RefinementRule(
                pattern = Regex("""if\\s*\\(\\s*(\\w+)\\s*!=\\s*null\\s*\\)\\s*\\{\\s*\\1\\.(\\w+)\\s*\\(\\s*\\)\\s*\\}"""),
                replacement = "$1?.$2()",
                reason = "Use safe call operator",
                type = "idiomatic"
            ),
            RefinementRule(
                pattern = Regex("""StringBuilder\\(\\).append\\((\\w+)\\).append\\((\\w+)\\).append\\((\\w+)\\)"""),
                replacement = "buildString { append($1); append($2); append($3) }",
                reason = "Use buildString for better readability",
                type = "idiomatic"
            ),
            RefinementRule(
                pattern = Regex("""for\\s*\\(\\s*(\\w+)\\s+in\\s+(\\w+)\\s*\\)\\s*\\{\\s*if\\s*\\(\\s*(\\w+)\\.(\\w+)\\s*\\(\\s*\\)\\s*\\)\\s*\\{"""),
                replacement = "for ($1 in $2) {\n    if ($1.$3()) {",
                reason = "Use consistent variable reference",
                type = "consistency"
            ),
            RefinementRule(
                pattern = Regex("""catch\\s*\\(\\s*[^)]*\\)\\s*\\{\\s*//\\s*do\\s*nothing\\s*\\}"""),
                replacement = "catch (e: Exception) {\n    Log.w(TAG, \"Operation failed\", e)\n}",
                reason = "Add logging to catch blocks",
                type = "error_handling"
            )
        )
    }

    @Volatile private var isShutdown = false
    private val resultCache = ConcurrentHashMap<String, RefinementResult>()
    private val resultCounter = AtomicLong(0)

    fun refineBatch(files: Map<String, String>, batchId: String? = null, autoApply: Boolean = false): RefinementResult {
        if (isShutdown) throw IllegalStateException("CodeRefiner is shutdown")
        if (files.isEmpty()) {
            return RefinementResult(
                batchId = batchId ?: "refine_0",
                files = emptyMap(),
                suggestions = emptyList(),
                appliedSuggestions = 0,
                totalSuggestions = 0,
                qualityBefore = 0.0,
                qualityAfter = 0.0,
                executionTimeMs = 0L
            )
        }
        if (files.size > MAX_FILES_PER_BATCH) {
            throw IllegalArgumentException("Too many files: ${files.size} (max: $MAX_FILES_PER_BATCH)")
        }

        val actualBatchId = batchId ?: "refine_${resultCounter.incrementAndGet()}"
        val startTime = System.currentTimeMillis()
        val allSuggestions = mutableListOf<RefinementSuggestion>()
        val refinedFiles = mutableMapOf<String, String>()

        try {
            for ((filePath, content) in files) {
                if (content.isBlank()) {
                    refinedFiles[filePath] = content
                    continue
                }
                val suggestions = analyzeFile(filePath, content)
                allSuggestions.addAll(suggestions)

                if (autoApply) {
                    val refinedContent = applyRefinement(content, suggestions)
                    refinedFiles[filePath] = refinedContent
                }
            }

            val qualityBefore = estimateQuality(files)
            val qualityAfter = if (autoApply) estimateQuality(refinedFiles) else qualityBefore
            val appliedCount = if (autoApply) allSuggestions.count { it.autoApplyable } else 0

            val result = RefinementResult(
                batchId = actualBatchId,
                files = if (autoApply) refinedFiles else files,
                suggestions = allSuggestions,
                appliedSuggestions = appliedCount,
                totalSuggestions = allSuggestions.size,
                qualityBefore = qualityBefore,
                qualityAfter = qualityAfter,
                executionTimeMs = System.currentTimeMillis() - startTime
            )

            resultCache[actualBatchId] = result

            Log.i(TAG, "Batch $actualBatchId: ${allSuggestions.size} suggestions, $appliedCount applied")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Refinement failed for batch $actualBatchId", e)
            return RefinementResult(
                batchId = actualBatchId,
                files = files,
                suggestions = emptyList(),
                appliedSuggestions = 0,
                totalSuggestions = 0,
                qualityBefore = 0.0,
                qualityAfter = 0.0,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    fun refineFile(filePath: String, content: String): List<RefinementSuggestion> {
        if (content.isBlank()) return emptyList()
        return analyzeFile(filePath, content)
    }

    fun applyRefinement(content: String, suggestions: List<RefinementSuggestion>): String {
        if (suggestions.isEmpty()) return content

        val sortedSuggestions = suggestions
            .filter { it.autoApplyable && it.original.isNotEmpty() }
            .sortedByDescending { it.line }
            .thenByDescending { it.column }

        var refined = content
        var appliedOffset = 0

        for (suggestion in sortedSuggestions) {
            if (suggestion.column > 0 && suggestion.length > 0) {
                val lines = refined.lines()
                val lineIndex = suggestion.line - 1
                if (lineIndex >= 0 && lineIndex < lines.size) {
                    val line = lines[lineIndex]
                    val startCol = suggestion.column - 1
                    val endCol = minOf(startCol + suggestion.length, line.length)
                    if (startCol >= 0 && startCol <= line.length) {
                        val actualOriginal = line.substring(startCol, endCol)
                        val newLine = line.substring(0, startCol) + suggestion.refined + line.substring(endCol)
                        refined = lines.toMutableList().apply {
                            set(lineIndex, newLine)
                        }.joinToString("\n")
                    }
                }
            } else {
                val firstIndex = refined.indexOf(suggestion.original)
                if (firstIndex >= 0) {
                    refined = refined.replaceRange(firstIndex, firstIndex + suggestion.original.length, suggestion.refined)
                }
            }
        }
        return refined
    }

    private fun analyzeFile(filePath: String, content: String): List<RefinementSuggestion> {
        val suggestions = mutableListOf<RefinementSuggestion>()
        val lines = content.lines()

        for ((index, line) in lines.withIndex()) {
            for (rule in REFINEMENT_RULES) {
                val match = rule.pattern.find(line)
                if (match != null) {
                    val original = match.value
                    val refined = rule.getReplacement(match)
                    val column = match.range.first + 1

                    suggestions.add(RefinementSuggestion(
                        type = rule.type,
                        file = filePath,
                        line = index + 1,
                        column = column,
                        length = original.length,
                        original = original,
                        refined = refined,
                        reason = rule.reason,
                        confidence = 0.9,
                        autoApplyable = true
                    ))
                }
            }

            if (EMPTY_CATCH_REGEX.containsMatchIn(line)) {
                suggestions.add(RefinementSuggestion(
                    type = "anti_pattern",
                    file = filePath,
                    line = index + 1,
                    column = 1,
                    length = line.trim().length,
                    original = line.trim(),
                    refined = "// TODO: Handle exception properly",
                    reason = "Empty catch block swallows exceptions",
                    confidence = 0.8,
                    autoApplyable = false
                ))
            }

            if (PRINT_STATEMENT_REGEX.containsMatchIn(line)) {
                suggestions.add(RefinementSuggestion(
                    type = "anti_pattern",
                    file = filePath,
                    line = index + 1,
                    column = 1,
                    length = line.trim().length,
                    original = line.trim(),
                    refined = "// TODO: Replace with proper logging",
                    reason = "Debug print statements should use a logging framework",
                    confidence = 0.75,
                    autoApplyable = false
                ))
            }
        }

        val multiLineContent = content
        for (rule in REFINEMENT_RULES) {
            if (rule.pattern.containsMatchIn(multiLineContent)) {
                val matchResult = rule.pattern.find(multiLineContent)
                if (matchResult != null) {
                    val matchLineNum = multiLineContent.substring(0, matchResult.range.first).count { it == '\n' } + 1
                    val existingMatch = suggestions.any { it.line == matchLineNum && it.type == rule.type }
                    if (!existingMatch) {
                        suggestions.add(RefinementSuggestion(
                            type = rule.type,
                            file = filePath,
                            line = matchLineNum,
                            column = matchResult.range.first + 1,
                            length = matchResult.value.length,
                            original = matchResult.value,
                            refined = rule.getReplacement(matchResult),
                            reason = rule.reason,
                            confidence = 0.85,
                            autoApplyable = true
                        ))
                    }
                }
            }
        }

        return suggestions.distinctBy { Triple(it.line, it.type, it.original) }
    }

    private fun estimateQuality(files: Map<String, String>): Double {
        if (files.isEmpty()) return 0.0

        val scores = files.values.map { content ->
            var score = 1.0
            val lines = content.lines()

            val hasComments = lines.any { it.trim().startsWith("//") || it.trim().startsWith("/**") }
            if (!hasComments && lines.size > 10) score -= 0.1

            if (lines.any { EMPTY_CATCH_REGEX.containsMatchIn(it) }) score -= 0.15

            if (lines.any { PRINT_STATEMENT_REGEX.containsMatchIn(it) }) score -= 0.1

            val nonBlankLines = lines.filter { it.isNotBlank() }
            if (nonBlankLines.isNotEmpty()) {
                val avgLineLength = nonBlankLines.map { it.length }.average()
                if (avgLineLength > 100) score -= 0.1
            }

            score.coerceIn(0.0, 1.0)
        }

        return scores.average()
    }

    fun getCachedResult(batchId: String): RefinementResult? = resultCache[batchId]

    fun clearCache() {
        resultCache.clear()
    }

    fun shutdown() {
        isShutdown = true
        resultCache.clear()
    }
}

data class RefinementRule(
    val pattern: Regex,
    val replacement: String,
    val reason: String,
    val type: String
) {
    fun getReplacement(match: MatchResult): String {
        var result = replacement
        for (i in 1..minOf(9, match.groupValues.size - 1)) {
            result = result.replace("$" + i, match.groupValues[i])
        }
        return result
    }
}