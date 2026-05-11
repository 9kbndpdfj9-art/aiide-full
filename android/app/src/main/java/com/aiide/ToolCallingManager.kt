package com.aiide

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class ToolCallingManager(
    private val getProjectDir: () -> File,
    private val validatePath: (String) -> Boolean,
    private val eventLogger: EventLogger
) {
    companion object {
        const val MAX_TOOL_ITERATIONS = 10
        const val MAX_OUTPUT_SIZE = 50000
        private const val MAX_SEARCH_DEPTH = 20
        private const val MAX_FILE_SIZE_FOR_READ = 1024 * 1024
    }

    private var toolDefinitionsCache: JSONArray? = null

    private fun safeResolvePath(path: String): File {
        val projectRoot = getProjectDir().canonicalFile
        val targetFile = projectRoot.resolve(path).canonicalFile
        if (!targetFile.path.startsWith(projectRoot.path)) {
            throw SecurityException("Path traversal detected: $path")
        }
        return targetFile
    }

    fun getToolDefinitions(): JSONArray {
        toolDefinitionsCache?.let { return JSONArray(it.toString()) }

        val tools = JSONArray()
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "readFile")
                put("description", "Read file contents")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray(arrayOf("path")))
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                        })
                    })
                })
            })
        })

        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "writeFile")
                put("description", "Write content to file")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray(arrayOf("path", "content")))
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply { put("type", "string") })
                        put("content", JSONObject().apply { put("type", "string") })
                    })
                })
            })
        })

        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "listFiles")
                put("description", "List directory contents")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply { put("type", "string") })
                    })
                })
            })
        })

        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "searchFiles")
                put("description", "Search for files matching pattern")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray(arrayOf("pattern")))
                    put("properties", JSONObject().apply {
                        put("pattern", JSONObject().apply { put("type", "string") })
                        put("path", JSONObject().apply { put("type", "string") })
                    })
                })
            })
        })

        toolDefinitionsCache = JSONArray(tools.toString())
        return JSONArray(tools.toString())
    }

    fun executeToolCall(toolCall: JSONObject): JSONObject {
        val function = toolCall.optJSONObject("function") ?: return toolResult("Invalid tool call format", isError = true)
        val name = function.optString("name", "")
        val argsStr = function.optString("arguments", "{}")

        val args = try {
            if (argsStr.isBlank()) JSONObject() else JSONObject(argsStr)
        } catch (e: Exception) {
            return toolResult("Invalid arguments JSON: ${e.message}", isError = true)
        }

        return try {
            when (name) {
                "readFile" -> toolReadFile(args)
                "writeFile" -> toolWriteFile(args)
                "listFiles" -> toolListFiles(args)
                "searchFiles" -> toolSearchFiles(args)
                else -> toolResult("Unknown tool: $name", isError = true)
            }
        } catch (e: SecurityException) {
            toolResult("Security violation: ${e.message}", isError = true)
        } catch (e: Exception) {
            toolResult("Tool execution error: ${e.message}", isError = true)
        }
    }

    private fun toolReadFile(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        if (path.isEmpty()) return toolResult("Path is required", isError = true)
        if (!validatePath(path)) return toolResult("Invalid path", isError = true)

        val file = safeResolvePath(path)
        if (!file.exists()) return toolResult("File not found: $path", isError = true)
        if (!file.isFile) return toolResult("Not a file: $path", isError = true)
        if (file.length() > MAX_FILE_SIZE_FOR_READ) return toolResult("File too large", isError = true)

        val content = try { file.readText() } catch (e: Exception) {
            return toolResult("Failed to read file: ${e.message}", isError = true)
        }

        val truncated = if (content.length > MAX_OUTPUT_SIZE) {
            content.take(MAX_OUTPUT_SIZE) + "\n...(output truncated)"
        } else content

        eventLogger.logFileEdit(path, "read", 0, 0)
        return toolResult(truncated, truncate = false)
    }

    private fun toolWriteFile(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val content = args.optString("content", "")
        if (path.isEmpty()) return toolResult("Path is required", isError = true)
        if (!validatePath(path)) return toolResult("Invalid path", isError = true)

        val file = safeResolvePath(path)
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        try { file.writeText(content) } catch (e: Exception) {
            return toolResult("Failed to write file: ${e.message}", isError = true)
        }

        val lineCount = content.count { it == '\n' } + 1
        eventLogger.logFileEdit(path, "ai_generate", lineCount, 0)
        return toolResult("Successfully wrote ${content.length} characters to $path")
    }

    private fun toolListFiles(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        if (path.isNotEmpty() && !validatePath(path)) return toolResult("Invalid path", isError = true)

        val dir = if (path.isEmpty()) getProjectDir() else safeResolvePath(path)
        if (!dir.exists() || !dir.isDirectory) return toolResult("Directory not found", isError = true)

        val files = dir.listFiles() ?: return toolResult("Cannot read directory", isError = true)
        val result = StringBuilder()
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { file ->
            val icon = if (file.isDirectory) "[DIR]" else "[FILE]"
            result.append("$icon ${file.name}\n")
        }
        return toolResult(if (result.isEmpty()) "Empty directory" else result.toString(), truncate = true)
    }

    private fun toolSearchFiles(args: JSONObject): JSONObject {
        val pattern = args.optString("pattern", "")
        val basePath = args.optString("path", "")
        if (pattern.isEmpty()) return toolResult("Pattern is required", isError = true)

        val searchDir = if (basePath.isNotEmpty()) {
            if (!validatePath(basePath)) return toolResult("Invalid base path", isError = true)
            safeResolvePath(basePath)
        } else getProjectDir()

        if (!searchDir.exists() || !searchDir.isDirectory) {
            return toolResult("Base directory not found", isError = true)
        }

        val results = mutableListOf<String>()
        collectMatchingFiles(searchDir, "", pattern, results, limit = 100, depth = 0)
        return if (results.isEmpty()) {
            toolResult("No files matching '$pattern'")
        } else {
            toolResult(results.joinToString("\n"), truncate = true)
        }
    }

    private fun collectMatchingFiles(
        dir: File, relativePath: String, pattern: String,
        results: MutableList<String>, limit: Int, depth: Int
    ) {
        if (results.size >= limit || depth >= MAX_SEARCH_DEPTH) return
        val files = dir.listFiles() ?: return
        files.forEach { file ->
            if (results.size >= limit) return
            if (file.isDirectory) {
                if (!file.name.startsWith(".")) {
                    collectMatchingFiles(file, if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}", pattern, results, limit, depth + 1)
                }
            } else {
                val fullPath = if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}"
                if (matchesGlob(fullPath, pattern)) {
                    results.add(fullPath)
                }
            }
        }
    }

    private fun matchesGlob(filePath: String, pattern: String): Boolean {
        val regexPattern = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '.' -> regexPattern.append("\\.")
                '?' -> regexPattern.append("[^/]")
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        regexPattern.append(".*")
                        i += 2
                        continue
                    }
                    regexPattern.append("[^/]*")
                }
                else -> regexPattern.append(pattern[i])
            }
            i++
        }
        return regexPattern.toString().toRegex().matches(filePath)
    }

    private fun toolResult(content: String, isError: Boolean = false, truncate: Boolean = false): JSONObject {
        val truncated = if (truncate && content.length > MAX_OUTPUT_SIZE) {
            content.take(MAX_OUTPUT_SIZE) + "\n...(output truncated)"
        } else content
        return JSONObject().apply {
            put("content", truncated)
            if (isError) put("isError", true)
        }
    }

    fun handleToolCall(toolName: String, arguments: Map<String, String>): JSONObject {
        val args = JSONObject()
        arguments.forEach { (key, value) -> args.put(key, value) }
        return try {
            when (toolName) {
                "readFile" -> toolReadFile(args)
                "writeFile" -> toolWriteFile(args)
                "listFiles" -> toolListFiles(args)
                "searchFiles" -> toolSearchFiles(args)
                else -> toolResult("Unknown tool: $toolName", isError = true)
            }
        } catch (e: Exception) {
            toolResult("Tool execution error: ${e.message}", isError = true)
        }
    }

    fun validateToolCall(toolName: String, arguments: JSONObject): String? {
        val definition = getToolDefinition(toolName) ?: return "Tool '$toolName' not found"
        val function = definition.optJSONObject("function") ?: return "Invalid tool definition"
        val parameters = function.optJSONObject("parameters") ?: return null
        val required = parameters.optJSONArray("required")

        if (required != null) {
            for (i in 0 until required.length()) {
                val reqName = required.getString(i)
                if (!arguments.has(reqName) || arguments.optString(reqName).isEmpty()) {
                    return "Missing required parameter: $reqName"
                }
            }
        }
        return null
    }

    fun getToolDefinition(name: String): JSONObject? {
        val allTools = getToolDefinitions()
        for (i in 0 until allTools.length()) {
            val tool = allTools.getJSONObject(i)
            val function = tool.optJSONObject("function")
            if (function?.optString("name") == name) {
                return JSONObject(tool.toString())
            }
        }
        return null
    }
}
