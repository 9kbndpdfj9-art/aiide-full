package com.aiide

import android.content.Context
import android.util.Log
import org.json.*
import java.io.File
import java.util.*
import java.util.concurrent.*

enum class SemanticNodeType { MODULE, SERVICE, UTIL }
enum class HealthStatus { GREEN, YELLOW, RED, PURPLE }
enum class SemanticEdgeType { DEPENDS_ON, CALLS, IMPLEMENTS }

data class FingerprintSummary(
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    val constraints: List<String> = emptyList()
)

data class SemanticNode(
    val id: String,
    val name: String,
    val type: SemanticNodeType,
    val health: HealthStatus,
    val fingerprintSummary: FingerprintSummary = FingerprintSummary(),
    val recentChanges: List<String> = emptyList(),
    val linkedDecisions: List<String> = emptyList()
)

data class SemanticEdge(
    val fromId: String,
    val toId: String,
    val type: SemanticEdgeType,
    val label: String = ""
)

data class SemanticMap(
    val nodes: List<SemanticNode>,
    val edges: List<SemanticEdge>,
    val generatedAt: Long = System.currentTimeMillis()
)

class ProjectSemanticMapEngine(
    private val context: Context,
    private val semanticShadowEngine: SemanticShadowEngine
) {
    companion object {
        private const val TAG = "ProjectSemanticMap"
        private const val MAP_DIR = "semantic_map"
        private const val MAP_TTL_MS = 120_000L
    }

    private val mapDir = File(context.filesDir, MAP_DIR)
    private val nodeCache = ConcurrentHashMap<String, SemanticNode>()
    private val edgeCache = ConcurrentHashMap<String, SemanticEdge>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    @Volatile
    private var cachedMap: SemanticMap? = null
    @Volatile
    private var mapGeneratedAt: Long = 0

    init {
        if (!mapDir.exists()) mapDir.mkdirs()
        loadPersistedMap()
    }

    fun generateMap(): SemanticMap {
        val cached = cachedMap
        if (cached != null && System.currentTimeMillis() - mapGeneratedAt < MAP_TTL_MS) {
            return cached
        }
        val nodes = buildNodesFromShadow()
        val edges = buildEdgesFromShadow()
        val map = SemanticMap(nodes = nodes, edges = edges)
        cachedMap = map
        mapGeneratedAt = System.currentTimeMillis()
        persistMap(map)
        return map
    }

    fun getNodeDetails(nodeId: String): SemanticNode? {
        val fromCache = nodeCache[nodeId]
        if (fromCache != null) return fromCache
        return cachedMap?.nodes?.find { it.id == nodeId }
    }

    fun getModuleHealth(moduleName: String): HealthStatus {
        return cachedMap?.nodes?.find { it.name == moduleName }?.health ?: HealthStatus.GREEN
    }

    fun searchNodes(query: String): List<SemanticNode> {
        val map = cachedMap ?: generateMap()
        val lowerQuery = query.lowercase(Locale.getDefault())
        return map.nodes.filter { node ->
            node.name.lowercase(Locale.getDefault()).contains(lowerQuery)
        }
    }

    fun getDependenciesOf(nodeId: String): List<SemanticEdge> {
        return (cachedMap ?: generateMap()).edges.filter { it.fromId == nodeId || it.toId == nodeId }
    }

    fun refreshMap(): SemanticMap {
        nodeCache.clear()
        edgeCache.clear()
        cachedMap = null
        return generateMap()
    }

    private fun buildNodesFromShadow(): List<SemanticNode> {
        val nodes = mutableListOf<SemanticNode>()
        val allStatuses = semanticShadowEngine.getAllFileStatuses()
        val moduleGroups = allStatuses.groupBy { status -> extractModuleName(status.filePath) }

        for ((moduleName, statuses) in moduleGroups) {
            val nodeId = "node_${moduleName.hashCode().toUInt()}"
            val health = aggregateHealth(statuses.map { it.statusColor })
            val nodeType = inferNodeType(moduleName)
            val summary = FingerprintSummary(
                input = emptyList(),
                output = emptyList(),
                sideEffects = emptyList(),
                constraints = emptyList()
            )
            val node = SemanticNode(
                id = nodeId,
                name = moduleName,
                type = nodeType,
                health = health,
                fingerprintSummary = summary,
                recentChanges = emptyList(),
                linkedDecisions = emptyList()
            )
            nodes.add(node)
            nodeCache[nodeId] = node
        }
        return nodes
    }

    private fun buildEdgesFromShadow(): List<SemanticEdge> {
        return emptyList()
    }

    private fun extractModuleName(filePath: String): String {
        val parts = filePath.split("/", "\\")
        val srcIndex = parts.indexOfLast { it == "src" }
        if (srcIndex >= 0 && srcIndex + 2 < parts.size) {
            return parts[srcIndex + 2]
        }
        return parts.lastOrNull()?.substringBeforeLast('.') ?: "unknown"
    }

    private fun aggregateHealth(statusColors: List<ShadowStatusColor>): HealthStatus {
        if (statusColors.isEmpty()) return HealthStatus.GREEN
        if (statusColors.any { it == ShadowStatusColor.RED }) return HealthStatus.RED
        if (statusColors.any { it == ShadowStatusColor.PURPLE }) return HealthStatus.PURPLE
        if (statusColors.any { it == ShadowStatusColor.YELLOW }) return HealthStatus.YELLOW
        return HealthStatus.GREEN
    }

    private fun inferNodeType(moduleName: String): SemanticNodeType {
        val lower = moduleName.lowercase(Locale.getDefault())
        return when {
            lower.contains("service") || lower.contains("api") -> SemanticNodeType.SERVICE
            lower.contains("util") || lower.contains("helper") -> SemanticNodeType.UTIL
            else -> SemanticNodeType.MODULE
        }
    }

    private fun persistMap(map: SemanticMap) {
        executor.execute {
            try {
                val mapFile = File(mapDir, "semantic_map.json")
                mapFile.writeText(JSONObject().apply {
                    put("nodes", JSONArray(map.nodes.map { JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("type", it.type.name)
                        put("health", it.health.name)
                    } }))
                    put("edges", JSONArray(map.edges.map { JSONObject().apply {
                        put("fromId", it.fromId)
                        put("toId", it.toId)
                        put("type", it.type.name)
                        put("label", it.label)
                    } }))
                    put("generatedAt", map.generatedAt)
                }.toString(2))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist semantic map", e)
            }
        }
    }

    private fun loadPersistedMap() {
        try {
            val mapFile = File(mapDir, "semantic_map.json")
            if (mapFile.exists()) {
                val json = JSONObject(mapFile.readText())
                val nodesArr = json.optJSONArray("nodes") ?: JSONArray()
                val edgesArr = json.optJSONArray("edges") ?: JSONArray()
                val nodes = mutableListOf<SemanticNode>()
                val edges = mutableListOf<SemanticEdge>()

                for (i in 0 until nodesArr.length()) {
                    val obj = nodesArr.optJSONObject(i) ?: continue
                    nodes.add(SemanticNode(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        type = SemanticNodeType.valueOf(obj.optString("type", "MODULE")),
                        health = HealthStatus.valueOf(obj.optString("health", "GREEN"))
                    ))
                }

                for (i in 0 until edgesArr.length()) {
                    val obj = edgesArr.optJSONObject(i) ?: continue
                    edges.add(SemanticEdge(
                        fromId = obj.optString("fromId", ""),
                        toId = obj.optString("toId", ""),
                        type = SemanticEdgeType.valueOf(obj.optString("type", "DEPENDS_ON")),
                        label = obj.optString("label", "")
                    ))
                }

                cachedMap = SemanticMap(nodes, edges, json.optLong("generatedAt", System.currentTimeMillis()))
                nodes.forEach { nodeCache[it.id] = it }
                edges.forEach { edgeCache["${it.fromId}_${it.toId}"] = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted semantic map", e)
        }
    }

    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

enum class ShadowStatusColor { GREEN, YELLOW, RED, PURPLE }

data class FileStatus(
    val filePath: String,
    val statusColor: ShadowStatusColor,
    val overallHealth: Double = 1.0
)

data class Fingerprint(
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

class SemanticShadowEngine {
    private val fileStatuses = mutableMapOf<String, FileStatus>()
    private val fingerprintHistory = mutableMapOf<String, MutableList<Fingerprint>>()
    private val dependentFunctions = mutableMapOf<String, MutableList<Pair<String, String>>>()

    fun getAllFileStatuses(): List<FileStatus> = fileStatuses.values.toList()

    fun getFingerprintHistory(filePath: String, pattern: String): List<Fingerprint> {
        return fingerprintHistory[filePath] ?: emptyList()
    }

    fun getDependentFunctions(filePath: String, pattern: String): List<Pair<String, String>> {
        return dependentFunctions[filePath] ?: emptyList()
    }

    fun addFileStatus(filePath: String, color: ShadowStatusColor, health: Double) {
        fileStatuses[filePath] = FileStatus(filePath, color, health)
    }

    fun addFingerprint(filePath: String, description: String) {
        fingerprintHistory.getOrPut(filePath) { mutableListOf() }.add(Fingerprint(description))
    }

    fun addDependentFunction(filePath: String, dependentFile: String, functionName: String) {
        dependentFunctions.getOrPut(filePath) { mutableListOf() }.add(Pair(dependentFile, functionName))
    }
}
