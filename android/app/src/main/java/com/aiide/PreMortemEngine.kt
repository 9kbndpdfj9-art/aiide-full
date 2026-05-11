package com.aiide

import android.content.Context
import org.json.JSONObject
import java.io.File

class PreMortemEngine(private val context: Context) {

    enum class PreMortemSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    data class PreMortemPattern(
        val id: String,
        val pattern: Regex,
        val severity: PreMortemSeverity,
        val description: String,
        val suggestion: String
    )

    data class PreMortemResult(
        val proceedAllowed: Boolean,
        val warnings: List<String>,
        val suggestions: List<String>,
        val injectedConstraints: List<String> = emptyList()
    )

    private val patterns = mutableListOf(
        PreMortemPattern(
            "sql_injection",
            Regex("(?i)sql.*concat|exec\(|statement.*\+"),
            PreMortemSeverity.HIGH,
            "Potential SQL injection vulnerability",
            "Use parameterized queries"
        ),
        PreMortemPattern(
            "hardcoded_secret",
            Regex("(?i)(password|secret|token|api.key)\s*=\s*['\"][^'\"]{8,}"),
            PreMortemSeverity.CRITICAL,
            "Hardcoded secret detected",
            "Move secrets to secure storage"
        ),
        PreMortemPattern(
            "eval_usage",
            Regex("eval\s*\(|Function\s*\("),
            PreMortemSeverity.HIGH,
            "Dynamic code execution detected",
            "Avoid eval, use safer alternatives"
        ),
        PreMortemPattern(
            "xss",
            Regex("innerHTML\s*=|document\.write\("),
            PreMortemSeverity.HIGH,
            "Potential XSS vulnerability",
            "Use textContent or sanitization"
        )
    )

    fun performCheck(intent: String): PreMortemResult {
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        val constraints = mutableListOf<String>()

        for (pattern in patterns) {
            if (pattern.pattern.containsMatchIn(intent)) {
                warnings.add("[${pattern.severity}] ${pattern.description}")
                suggestions.add(pattern.suggestion)

                if (pattern.severity == PreMortemSeverity.CRITICAL) {
                    constraints.add("CRITICAL: ${pattern.suggestion}")
                }
            }
        }

        val hasCritical = warnings.any { it.startsWith("[CRITICAL]") }

        return PreMortemResult(
            proceedAllowed = !hasCritical,
            warnings = warnings,
            suggestions = suggestions,
            injectedConstraints = constraints
        )
    }

    fun addPattern(pattern: PreMortemPattern) {
        patterns.add(pattern)
    }

    fun removePattern(patternId: String) {
        patterns.removeIf { it.id == patternId }
    }

    fun getPatterns(): List<PreMortemPattern> = patterns.toList()
}
