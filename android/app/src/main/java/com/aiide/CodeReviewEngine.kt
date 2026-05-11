package com.aiide

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class ReviewIssue(
    val severity: IssueSeverity,
    val category: String,
    val message: String,
    val filePath: String,
    val line: Int = 0,
    val column: Int = 0,
    val suggestion: String = ""
)

enum class IssueSeverity { ERROR, WARNING, INFO, HINT }

data class ReviewReport(
    val totalFiles: Int,
    val issues: List<ReviewIssue>,
    val score: Double,
    val categories: Set<String>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("totalFiles", totalFiles)
        put("issues", org.json.JSONArray(issues.map { JSONObject().apply {
            put("severity", it.severity.name)
            put("category", it.category)
            put("message", it.message)
            put("filePath", it.filePath)
            put("line", it.line)
            put("column", it.column)
            put("suggestion", it.suggestion)
        }}))
        put("score", score)
        put("categories", org.json.JSONArray(categories))
        put("timestamp", timestamp)
    }
}

class CodeReviewEngine(private val context: Context) {
    private val syntaxPatterns = ConcurrentHashMap<String, Pattern>()
    private val styleRules = ConcurrentHashMap<String, StyleRule>()
    private val securityRules = ConcurrentHashMap<String, SecurityRule>()
    
    init {
        initializeSyntaxPatterns()
        initializeStyleRules()
        initializeSecurityRules()
    }

    private fun initializeSyntaxPatterns() {
        syntaxPatterns["unclosed_brace"] = Pattern.compile("""\{[^{}]*${'$'}""")
        syntaxPatterns["unclosed_paren"] = Pattern.compile("""\([^()]*${'$'}""")
        syntaxPatterns["trailing_semicolon"] = Pattern.compile(""";\s*\n\s*\n\s*""")
    }

    private fun initializeStyleRules() {
        styleRules["max_line_length"] = StyleRule("Line exceeds 120 characters", 120)
        styleRules["max_function_length"] = StyleRule("Function exceeds 50 lines", 50)
        styleRules["magic_numbers"] = StyleRule("Avoid magic numbers", 0)
        styleRules["naming_convention"] = StyleRule("Follow naming conventions", 0)
    }

    private fun initializeSecurityRules() {
        securityRules["hardcoded_password"] = SecurityRule(
            "Hardcoded credentials detected",
            listOf("password", "secret", "api_key", "token"),
            IssueSeverity.ERROR
        )
        securityRules["sql_injection"] = SecurityRule(
            "Potential SQL injection",
            listOf("SELECT * FROM", "INSERT INTO", "DELETE FROM"),
            IssueSeverity.WARNING
        )
        securityRules["eval_usage"] = SecurityRule(
            "Avoid using eval()",
            listOf("eval(", "Function(\"\""),
            IssueSeverity.WARNING
        )
    }

    fun reviewProject(
        projectDir: File,
        categories: Set<String> = setOf("syntax", "style", "security", "performance", "architecture"),
        maxFiles: Int = MAX_REVIEW_FILES
    ): ReviewReport {
        val allIssues = mutableListOf<ReviewIssue>()
        val kotlinFiles = projectDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .take(maxFiles)
            .toList()
        
        kotlinFiles.forEach { file ->
            try {
                val content = file.readText()
                val fileIssues = reviewFile(content, file.absolutePath, categories)
                allIssues.addAll(fileIssues)
            } catch (e: Exception) {
                allIssues.add(ReviewIssue(
                    IssueSeverity.ERROR, "file_read",
                    "Failed to read file: ${e.message}",
                    file.absolutePath
                ))
            }
        }
        
        val score = calculateScore(allIssues, kotlinFiles.size)
        
        return ReviewReport(
            totalFiles = kotlinFiles.size,
            issues = allIssues,
            score = score,
            categories = categories
        )
    }

    fun reviewDiff(
        oldContent: String,
        newContent: String,
        filePath: String,
        categories: Set<String> = setOf("syntax", "style", "security", "performance")
    ): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        val oldLines = oldContent.split("\n")
        val newLines = newContent.split("\n")
        
        for (i in newLines.indices) {
            if (i >= oldLines.size || newLines[i] != oldLines[i]) {
                val lineContent = newLines[i]
                
                if (categories.contains("security")) {
                    issues.addAll(checkSecurity(newLines.take(i + 1).joinToString("\n"), filePath, i + 1))
                }
                
                if (categories.contains("style")) {
                    issues.addAll(checkStyle(listOf(lineContent), filePath, i + 1))
                }
            }
        }
        
        return issues
    }

    private fun reviewFile(content: String, filePath: String, categories: Set<String>): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        val lines = content.split("\n")
        
        if (categories.contains("syntax")) {
            issues.addAll(checkSyntax(content, filePath))
        }
        
        if (categories.contains("style")) {
            issues.addAll(checkStyle(lines, filePath))
        }
        
        if (categories.contains("security")) {
            issues.addAll(checkSecurity(content, filePath))
        }
        
        if (categories.contains("performance")) {
            issues.addAll(checkPerformance(content, filePath))
        }
        
        if (categories.contains("architecture")) {
            issues.addAll(checkArchitecture(content, filePath))
        }
        
        return issues
    }

    private fun checkSyntax(content: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        if (openBraces != closeBraces) {
            issues.add(ReviewIssue(
                IssueSeverity.ERROR, "syntax",
                "Mismatched braces: $openBraces open, $closeBraces close",
                filePath
            ))
        }
        
        val openParens = content.count { it == '(' }
        val closeParens = content.count { it == ')' }
        if (openParens != closeParens) {
            issues.add(ReviewIssue(
                IssueSeverity.ERROR, "syntax",
                "Mismatched parentheses: $openParens open, $closeParens close",
                filePath
            ))
        }
        
        return issues
    }

    private fun checkStyle(lines: List<String>, filePath: String, offset: Int = 0): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        lines.forEachIndexed { index, line ->
            if (line.length > 120) {
                issues.add(ReviewIssue(
                    IssueSeverity.INFO, "style",
                    "Line exceeds 120 characters (${line.length} chars)",
                    filePath, index + 1 + offset
                ))
            }
            
            if (line.matches(Regex(".*[0-9]{4,}.*")) && !line.contains("version") && !line.contains("date")) {
                if (line.matches(Regex(".*[0-9]{4,}.*\d{4,}.*"))) {
                    issues.add(ReviewIssue(
                        IssueSeverity.HINT, "style",
                        "Potential magic number",
                        filePath, index + 1 + offset
                    ))
                }
            }
            
            if (index > 0 && lines[index - 1].trim().endsWith("{") && line.trim().isEmpty()) {
                // Empty line after opening brace - acceptable
            }
        }
        
        return issues
    }

    private fun checkSecurity(content: String, filePath: String, offsetLine: Int = 0): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        val lines = content.split("\n")
        
        lines.forEachIndexed { index, line ->
            val lowerLine = line.lowercase()
            
            if (securityRules["hardcoded_password"]?.patterns?.any { lowerLine.contains(it) } == true) {
                issues.add(ReviewIssue(
                    IssueSeverity.ERROR, "security",
                    "Potential hardcoded credential detected",
                    filePath, index + 1 + offsetLine,
                    "Use environment variables or secure storage instead"
                ))
            }
            
            if (lowerLine.contains("eval(") || lowerLine.contains("runtime.exec")) {
                issues.add(ReviewIssue(
                    IssueSeverity.WARNING, "security",
                    "Potentially dangerous function usage",
                    filePath, index + 1 + offsetLine
                ))
            }
        }
        
        return issues
    }

    private fun checkPerformance(content: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        if (content.contains("ArrayList") && !content.contains("List<") {
            issues.add(ReviewIssue(
                IssueSeverity.INFO, "performance",
                "Consider using List interface instead of concrete ArrayList",
                filePath
            ))
        }
        
        val stringConcats = Regex("\+\\s*\").findAll(content).count()
        if (stringConcats > 10) {
            issues.add(ReviewIssue(
                IssueSeverity.HINT, "performance",
                "Consider using StringBuilder for multiple concatenations",
                filePath
            ))
        }
        
        return issues
    }

    private fun checkArchitecture(content: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        val classMatches = Regex("class\\s+(\\w+)").findAll(content)
        val functionMatches = Regex("fun\\s+(\\w+)").findAll(content)
        
        if (classMatches.count() > 5) {
            issues.add(ReviewIssue(
                IssueSeverity.INFO, "architecture",
                "File contains ${classMatches.count()} classes - consider splitting",
                filePath
            ))
        }
        
        if (functionMatches.count() > 20) {
            issues.add(ReviewIssue(
                IssueSeverity.INFO, "architecture",
                "File contains ${functionMatches.count()} functions - consider refactoring",
                filePath
            ))
        }
        
        return issues
    }

    private fun calculateScore(issues: List<ReviewIssue>, totalFiles: Int): Double {
        if (totalFiles == 0) return 1.0
        
        val errorWeight = 0.3
        val warningWeight = 0.1
        val infoWeight = 0.02
        val hintWeight = 0.005
        
        val errorCount = issues.count { it.severity == IssueSeverity.ERROR }
        val warningCount = issues.count { it.severity == IssueSeverity.WARNING }
        val infoCount = issues.count { it.severity == IssueSeverity.INFO }
        val hintCount = issues.count { it.severity == IssueSeverity.HINT }
        
        val totalPenalty = errorCount * errorWeight + warningCount * warningWeight +
                          infoCount * infoWeight + hintCount * hintWeight
        
        val normalizedPenalty = totalPenalty / totalFiles
        return (1.0 - normalizedPenalty.coerceIn(0.0, 1.0))
    }

    companion object {
        private const val MAX_REVIEW_FILES = 100
    }
}

data class StyleRule(val message: String, val threshold: Int)
data class SecurityRule(val message: String, val patterns: List<String>, val severity: IssueSeverity)
