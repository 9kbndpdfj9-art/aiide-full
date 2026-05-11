package com.aiide

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class VectorSearchEngine {
    private val index = ConcurrentHashMap<String, List<SearchResult>>()

    data class SearchResult(
        val id: String,
        val content: String,
        val score: Double,
        val metadata: Map<String, String> = emptyMap()
    )

    fun index(id: String, content: String, metadata: Map<String, String> = emptyMap()) {
        val terms = extractTerms(content)
        terms.forEach { term ->
            index.computeIfAbsent(term) { mutableListOf() }.let { list ->
                (list as MutableList).add(SearchResult(id, content, calculateScore(content, term), metadata))
            }
        }
    }

    fun search(query: String, limit: Int = 10): List<SearchResult> {
        val queryTerms = extractTerms(query)
        val results = mutableMapOf<String, Double>()

        queryTerms.forEach { term ->
            index[term]?.forEach { result ->
                results[result.id] = (results[result.id] ?: 0.0) + result.score
            }
        }

        return results.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { entry ->
                index.values.flatten().find { it.id == entry.key }
            }
    }

    private fun extractTerms(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .distinct()
    }

    private fun calculateScore(content: String, term: String): Double {
        val termLower = term.lowercase()
        val contentLower = content.lowercase()
        val termCount = contentLower.split(termLower).size - 1
        val totalTerms = extractTerms(content).size
        return if (totalTerms > 0) termCount.toDouble() / totalTerms else 0.0
    }

    fun clear() = index.clear()
}
