package com.aiide

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class McpServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val autoConnect: Boolean = false
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("command", command)
        put("args", JSONArray(args))
        put("env", JSONObject(env))
        put("enabled", enabled)
        put("autoConnect", autoConnect)
    }
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val serverId: String
)

data class McpToolResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

data class McpResource(
    val uri: String,
    val name: String,
    val mimeType: String,
    val serverId: String
)

data class McpPrompt(
    val name: String,
    val description: String,
    val arguments: List<Map<String, String>>,
    val serverId: String
)

class McpProtocolManager(
    private val context: Context,
    private val eventLogger: EventLogger? = null
) {
    private val servers = ConcurrentHashMap<String, McpServerConfig>()
    private val connectedServers = ConcurrentHashMap<String, Job>()
    private val serverCapabilities = ConcurrentHashMap<String, ServerCapabilities>()
    private val toolCache = ConcurrentHashMap<String, List<McpTool>>()
    private val resourceCache = ConcurrentHashMap<String, List<McpResource>>()
    private val promptCache = ConcurrentHashMap<String, List<McpPrompt>>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<McpToolResult>>()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        loadServers()
        connectAutoServers()
    }

    private fun loadServers() {
        try {
            val prefs = context.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE)
            prefs.getString("servers", null)?.let {
                val json = JSONArray(it)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val config = McpServerConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        command = obj.getString("command"),
                        args = (0 until obj.getJSONArray("args").length()).map { j ->
                            obj.getJSONArray("args").getString(j)
                        },
                        env = obj.optJSONObject("env")?.let { envObj ->
                            envObj.keys().asSequence().associateWith { envObj.getString(it) }
                        } ?: emptyMap(),
                        enabled = obj.optBoolean("enabled", true),
                        autoConnect = obj.optBoolean("autoConnect", false)
                    )
                    servers[config.id] = config
                }
            }
        } catch (e: Exception) {
            Log.e("McpProtocol", "Failed to load servers: ${e.message}")
        }
    }

    private fun saveServers() {
        try {
            val prefs = context.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE)
            val json = JSONArray()
            servers.values.forEach { config ->
                json.put(config.toJSON())
            }
            prefs.edit().putString("servers", json.toString()).apply()
        } catch (e: Exception) {
            Log.e("McpProtocol", "Failed to save servers: ${e.message}")
        }
    }

    private fun connectAutoServers() {
        servers.values.filter { it.autoConnect && it.enabled }.forEach { config ->
            scope.launch {
                connectServer(config.id)
            }
        }
    }

    fun addServer(config: McpServerConfig): Result<McpServerConfig> {
        return try {
            servers[config.id] = config
            saveServers()
            
            eventLogger?.log("mcp_server_added", mapOf(
                "serverId" to config.id,
                "serverName" to config.name
            ))
            
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateServer(serverId: String, updates: Map<String, Any>): Result<McpServerConfig> {
        val existing = servers[serverId]
            ?: return Result.failure(IllegalArgumentException("Server not found"))
        
        val updated = existing.copy(
            name = updates["name"] as? String ?: existing.name,
            command = updates["command"] as? String ?: existing.command,
            args = updates["args"] as? List<String> ?: existing.args,
            env = updates["env"] as? Map<String, String> ?: existing.env,
            enabled = updates["enabled"] as? Boolean ?: existing.enabled,
            autoConnect = updates["autoConnect"] as? Boolean ?: existing.autoConnect
        )
        
        servers[serverId] = updated
        saveServers()
        
        return Result.success(updated)
    }

    fun removeServer(serverId: String): Result<Unit> {
        return try {
            disconnectServer(serverId)
            servers.remove(serverId)
            toolCache.remove(serverId)
            resourceCache.remove(serverId)
            promptCache.remove(serverId)
            saveServers()
            
            eventLogger?.log("mcp_server_removed", mapOf("serverId" to serverId))
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getServers(): List<McpServerConfig> = servers.values.toList()

    fun isServerConnected(serverId: String): Boolean = connectedServers.containsKey(serverId)

    suspend fun connectServer(serverId: String): Result<List<McpTool>> {
        val config = servers[serverId]
            ?: return Result.failure(IllegalArgumentException("Server not found"))
        
        if (connectedServers.containsKey(serverId)) {
            toolCache[serverId]?.let { return Result.success(it) }
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val initializeResult = initializeServer(config)
                
                if (initializeResult.isSuccess) {
                    val capabilities = initializeResult.getOrNull()!!
                    serverCapabilities[serverId] = capabilities
                    
                    val tools = capabilities.tools ?: emptyList()
                    toolCache[serverId] = tools
                    
                    val resources = capabilities.resources ?: emptyList()
                    resourceCache[serverId] = resources
                    
                    val prompts = capabilities.prompts ?: emptyList()
                    promptCache[serverId] = prompts
                    
                    val job = scope.launch {
                        maintainConnection(serverId, config)
                    }
                    connectedServers[serverId] = job
                    
                    eventLogger?.log("mcp_server_connected", mapOf(
                        "serverId" to serverId,
                        "toolCount" to tools.size
                    ))
                    
                    Result.success(tools)
                } else {
                    Result.failure(initializeResult.exceptionOrNull() ?: Exception("Initialization failed"))
                }
            } catch (e: Exception) {
                Log.e("McpProtocol", "Failed to connect server $serverId: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private suspend fun initializeServer(config: McpServerConfig): Result<ServerCapabilities> {
        return withContext(Dispatchers.IO) {
            delay(100)
            
            val capabilities = ServerCapabilities(
                tools = listOf(
                    McpTool(
                        name = "${config.name}_example",
                        description = "Example tool from ${config.name}",
                        inputSchema = JSONObject().put("type", "object"),
                        serverId = config.id
                    )
                ),
                resources = emptyList(),
                prompts = emptyList()
            )
            
            Result.success(capabilities)
        }
    }

    private suspend fun maintainConnection(serverId: String, config: McpServerConfig) {
        while (isActive && connectedServers.containsKey(serverId)) {
            try {
                delay(5000)
                
                if (!isServerHealthy(serverId)) {
                    Log.w("McpProtocol", "Server $serverId health check failed")
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e("McpProtocol", "Connection maintenance error for $serverId: ${e.message}")
            }
        }
    }

    private fun isServerHealthy(serverId: String): Boolean {
        return connectedServers.containsKey(serverId)
    }

    fun disconnectServer(serverId: String) {
        connectedServers[serverId]?.cancel()
        connectedServers.remove(serverId)
        
        eventLogger?.log("mcp_server_disconnected", mapOf("serverId" to serverId))
    }

    fun getTools(serverId: String? = null): List<McpTool> {
        return if (serverId != null) {
            toolCache[serverId] ?: emptyList()
        } else {
            toolCache.values.flatten()
        }
    }

    fun getResources(serverId: String? = null): List<McpResource> {
        return if (serverId != null) {
            resourceCache[serverId] ?: emptyList()
        } else {
            resourceCache.values.flatten()
        }
    }

    fun getPrompts(serverId: String? = null): List<McpPrompt> {
        return if (serverId != null) {
            promptCache[serverId] ?: emptyList()
        } else {
            promptCache.values.flatten()
        }
    }

    suspend fun callTool(
        toolName: String,
        arguments: JSONObject,
        serverId: String? = null
    ): Result<McpToolResult> {
        val targetServer = serverId ?: findToolServer(toolName)
            ?: return Result.failure(IllegalArgumentException("Tool not found: $toolName"))
        
        if (!connectedServers.containsKey(targetServer)) {
            connectServer(targetServer)
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val requestId = "req_${System.currentTimeMillis()}_${(0..9999).random()}"
                val deferred = CompletableDeferred<McpToolResult>()
                pendingRequests[requestId] = deferred
                
                sendRequest(targetServer, "tools/call", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                    put("requestId", requestId)
                })
                
                val result = withTimeout(30000) {
                    deferred.await()
                }
                
                pendingRequests.remove(requestId)
                
                eventLogger?.log("mcp_tool_called", mapOf(
                    "toolName" to toolName,
                    "serverId" to targetServer,
                    "success" to result.success
                ))
                
                Result.success(result)
            } catch (e: Exception) {
                Log.e("McpProtocol", "Tool call failed for $toolName: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private fun findToolServer(toolName: String): String? {
        toolCache.entries.forEach { (serverId, tools) ->
            if (tools.any { it.name == toolName }) {
                return serverId
            }
        }
        return null
    }

    private fun sendRequest(serverId: String, method: String, params: JSONObject) {
        Log.d("McpProtocol", "Sending $method to $serverId: $params")
    }

    suspend fun readResource(uri: String): Result<String> {
        val serverId = findResourceServer(uri)
            ?: return Result.failure(IllegalArgumentException("Resource not found: $uri"))
        
        return withContext(Dispatchers.IO) {
            delay(50)
            Result.success("Resource content for $uri")
        }
    }

    private fun findResourceServer(uri: String): String? {
        resourceCache.entries.forEach { (serverId, resources) ->
            if (resources.any { it.uri == uri }) {
                return serverId
            }
        }
        return null
    }

    suspend fun getPrompt(
        promptName: String,
        arguments: Map<String, String>,
        serverId: String? = null
    ): Result<String> {
        val targetServer = serverId ?: findPromptServer(promptName)
            ?: return Result.failure(IllegalArgumentException("Prompt not found: $promptName"))
        
        return withContext(Dispatchers.IO) {
            delay(50)
            Result.success("Prompt response for $promptName")
        }
    }

    private fun findPromptServer(promptName: String): String? {
        promptCache.entries.forEach { (serverId, prompts) ->
            if (prompts.any { it.name == promptName }) {
                return serverId
            }
        }
        return null
    }

    fun subscribeToNotifications(serverId: String, callback: (JSONObject) -> Unit) {
        Log.d("McpProtocol", "Subscribed to notifications from $serverId")
    }

    fun getServerInfo(serverId: String): Map<String, Any>? {
        val config = servers[serverId] ?: return null
        val connected = connectedServers.containsKey(serverId)
        val capabilities = serverCapabilities[serverId]
        
        return mapOf(
            "config" to config.toJSON().toMap(),
            "connected" to connected,
            "toolCount" to (toolCache[serverId]?.size ?: 0),
            "resourceCount" to (resourceCache[serverId]?.size ?: 0),
            "promptCount" to (promptCache[serverId]?.size ?: 0)
        )
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalServers" to servers.size,
            "connectedServers" to connectedServers.size,
            "totalTools" to toolCache.values.sumOf { it.size },
            "totalResources" to resourceCache.values.sumOf { it.size },
            "totalPrompts" to promptCache.values.sumOf { it.size },
            "pendingRequests" to pendingRequests.size
        )
    }

    fun destroy() {
        scope.cancel()
        connectedServers.values.forEach { it.cancel() }
        connectedServers.clear()
    }
}

data class ServerCapabilities(
    val tools: List<McpTool>? = null,
    val resources: List<McpResource>? = null,
    val prompts: List<McpPrompt>? = null
)
