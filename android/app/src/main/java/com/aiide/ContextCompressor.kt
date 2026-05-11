package com.aiide

import org.json.JSONObject
import org.json.JSONArray

class ContextCompressor {
    companion object { private const val MAX_CONTEXT_TOKENS = 120000; private const val TARGET_CONTEXT_TOKENS = 80000; private const val TOKEN_ESTIMATE = 4 }

    data class CompressedContext(val originalTokens: Int, val compressedTokens: Int, val compressionRatio: Double, val preservedItems: List<String>, val summary: String, val compressedContent: String, val canRestore: Boolean) {
        fun toJson(): JSONObject = JSONObject().apply { put("originalTokens", originalTokens); put("compressedTokens", compressedTokens); put("compressionRatio", compressionRatio); put("preservedItems", JSONArray(preservedItems)); put("summary", summary); put("compressedContent", compressedContent); put("canRestore", canRestore) }
    }

    data class ContextItem(val type: String, val path: String, val content: String, val importance: Double, val tokenCount: Int)

    fun compressContext(files: Map<String, String>, currentQuery: String = "", maxTokens: Int = TARGET_CONTEXT_TOKENS): CompressedContext {
        val contextItems = mutableListOf<ContextItem>()
        files.forEach { (path, content) -> contextItems.addAll(extractContextItems(path, content)) }
        val totalTokens = contextItems.sumOf { it.tokenCount }
        if (totalTokens <= maxTokens) return CompressedContext(originalTokens = totalTokens, compressedTokens = totalTokens, compressionRatio = 1.0, preservedItems = contextItems.map { "${it.type}:${it.path}" }, summary = "No compression needed", compressedContent = files.values.joinToString("\n\n"), canRestore = true)
        val sortedItems = contextItems.sortedByDescending { it.importance }
        val preserved = mutableListOf<ContextItem>(); var currentTokenCount = 0
        sortedItems.forEach { item -> if (currentTokenCount + item.tokenCount <= maxTokens) { preserved.add(item); currentTokenCount += item.tokenCount } }
        val compressedContent = buildCompressedContent(preserved, sortedItems.filter { !preserved.contains(it) })
        val summary = generateCompressionSummary(preserved, sortedItems.filter { !preserved.contains(it) })
        return CompressedContext(originalTokens = totalTokens, compressedTokens = currentTokenCount, compressionRatio = currentTokenCount.toDouble() / totalTokens, preservedItems = preserved.map { "${it.type}:${it.path}" }, summary = summary, compressedContent = compressedContent, canRestore = true)
    }

    fun compressConversation(messages: JSONArray, maxTokens: Int = TARGET_CONTEXT_TOKENS): CompressedContext {
        val originalTokens = estimateJsonTokens(messages)
        if (originalTokens <= maxTokens) return CompressedContext(originalTokens = originalTokens, compressedTokens = originalTokens, compressionRatio = 1.0, preservedItems = listOf("All messages preserved"), summary = "No compression needed", compressedContent = messages.toString(), canRestore = true)
        val compressedMessages = JSONArray()
        for (i in 0 until messages.length()) { val msg = messages.optJSONObject(i) ?: continue; val role = msg.optString("role", ""); val content = msg.optString("content", ""); when { i == 0 && role == "system" -> compressedMessages.put(msg); i == messages.length() - 1 -> compressedMessages.put(msg); role == "user" && content.length > 500 -> compressedMessages.put(JSONObject().put("role", "user").put("content", content.take(200) + "...[compressed]")); role == "assistant" && content.length > 1000 -> compressedMessages.put(JSONObject().put("role", "assistant").put("content", content.take(300) + "...[compressed]")); else -> compressedMessages.put(msg) } }
        val compressedContent = compressedMessages.toString(); val compressedTokens = estimateJsonTokens(compressedMessages)
        return CompressedContext(originalTokens = originalTokens, compressedTokens = compressedTokens, compressionRatio = compressedTokens.toDouble() / originalTokens, preservedItems = listOf("System prompt", "First/Last messages", "Compressed middle messages"), summary = "Compressed ${messages.length()} messages", compressedContent = compressedContent, canRestore = false)
    }

    fun generateContextSummary(files: Map<String, String>): String = buildString { files.forEach { (path, content) -> append("## $path\n"); val lines = content.lines(); append("- Lines: ${lines.size}\n"); val imports = lines.filter { it.trim().startsWith("import ") || it.trim().startsWith("from ") }; if (imports.isNotEmpty()) append("- Imports: ${imports.take(5).joinToString(", ")}\n"); val functions = Regex("""\b(?:fun|function|def|fn)\s+(\w+)""").findAll(content).map { it.groupValues[1] }.toList(); if (functions.isNotEmpty()) append("- Functions: ${functions.take(10).joinToString(", ")}\n"); val classes = Regex("""\b(?:class|interface|object|struct)\s+(\w+)""").findAll(content).map { it.groupValues[1] }.toList(); if (classes.isNotEmpty()) append("- Classes: ${classes.take(5).joinToString(", ")}\n"); append("\n") } }

    private fun extractContextItems(path: String, content: String): List<ContextItem> { val items = mutableListOf<ContextItem>(); val lines = content.lines(); lines.forEachIndexed { index, line -> val trimmed = line.trim(); if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) items.add(ContextItem("import", "$path:$index", trimmed, 0.9, trimmed.length / TOKEN_ESTIMATE)); if (trimmed.matches(Regex("""\b(?:fun|function|def|fn)\s+\w+.*\{?"""))) items.add(ContextItem("function", "$path:$index", extractFunctionBody(lines, index), 0.8, extractFunctionBody(lines, index).length / TOKEN_ESTIMATE)); if (trimmed.matches(Regex("""\b(?:class|interface|object|struct)\s+\w+.*\{?"""))) items.add(ContextItem("class", "$path:$index", extractClassBody(lines, index), 0.85, extractClassBody(lines, index).length / TOKEN_ESTIMATE)) }; return items }

    private fun extractFunctionBody(lines: List<String>, startIndex: Int): String { var endIndex = startIndex + 1; var braceCount = 0; var foundOpening = false; while (endIndex < lines.size) { braceCount += lines[endIndex].count { it == '{' }; braceCount -= lines[endIndex].count { it == '}' }; if (braceCount > 0) foundOpening = true; if (foundOpening && braceCount <= 0) break; endIndex++ }; return lines.subList(startIndex, minOf(endIndex + 1, lines.size)).joinToString("\n") }

    private fun extractClassBody(lines: List<String>, startIndex: Int): String { var endIndex = startIndex + 1; var braceCount = 0; var foundOpening = false; while (endIndex < lines.size) { if (!foundOpening && lines[endIndex].contains('{')) foundOpening = true; braceCount += lines[endIndex].count { it == '{' }; braceCount -= lines[endIndex].count { it == '}' }; if (foundOpening && braceCount <= 0) break; endIndex++ }; return lines.subList(startIndex, minOf(endIndex + 1, lines.size)).joinToString("\n") }

    private fun buildCompressedContent(preserved: List<ContextItem>, compressed: List<ContextItem>): String = buildString { append("=== PRESERVED CONTEXT ===\n\n"); preserved.forEach { append("## ${it.type} (${it.path})\n${it.content}\n\n") }; if (compressed.isNotEmpty()) { append("=== COMPRESSED CONTEXT ===\n\n"); compressed.groupBy { it.type }.forEach { (type, items) -> append("## $type (${items.size} items)\n"); items.forEach { append("- ${it.path}: ${it.content.take(100)}[...compressed]\n") }; append("\n") } } }

    private fun generateCompressionSummary(preserved: List<ContextItem>, compressed: List<ContextItem>): String = buildString { append("Preserved: "); preserved.groupBy { it.type }.forEach { (type, items) -> append("${items.size} $type, ") }; append("\nCompressed: "); compressed.groupBy { it.type }.forEach { (type, items) -> append("${items.size} $type, ") } }
    private fun estimateJsonTokens(jsonArray: JSONArray): Int = jsonArray.toString().length / TOKEN_ESTIMATE
}