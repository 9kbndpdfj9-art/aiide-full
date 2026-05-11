package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

data class CompletionSuggestion(val id: String, val text: String, val category: String, val confidence: Double, val source: String, val context: String = "", val metadata: Map<String, String> = emptyMap()) { fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("text", text); put("category", category); put("confidence", confidence); put("source", source); put("context", context); put("metadata", JSONObject(metadata)) } }
data class CompletionContext(val currentInput: String, val cursorPosition: Int, val recentIntents: List<String> = emptyList(), val activeFilePath: String = "", val projectType: String = "", val availableModels: List<String> = emptyList()) { fun toJson(): JSONObject = JSONObject().apply { put("current_input", currentInput); put("cursor_position", cursorPosition); put("recent_intents", JSONArray(recentIntents)); put("active_file_path", activeFilePath); put("project_type", projectType); put("available_models", JSONArray(availableModels)) } }

class IntentCompletionEngine(private val context: Context, private val knowledgeBase: OrganizationKnowledgeBase, private val toolGenome: ToolGenomeEngine) {
    companion object { private const val TAG = "IntentCompletionEngine"; private const val MAX_SUGGESTIONS = 10; private const val MAX_HISTORY = 200; private const val MIN_INPUT_LENGTH = 1; private val REGEX_ACTION = Regex("(创建|添加|删除|修改|重构|测试|审查|部署|生成|分析|修复|优化|迁移|搜索)", RegexOption.IGNORE_CASE); private val REGEX_TARGET = Regex("(函数|方法|类|接口|文件|模块|API|页面|组件|数据库|表|字段|配置|测试|文档)", RegexOption.IGNORE_CASE) }
    private val suggestionCounter = AtomicLong(0)
    private val intentHistory = object : LinkedHashMap<String, String>(64, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean { return size > MAX_HISTORY } }
    private val historyLock = Any()
    private val templateCache = ConcurrentHashMap<String, List<CompletionSuggestion>>()
    private val adoptionStats = ConcurrentHashMap<String, AtomicLong>>()

    init { loadTemplates() }

    private fun loadTemplates() {
        val createTemplates = listOf(CompletionSuggestion("tpl_create_func", "创建函数", "action", 0.9, "template", "创建一个新的函数"), CompletionSuggestion("tpl_create_class", "创建类", "action", 0.85, "template", "创建一个新的类"), CompletionSuggestion("tpl_create_api", "创建API接口", "action", 0.8, "template", "创建REST API端点"), CompletionSuggestion("tpl_create_test", "创建测试用例", "action", 0.75, "template", "为指定代码生成测试"), CompletionSuggestion("tpl_create_module", "创建模块", "action", 0.7, "template", "创建新的功能模块"), CompletionSuggestion("tpl_create_page", "创建页面", "action", 0.7, "template", "创建UI页面"))
        templateCache["创建"] = createTemplates
        val modifyTemplates = listOf(CompletionSuggestion("tpl_modify_refactor", "重构代码", "action", 0.8, "template", "重构指定代码"), CompletionSuggestion("tpl_modify_optimize", "优化性能", "action", 0.75, "template", "优化代码性能"), CompletionSuggestion("tpl_modify_fix", "修复Bug", "action", 0.85, "template", "修复指定问题"), CompletionSuggestion("tpl_modify_migrate", "迁移代码", "action", 0.6, "template", "迁移到新框架/版本"))
        templateCache["修改"] = modifyTemplates
        val analyzeTemplates = listOf(CompletionSuggestion("tpl_analyze_health", "分析项目健康度", "action", 0.7, "template", "全面分析项目健康状态"), CompletionSuggestion("tpl_analyze_security", "安全审查", "action", 0.75, "template", "安全漏洞扫描和审查"), CompletionSuggestion("tpl_analyze_deps", "分析依赖关系", "action", 0.65, "template", "分析模块依赖图"), CompletionSuggestion("tpl_analyze_coverage", "分析测试覆盖率", "action", 0.6, "template", "测试覆盖率分析"))
        templateCache["分析"] = analyzeTemplates
        val deleteTemplates = listOf(CompletionSuggestion("tpl_delete_file", "删除文件", "action", 0.5, "template", "删除指定文件（需确认）"), CompletionSuggestion("tpl_delete_module", "删除模块", "action", 0.4, "template", "删除整个模块（需确认）"))
        templateCache["删除"] = deleteTemplates
    }

    fun getSuggestions(completionContext: CompletionContext): List<CompletionSuggestion> {
        val input = completionContext.currentInput.trim()
        if (input.length < MIN_INPUT_LENGTH) return getPopularSuggestions()
        val allSuggestions = mutableListOf<CompletionSuggestion>()
        allSuggestions.addAll(getTemplateSuggestions(input)); allSuggestions.addAll(getHistorySuggestions(input)); allSuggestions.addAll(getKnowledgeSuggestions(input)); allSuggestions.addAll(getContextSuggestions(input, completionContext))
        return allSuggestions.distinctBy { it.text }.sortedByDescending { it.confidence }.take(MAX_SUGGESTIONS)
    }

    private fun getTemplateSuggestions(input: String): List<CompletionSuggestion> {
        val suggestions = mutableListOf<CompletionSuggestion>()
        for ((key, templates) in templateCache) { if (input.contains(key) || key.contains(input)) { suggestions.addAll(templates.map { t -> t.copy(confidence = t.confidence * if (input.startsWith(key)) 1.2 else 0.8) }) } }
        val actionMatch = REGEX_ACTION.find(input); if (actionMatch != null) { val targetMatch = REGEX_TARGET.find(input); if (targetMatch != null) { suggestions.add(CompletionSuggestion("combo_${suggestionCounter.incrementAndGet()}", "${actionMatch.value}${targetMatch.value}", "combo", 0.85, "pattern", "常见操作组合")) } }
        return suggestions
    }

    private fun getHistorySuggestions(input: String): List<CompletionSuggestion> {
        val suggestions = mutableListOf<CompletionSuggestion>()
        synchronized(historyLock) { intentHistory.values.filter { hist -> hist.contains(input, ignoreCase = true) || input.contains(hist.take(3), ignoreCase = true) }.take(5).forEach { match -> suggestions.add(CompletionSuggestion("hist_${suggestionCounter.incrementAndGet()}", match, "history", 0.7, "history", "历史意图")) } }
        return suggestions
    }

    private fun getKnowledgeSuggestions(input: String): List<CompletionSuggestion> {
        val suggestions = mutableListOf<CompletionSuggestion>()
        try { val searchResult = knowledgeBase.searchKnowledge(input, limit = 3); for (entry in searchResult.entries) { suggestions.add(CompletionSuggestion("kb_${suggestionCounter.incrementAndGet()}", "${entry.title}: ${entry.chosenOption}", "knowledge", 0.6, "knowledge_base", entry.scenario.take(100), mapOf("entry_id" to entry.id, "category" to entry.category.name))) } } catch (e: Exception) { Log.w(TAG, "Failed: ${e.message}") }
        return suggestions
    }

    private fun getContextSuggestions(input: String, ctx: CompletionContext): List<CompletionSuggestion> {
        val suggestions = mutableListOf<CompletionSuggestion>()
        if (ctx.activeFilePath.isNotEmpty()) { val ext = ctx.activeFilePath.substringAfterLast('.', ""); val langSuggestion = when (ext) { "kt", "java" -> "Kotlin/Java"; "xml" -> "Android Layout"; "py" -> "Python"; "js", "ts" -> "JavaScript/TypeScript"; else -> null }; if (langSuggestion != null) { suggestions.add(CompletionSuggestion("ctx_lang_${suggestionCounter.incrementAndGet()}", "在${langSuggestion}文件中${if (input.isNotEmpty()) input else "操作"}", "context", 0.5, "file_context", "当前文件: ${ctx.activeFilePath.takeLast(30)}")) } }
        if (ctx.recentIntents.isNotEmpty()) { val lastIntent = ctx.recentIntents.last(); if (lastIntent.contains("创建") || lastIntent.contains("添加")) { suggestions.add(CompletionSuggestion("ctx_follow_${suggestionCounter.incrementAndGet()}", "为刚才的变更编写测试", "follow_up", 0.65, "context_follow_up", "基于最近操作的建议")) } }
        return suggestions
    }

    private fun getPopularSuggestions(): List<CompletionSuggestion> = listOf(CompletionSuggestion("pop_create", "创建功能", "popular", 0.8, "popular"), CompletionSuggestion("pop_fix", "修复Bug", "popular", 0.75, "popular"), CompletionSuggestion("pop_refactor", "重构代码", "popular", 0.7, "popular"), CompletionSuggestion("pop_test", "编写测试", "popular", 0.65, "popular"), CompletionSuggestion("pop_review", "代码审查", "popular", 0.6, "popular"))

    fun recordAdoption(suggestionId: String, adoptedText: String) { adoptionStats.computeIfAbsent(suggestionId) { AtomicLong(0) }.incrementAndGet(); synchronized(historyLock) { intentHistory["hist_${System.currentTimeMillis()}"] = adoptedText } }
    fun recordIntent(intent: String) { synchronized(historyLock) { intentHistory["intent_${System.currentTimeMillis()}"] = intent } }
    fun getCategories(): List<String> = listOf("action", "combo", "history", "knowledge", "context", "follow_up", "popular")
    fun getAdoptionStats(): JSONObject = JSONObject().apply { put("total_adoptions", adoptionStats.values.sumOf { it.get() }); val topArr = JSONArray(); adoptionStats.entries.sortedByDescending { it.value.get() }.take(10).forEach { (id, count) -> topArr.put(JSONObject().put("suggestion_id", id).put("count", count.get())) }; put("top_adopted", topArr) }
    fun getStats(): JSONObject = JSONObject().apply { put("template_count", templateCache.values.sumOf { it.size }); put("history_count", synchronized(historyLock) { intentHistory.size }); put("suggestion_counter", suggestionCounter.get()); put("adoption_stats", getAdoptionStats()) }
}