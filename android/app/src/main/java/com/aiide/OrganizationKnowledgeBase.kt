package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class KnowledgeCategory { PERFORMANCE, SECURITY, ARCHITECTURE, DATABASE, CONCURRENCY, OTHER }
enum class KnowledgeSource { AGENT_DECISION, MANUAL, COMMUNITY }

data class KnowledgeEntry(
    val id: String, val title: String, val category: KnowledgeCategory, val tags: List<String> = emptyList(),
    val scenario: String, val options: List<String> = emptyList(), val chosenOption: String, val reason: String,
    val relatedCodeLocations: List<String> = emptyList(), val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String = "agent", val source: KnowledgeSource = KnowledgeSource.AGENT_DECISION
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("category", category.name); put("tags", JSONArray(tags))
        put("scenario", scenario); put("options", JSONArray(options)); put("chosen_option", chosenOption)
        put("reason", reason); put("related_code_locations", JSONArray(relatedCodeLocations)); put("created_at", createdAt)
        put("created_by", createdBy); put("source", source.name)
    }
    companion object { fun fromJson(json: JSONObject): KnowledgeEntry = KnowledgeEntry(
        id = json.optString("id", ""), title = json.optString("title", ""),
        category = KnowledgeCategory.valueOf(json.optString("category", KnowledgeCategory.OTHER.name)),
        tags = (0 until json.optJSONArray("tags")?.length() ?: 0).map { json.optJSONArray("tags").optString(it) },
        scenario = json.optString("scenario", ""),
        options = (0 until json.optJSONArray("options")?.length() ?: 0).map { json.optJSONArray("options").optString(it) },
        chosenOption = json.optString("chosen_option", ""), reason = json.optString("reason", ""),
        relatedCodeLocations = (0 until json.optJSONArray("related_code_locations")?.length() ?: 0).map { json.optJSONArray("related_code_locations").optString(it) },
        createdAt = json.optLong("created_at", System.currentTimeMillis()), createdBy = json.optString("created_by", "agent"),
        source = KnowledgeSource.valueOf(json.optString("source", KnowledgeSource.AGENT_DECISION.name))
    )}
}
data class KnowledgeSearchResult(val entries: List<KnowledgeEntry>, val totalCount: Int, val matchedTags: List<String>)

class OrganizationKnowledgeBase(private val context: Context) {
    companion object { private const val TAG = "OrgKnowledgeBase"; private const val KB_DIR = "knowledge_base"; private const val MAX_ENTRIES = 5000 }

    private val kbDir = File(context.filesDir, KB_DIR)
    private val entryCache = ConcurrentHashMap<String, KnowledgeEntry>()
    private val tagIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val categoryIndex = ConcurrentHashMap<KnowledgeCategory, MutableSet<String>>()
    private val recordCounter = AtomicLong(0)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = ReentrantReadWriteLock()

    init { if (!kbDir.exists()) kbDir.mkdirs(); loadPersistedEntries() }

    fun addEntry(title: String, category: KnowledgeCategory, tags: List<String>, scenario: String, options: List<String>, chosenOption: String, reason: String, relatedCodeLocations: List<String> = emptyList(), source: KnowledgeSource = KnowledgeSource.AGENT_DECISION): KnowledgeEntry {
        val id = "kb_${recordCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}"
        val entry = KnowledgeEntry(id = id, title = title, category = category, tags = tags, scenario = scenario, options = options, chosenOption = chosenOption, reason = reason, relatedCodeLocations = relatedCodeLocations, source = source)
        lock.writeLock().lock(); try { entryCache[id] = entry; tags.forEach { tagIndex.computeIfAbsent(it.lowercase()) { ConcurrentHashMap.newKeySet() }.add(id) }; categoryIndex.computeIfAbsent(category) { ConcurrentHashMap.newKeySet() }.add(id); evictIfNeeded() } finally { lock.writeLock().unlock() }
        persistEntry(entry); return entry
    }

    fun searchKnowledge(query: String, category: KnowledgeCategory? = null, tags: List<String>? = null, limit: Int = 50): KnowledgeSearchResult {
        lock.readLock().lock(); try {
            var candidateIds: Set<String>? = null
            if (tags != null && tags.isNotEmpty()) { for (tag in tags) { val ids = tagIndex[tag.lowercase()]; if (ids != null) candidateIds = if (candidateIds == null) ids.toSet() else candidateIds!!.intersect(ids) } }
            if (category != null) { val catIds = categoryIndex[category]?.toSet() ?: emptySet(); candidateIds = if (candidateIds != null) candidateIds!!.intersect(catIds) else catIds }
            val entries = (candidateIds ?: entryCache.keys).mapNotNull { entryCache[it] }.filter { entry -> if (query.isEmpty()) true else entry.title.lowercase().contains(query.lowercase()) || entry.scenario.lowercase().contains(query.lowercase()) || entry.reason.lowercase().contains(query.lowercase()) }.sortedByDescending { it.createdAt }.take(limit)
            return KnowledgeSearchResult(entries = entries, totalCount = entries.size, matchedTags = tags ?: emptyList())
        } finally { lock.readLock().unlock() }
    }

    fun extractFromDecision(decision: DecisionRecord): KnowledgeEntry? { val category = when (decision.action) { DecisionAction.EXECUTED -> KnowledgeCategory.ARCHITECTURE; else -> return null }; if (decision.description.isBlank()) return null; val tags = mutableListOf(decision.agentRole.lowercase(), decision.action.name.lowercase()); decision.relatedFiles.forEach { val ext = it.substringAfterLast('.', ""); if (ext.isNotEmpty()) tags.add(ext) }; return addEntry(title = "Decision: ${decision.action.name} by ${decision.agentRole}", category = category, tags = tags.distinct(), scenario = decision.description, options = decision.details.entries.map { "${it.key}=${it.value}" }, chosenOption = decision.details["chosen"] ?: decision.action.name, reason = decision.details["reason"] ?: decision.description, relatedCodeLocations = decision.relatedFiles, source = KnowledgeSource.AGENT_DECISION) }
    fun getCategories(): List<String> = KnowledgeCategory.values().map { it.name }
    fun exportKnowledge(): String { lock.readLock().lock(); try { return JSONArray(entryCache.values.sortedByDescending { it.createdAt }.map { it.toJson() }).toString(2) } finally { lock.readLock().unlock() } }

    private fun evictIfNeeded() { if (entryCache.size > MAX_ENTRIES) { val oldest = entryCache.values.sortedBy { it.createdAt }.take(entryCache.size - MAX_ENTRIES); oldest.forEach { entry -> entryCache.remove(entry.id); entry.tags.forEach { tagIndex[it.lowercase()]?.remove(entry.id) }; categoryIndex[entry.category]?.remove(entry.id); File(kbDir, "${entry.id}.json").delete() } } }
    private fun persistEntry(entry: KnowledgeEntry) { executor.execute { try { File(kbDir, "${entry.id}.json").writeText(entry.toJson().toString(2)) } catch (e: Exception) { Log.e(TAG, "Failed to persist", e) } } }
    private fun loadPersistedEntries() { kbDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file -> try { val entry = KnowledgeEntry.fromJson(JSONObject(file.readText())); entryCache[entry.id] = entry; entry.tags.forEach { tagIndex.computeIfAbsent(it.lowercase()) { ConcurrentHashMap.newKeySet() }.add(entry.id) }; categoryIndex.computeIfAbsent(entry.category) { ConcurrentHashMap.newKeySet() }.add(entry.id) } catch (e: Exception) { Log.e(TAG, "Failed: ${file.name}", e) } } }
    fun shutdown() { executor.shutdown(); try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow() } catch (e: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() } }
}