package com.aiide

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class Symbol(
    val name: String,
    val type: SymbolType,
    val filePath: String,
    val line: Int,
    val column: Int,
    val signature: String,
    val references: MutableList<SymbolReference> = mutableListOf()
)

data class SymbolReference(
    val filePath: String,
    val line: Int,
    val column: Int,
    val context: String
)

enum class SymbolType { CLASS, FUNCTION, VARIABLE, TYPE, PACKAGE, INTERFACE, ENUM }

data class FileContext(
    val filePath: String,
    val imports: List<String>,
    val symbols: List<Symbol>,
    val usages: List<SymbolReference>
)

class CrossFileContextEngine(private val context: Context) {
    private val symbolIndex = ConcurrentHashMap<String, MutableList<Symbol>>()
    private val fileContexts = ConcurrentHashMap<String, FileContext>()
    private val referenceGraph = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        loadIndex()
    }

    private fun loadIndex() {
        try {
            val prefs = context.getSharedPreferences("symbol_index", Context.MODE_PRIVATE)
            prefs.getString("symbols", null)?.let {
                val json = org.json.JSONArray(it)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val symbol = Symbol(
                        name = obj.getString("name"),
                        type = SymbolType.valueOf(obj.getString("type")),
                        filePath = obj.getString("filePath"),
                        line = obj.getInt("line"),
                        column = obj.getInt("column"),
                        signature = obj.getString("signature")
                    )
                    symbolIndex.getOrPut(symbol.name, { mutableListOf() }).add(symbol)
                }
            }
        } catch (e: Exception) {
            Log.e("CrossFileContext", "Failed to load index: ${e.message}")
        }
    }

    private fun saveIndex() {
        try {
            val prefs = context.getSharedPreferences("symbol_index", Context.MODE_PRIVATE)
            val json = org.json.JSONArray()
            symbolIndex.values.flatten().forEach { symbol ->
                json.put(JSONObject().apply {
                    put("name", symbol.name)
                    put("type", symbol.type.name)
                    put("filePath", symbol.filePath)
                    put("line", symbol.line)
                    put("column", symbol.column)
                    put("signature", symbol.signature)
                })
            }
            prefs.edit().putString("symbols", json.toString()).apply()
        } catch (e: Exception) {
            Log.e("CrossFileContext", "Failed to save index: ${e.message}")
        }
    }

    fun indexFile(content: String, filePath: String) {
        val symbols = extractSymbols(content, filePath)
        val imports = extractImports(content)
        val usages = extractUsages(content, filePath)
        
        symbols.forEach { symbol ->
            symbolIndex.getOrPut(symbol.name, { mutableListOf() })
                .removeAll { it.filePath == filePath }
            symbolIndex[symbol.name]?.add(symbol)
        }
        
        fileContexts[filePath] = FileContext(
            filePath = filePath,
            imports = imports,
            symbols = symbols,
            usages = usages
        )
        
        updateReferenceGraph(filePath, usages)
        saveIndex()
    }

    private fun extractSymbols(content: String, filePath: String): List<Symbol> {
        val symbols = mutableListOf<Symbol>()
        val lines = content.split("\n")
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            when {
                trimmed.startsWith("class ") -> {
                    val match = Regex("class\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.CLASS,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
                trimmed.startsWith("fun ") || trimmed.startsWith("private fun ") -> {
                    val match = Regex("(?:private\\s+)?fun\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.FUNCTION,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
                trimmed.startsWith("data class ") -> {
                    val match = Regex("data\\s+class\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.CLASS,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
                trimmed.startsWith("interface ") -> {
                    val match = Regex("interface\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.INTERFACE,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
                trimmed.startsWith("enum class ") -> {
                    val match = Regex("enum\\s+class\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.ENUM,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
                trimmed.startsWith("val ") || trimmed.startsWith("var ") -> {
                    val match = Regex("(?:val|var)\\s+(\\w+)").find(trimmed)
                    match?.let {
                        symbols.add(Symbol(
                            name = it.groupValues[1],
                            type = SymbolType.VARIABLE,
                            filePath = filePath,
                            line = index + 1,
                            column = trimmed.indexOf(it.groupValues[1]),
                            signature = trimmed
                        ))
                    }
                }
            }
        }
        
        return symbols
    }

    private fun extractImports(content: String): List<String> {
        return Regex("""import\\s+([a-zA-Z0-9_.]+)""")
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun extractUsages(content: String, filePath: String): List<SymbolReference> {
        val usages = mutableListOf<SymbolReference>()
        val lines = content.split("\n")
        
        lines.forEachIndexed { index, line ->
            Regex("\\b(\\w+)\\b").findAll(line).forEach { match ->
                val name = match.groupValues[1]
                if (symbolIndex.containsKey(name) && symbolIndex[name]?.none { it.filePath == filePath } == true) {
                    usages.add(SymbolReference(
                        filePath = filePath,
                        line = index + 1,
                        column = match.range.first,
                        context = line.trim()
                    ))
                }
            }
        }
        
        return usages
    }

    private fun updateReferenceGraph(filePath: String, usages: List<SymbolReference>) {
        val symbolsInFile = symbolIndex.values.flatten().filter { it.filePath == filePath }
        
        symbolsInFile.forEach { symbol ->
            val key = "$filePath:${symbol.name}"
            usages.forEach { usage ->
                referenceGraph.getOrPut(key, { mutableSetOf() }).add(usage.filePath)
            }
        }
    }

    fun getCrossFileContext(filePath: String, cursorPos: Int): FileContext? {
        return fileContexts[filePath]
    }

    fun findSymbol(name: String): List<Symbol> {
        return symbolIndex[name] ?: emptyList()
    }

    fun findReferences(symbolName: String): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()
        
        symbolIndex[symbolName]?.forEach { symbol ->
            references.addAll(symbol.references)
        }
        
        return references
    }

    fun getCallGraph(filePath: String): Map<String, List<String>> {
        val calls = mutableMapOf<String, MutableList<String>>()
        
        fileContexts[filePath]?.symbols?.forEach { symbol ->
            val key = "$filePath:${symbol.name}"
            referenceGraph[key]?.forEach { callee ->
                calls.getOrPut(symbol.name, { mutableListOf() }).add(callee)
            }
        }
        
        return calls
    }

    fun getDependentFiles(filePath: String): List<String> {
        val dependents = mutableSetOf<String>()
        
        referenceGraph.entries.forEach { (key, refs) ->
            if (refs.contains(filePath) && key.startsWith(filePath).not()) {
                dependents.add(key.substringBefore(":"))
            }
        }
        
        return dependents.toList()
    }

    fun searchByType(type: SymbolType): List<Symbol> {
        return symbolIndex.values.flatten().filter { it.type == type }
    }

    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalSymbols" to symbolIndex.values.sumOf { it.size },
            "totalFiles" to fileContexts.size,
            "referenceCount" to referenceGraph.size,
            "typeDistribution" to symbolIndex.values.flatten().groupBy { it.type }.mapValues { it.value.size }
        )
    }
}
