package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class DecisionAction { STARTED, CLASSIFIED, CONFIRMED, EXECUTED, VERIFIED, DELIVERED, FAILED }

data class DecisionRecord(
    val id: String, val timestamp: Long, val agentId: String, val agentRole: String,
    val action: DecisionAction, val description: String, val details: Map<String, String> = emptyMap(),
    val relatedFiles: List<String> = emptyList(), val parentDecisionId: String? = null, val cycleId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("agent_id", agentId); put("agent_role", agentRole)
        put("action", action.name); put("description", description); put("details", JSONObject(details))
        put("related_files", JSONArray(relatedFiles)); parentDecisionId?.let { put("parent_decision_id", it) }; cycleId?.let { put("cycle_id", it) }
    }
    companion object { fun fromJson(json: JSONObject): DecisionRecord = DecisionRecord(
        id = json.optString("id", ""), timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        agentId = json.optString("agent_id", ""), agentRole = json.optString("agent_role", ""),
        action = DecisionAction.valueOf(json.optString("action", DecisionAction.STARTED.name)),
        description = json.optString("description", ""),
        details = json.optJSONObject("details")?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it, "") } } ?: emptyMap(),
        relatedFiles = (0 until json.optJSONArray("related_files")?.length() ?: 0).map { json.optJSONArray("related_files").optString(it) },
        parentDecisionId = json.optString("parent_decision_id").ifEmpty { null },
        cycleId = json.optString("cycle_id").ifEmpty { null }
    )}
}

data class TimelineFilter(val agentRole: String? = null, val action: DecisionAction? = null, val fromTime: Long? = null, val toTime: Long? = null, val relatedFile: String? = null, val searchQuery: String? = null)
data class TimelinePage(val records: List<DecisionRecord>, val totalCount: Int, val hasMore: Boolean)

class AgentDecisionTimelineEngine(private val context: Context) {
    companion object { private const val TAG = "DecisionTimeline"; private const val MAX_RECORDS = 10000; private const val TIMELINE_DIR = "decision_timeline" }

    private val timelineDir = File(context.filesDir, TIMELINE_DIR)
    private val recordCache = ConcurrentHashMap<String, DecisionRecord>()
    private val sortedIndex = CopyOnWriteArrayList<String>()
    private val fileIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val cycleIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val recordCounter = AtomicLong(0)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = ReentrantReadWriteLock()

    init { if (!timelineDir.exists()) timelineDir.mkdirs(); loadPersistedRecords() }

    fun recordDecision(agentId: String, agentRole: String, action: DecisionAction, description: String, details: Map<String, String> = emptyMap(), relatedFiles: List<String> = emptyList(), cycleId: String? = null): DecisionRecord {
        val id = "dec_${recordCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(6)}"
        val record = DecisionRecord(id = id, timestamp = System.currentTimeMillis(), agentId = agentId, agentRole = agentRole, action = action, description = description, details = details, relatedFiles = relatedFiles, cycleId = cycleId)
        lock.writeLock().lock(); try {
            recordCache[id] = record; sortedIndex.add(id)
            for (file in relatedFiles) fileIndex.computeIfAbsent(file) { ConcurrentHashMap.newKeySet() }.add(id)
            if (cycleId != null) cycleIndex.computeIfAbsent(cycleId) { ConcurrentHashMap.newKeySet() }.add(id)
            evictIfNeeded()
        } finally { lock.writeLock().unlock() }
        persistRecord(record); return record
    }

    fun queryTimeline(filter: TimelineFilter, page: Int, pageSize: Int): TimelinePage {
        lock.readLock().lock(); try {
            val filtered = sortedIndex.mapNotNull { recordCache[it] }.filter { matchesFilter(it, filter) }.sortedByDescending { it.timestamp }
            val fromIndex = page * pageSize
            return TimelinePage(records = filtered.subList(fromIndex.coerceAtMost(filtered.size), minOf(fromIndex + pageSize, filtered.size)), totalCount = filtered.size, hasMore = fromIndex + pageSize < filtered.size)
        } finally { lock.readLock().unlock() }
    }

    fun getDecisionChain(decisionId: String): List<DecisionRecord> {
        val chain = mutableListOf<DecisionRecord>(); val visited = mutableSetOf<String>(); var currentId: String? = decisionId
        while (currentId != null && visited.add(currentId)) { val record = recordCache[currentId] ?: break; chain.add(0, record); currentId = record.parentDecisionId }
        return chain
    }

    fun getRecentDecisions(count: Int): List<DecisionRecord> { lock.readLock().lock(); try { return sortedIndex.takeLast(count).mapNotNull { recordCache[it] }.sortedByDescending { it.timestamp } } finally { lock.readLock().unlock() } }
    fun getDecisionsForFile(filePath: String): List<DecisionRecord> = fileIndex[filePath]?.mapNotNull { recordCache[it] }?.sortedByDescending { it.timestamp } ?: emptyList()
    fun exportTimeline(fromTime: Long?, toTime: Long?): String { lock.readLock().lock(); try { val records = sortedIndex.mapNotNull { recordCache[it] }.filter { (fromTime == null || it.timestamp >= fromTime) && (toTime == null || it.timestamp <= toTime) }.sortedByDescending { it.timestamp }; return JSONArray(records.map { it.toJson() }).toString(2) } finally { lock.readLock().unlock() } }

    private fun matchesFilter(record: DecisionRecord, filter: TimelineFilter): Boolean {
        if (filter.agentRole != null && record.agentRole != filter.agentRole) return false
        if (filter.action != null && record.action != filter.action) return false
        if (filter.fromTime != null && record.timestamp < filter.fromTime) return false
        if (filter.toTime != null && record.timestamp > filter.toTime) return false
        if (filter.relatedFile != null && record.relatedFiles.none { it.contains(filter.relatedFile) }) return false
        if (filter.searchQuery != null && !record.description.lowercase().contains(filter.searchQuery.lowercase())) return false
        return true
    }

    private fun evictIfNeeded() { if (recordCache.size > MAX_RECORDS) { val toRemove = recordCache.size - MAX_RECORDS; val oldestIds = sortedIndex.take(toRemove); for (id in oldestIds) { val record = recordCache.remove(id); sortedIndex.remove(id); record?.relatedFiles?.forEach { fileIndex[it]?.remove(id) }; if (record?.cycleId != null) cycleIndex[record.cycleId]?.remove(id); File(timelineDir, "$id.json").delete() } } }
    private fun persistRecord(record: DecisionRecord) { executor.execute { try { File(timelineDir, "${record.id}.json").writeText(record.toJson().toString(2)) } catch (e: Exception) { Log.e(TAG, "Failed to persist", e) } } }
    private fun loadPersistedRecords() { timelineDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file -> try { val record = DecisionRecord.fromJson(JSONObject(file.readText())); recordCache[record.id] = record; sortedIndex.add(record.id); record.relatedFiles.forEach { fileIndex.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(record.id) }; if (record.cycleId != null) cycleIndex.computeIfAbsent(record.cycleId) { ConcurrentHashMap.newKeySet() }.add(record.id) } catch (e: Exception) { Log.e(TAG, "Failed to load: ${file.name}", e) } } }
    fun shutdown() { executor.shutdown(); try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow() } catch (e: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() } }
}