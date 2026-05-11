package com.aiide

import org.json.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class CompressionLevel { FAST, BALANCED, MAX }

private val TOKEN_ESTIMATION_TABLE = mapOf("function_def" to 25, "class_def" to 40, "interface_def" to 30, "import_stmt" to 8, "variable_decl" to 12, "loop_stmt" to 15, "conditional" to 18, "try_catch" to 20, "return_stmt" to 10, "assignment" to 8, "method_call" to 12, "comment" to 6, "annotation" to 5, "enum_entry" to 4, "default" to 10)

data class SCTPLiteMessage(val version: String = "sctp-lite/v1", val intent: SCTPIntent, val boundaries: Map<String, SemanticFingerprintCompact> = emptyMap(), val depGraph: Map<String, List<String>> = emptyMap(), val scope: List<String> = emptyList(), val knownBadPatterns: List<String> = emptyList(), val estimatedTokenCount: Int = 0) {
    fun toCompactString(): String = buildString { appendLine("$version:"); appendLine("  intent:"); appendLine("    op: ${intent.operation}"); appendLine("    target: ${intent.target}"); appendLine("    case: ${intent.case}"); appendLine("    constraints: [${intent.constraints.joinToString(", ")}]") }
    fun toJson(): JSONObject = JSONObject().apply { put("version", version); put("intent", intent.toJson()); put("estimated_token_count", estimatedTokenCount) }
}

data class SCTPIntent(val operation: String, val target: String, val case: String, val constraints: List<String> = emptyList()) { fun toJson(): JSONObject = JSONObject().apply { put("operation", operation); put("target", target); put("case", case); put("constraints", JSONArray(constraints)) } }

data class SemanticFingerprintCompact(val id: String, val inputTypes: Map<String, String> = emptyMap(), val outputTypes: Map<String, String> = emptyMap(), val sideEffects: List<String> = emptyList(), val constraints: Map<String, String> = emptyMap(), val dependencies: List<String> = emptyList(), val dependedBy: List<String> = emptyList()) {
    fun toCompactString(): String = buildString { append("{"); if (inputTypes.isNotEmpty()) { append("i:{"); append(inputTypes.entries.joinToString(",") { "${it.key}:${it.value}" }); append("}") }; append("}") }
    fun estimatedTokenCount(): Int = toCompactString().length / 4
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("input_types", JSONObject(inputTypes)); put("output_types", JSONObject(outputTypes)); put("side_effects", JSONArray(sideEffects)); put("constraints", JSONObject(constraints)); put("dependencies", JSONArray(dependencies)); put("depended_by", JSONArray(dependedBy)) }
}

data class TypeFingerprint(val name: String, val fields: Map<String, TypeField> = emptyMap(), val hash: String = "") {
    fun toCompactString(): String = "t:$name{" + fields.entries.joinToString(",") { "${it.key}:${it.value.toCompactString()}" } + "}"
    fun estimatedTokenCount(): Int = toCompactString().length / 4
    fun computeHash(): String = MessageDigest.getInstance("SHA-256").digest(("$name|${fields.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value.type}" }}").toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    data class TypeField(val type: String, val optional: Boolean = false, val unionTypes: List<String> = emptyList()) { fun toCompactString(): String = if (unionTypes.isNotEmpty()) "$type|${unionTypes.joinToString("|")}" else type + if (optional) "?" else "" }
}

class TypeFingerprintCache { private val cache = ConcurrentHashMap<String, TypeFingerprint>(); private val references = ConcurrentHashMap<String, AtomicInteger>(); fun registerType(type: TypeFingerprint): String { val hash = type.computeHash(); if (!cache.containsKey(hash)) { cache[hash] = type.copy(hash = hash) }; references.computeIfAbsent(hash) { AtomicInteger(0) }.incrementAndGet(); return hash }; fun getTypeByHash(hash: String): TypeFingerprint? = cache[hash]; fun getTypeReference(hash: String): String = "t:$hash"; fun getCacheSize(): Int = cache.size; fun getTotalReferences(): Int = references.values.sumOf { it.get() }; fun clear() { cache.clear(); references.clear() } }

data class DialogueState(val confirmed: List<String> = emptyList(), val pending: List<String> = emptyList(), val rejectedPaths: List<DialoguePath> = emptyList(), val currentConstraints: Map<String, Any> = emptyMap(), val estimatedTokenCount: Int = 0) {
    data class DialoguePath(val attempt: String, val summary: String, val rejected: Boolean) { fun toJson(): JSONObject = JSONObject().apply { put("attempt", attempt); put("summary", summary); put("rejected", rejected) } }
    fun toJson(): JSONObject = JSONObject().apply { put("confirmed", JSONArray(confirmed)); put("pending", JSONArray(pending)); put("rejected_paths", JSONArray(rejectedPaths.map { it.toJson() })); put("current_constraints", JSONObject(currentConstraints.mapValues { it.value.toString() })); put("estimated_token_count", estimatedTokenCount) }
}

data class BuildSummary(val success: Boolean, val duration: String = "", val testsAdded: Int = 0, val testsRemoved: Int = 0, val testsFailed: Int = 0, val coverage: Double = 0.0, val errors: List<BuildError> = emptyList()) {
    fun toCompactString(): String = if (success) "build:ok\nduration:${duration}\ntests:+$testsAdded/-$testsRemoved\ncoverage:${coverage}%" else "build:failed"
    fun estimatedTokenCount(): Int = if (success) 22 else 50 + errors.size * 20
    data class BuildError(val file: String, val line: Int, val message: String)
}

data class DeterministicSkeleton(val parts: List<SkeletonPart> = emptyList(), val holes: List<SkeletonHole> = emptyList(), val estimatedTokenCount: Int = 0) {
    sealed class SkeletonPart { data class Deterministic(val description: String) : SkeletonPart(); data class HoleRef(val holeId: String) : SkeletonPart() }
    data class SkeletonHole(val id: String, val options: List<String>, val preferredOption: String, val context: String = "", val constraints: List<String> = emptyList())
}

class TokenCompressionEngine {
    companion object { private const val MAX_FINGERPRINT_CACHE_SIZE = 200; private val REGEX_CONSTRAINT_KEY_VALUE = Regex("(\\w+):\\s*(.+?)(?:\\n|$)") }
    private val typeCache = TypeFingerprintCache(); private var currentDialogueState = DialogueState(); private val fingerprintCache = ConcurrentHashMap<String, SemanticFingerprintCompact>(); private val sbdlEncodingCache = ConcurrentHashMap<String, String>()

    fun estimateTokensByPattern(code: String): Int { var total = 0; val patterns = mapOf("function_def" to Regex("""\\b(fun|def|function)\\s+\\w+\\s*\("""), "class_def" to Regex("""\\b(class|object|interface)\\s+\\w+"""), "import_stmt" to Regex("""\\bimport\\s+"""), "variable_decl" to Regex("""\\b(val|var|let|const)\\s+\\w+""")); for ((type, regex) in patterns) { total += regex.findAll(code).count() * (TOKEN_ESTIMATION_TABLE[type] ?: TOKEN_ESTIMATION_TABLE["default"]!!) }; return total }

    fun compressSourceFile(filePath: String, code: String, fingerprint: SemanticFingerprintCompact, level: CompressionLevel = CompressionLevel.BALANCED): String { val cacheKey = "$filePath:${fingerprint.id}:$level"; sbdlEncodingCache[cacheKey]?.let { return it }; fingerprintCache[filePath] = fingerprint; if (fingerprintCache.size > MAX_FINGERPRINT_CACHE_SIZE) { val oldestKey = fingerprintCache.keys.firstOrNull(); if (oldestKey != null) fingerprintCache.remove(oldestKey) }; val encoded = fingerprint.toCompactString(level); sbdlEncodingCache[cacheKey] = encoded; return encoded }

    fun compressDialogueHistory(messages: List<DialogueMessage>): DialogueState { val confirmed = mutableListOf<String>(); val pending = mutableListOf<String>(); val rejectedPaths = mutableListOf<DialogueState.DialoguePath>(); val currentConstraints = mutableMapOf<String, Any>(); for (message in messages) { when (message.role) { "user" -> { if (message.content.contains("确认") || message.content.contains("好的")) confirmed.add(message.content) else if (message.content.contains("否决") || message.content.contains("不要")) rejectedPaths.add(DialogueState.DialoguePath(attempt = "尝试", summary = message.content, rejected = true)) else pending.add(message.content) }; "assistant" -> extractConstraints(message.content, currentConstraints) } }; currentDialogueState = DialogueState(confirmed = confirmed, pending = pending, rejectedPaths = rejectedPaths, currentConstraints = currentConstraints, estimatedTokenCount = 120); return currentDialogueState }
    private fun extractConstraints(content: String, constraints: MutableMap<String, Any>) { REGEX_CONSTRAINT_KEY_VALUE.findAll(content).forEach { constraints[it.groupValues[1]] = it.groupValues[2] } }

    fun compressBuildOutput(rawOutput: String, success: Boolean): BuildSummary = if (success) BuildSummary(success = true, duration = "0.0s", testsAdded = 0, coverage = 0.0) else BuildSummary(success = false, errors = emptyList())
    fun createDeterministicSkeleton(deterministicParts: List<String>, holes: List<DeterministicSkeleton.SkeletonHole>): DeterministicSkeleton = DeterministicSkeleton(parts = deterministicParts.map { DeterministicSkeleton.SkeletonPart.Deterministic(it) } + holes.map { DeterministicSkeleton.SkeletonPart.HoleRef(it.id) }, holes = holes, estimatedTokenCount = holes.sumOf { 50 + it.options.size * 20 })
    fun compressTypeDefinition(typeDef: TypeFingerprint): String { val hash = typeCache.registerType(typeDef); return typeCache.getTypeReference(hash) }
    fun getTypeByHash(hash: String): TypeFingerprint? = typeCache.getTypeByHash(hash)
    fun compressSemanticDiff(targetFingerprint: SemanticFingerprintCompact, intent: String, affectedFingerprints: List<SemanticFingerprintCompact>, constraints: List<String>): SCTPLiteMessage = SCTPLiteMessage(intent = SCTPIntent(operation = "modify", target = targetFingerprint.id, case = intent, constraints = constraints), boundaries = mapOf("target" to targetFingerprint) + affectedFingerprints.associate { it.id to it }, depGraph = mapOf(targetFingerprint.id to affectedFingerprints.map { it.id }), scope = listOf(targetFingerprint.id) + affectedFingerprints.map { it.id }, estimatedTokenCount = targetFingerprint.estimatedTokenCount() * affectedFingerprints.size + 100)
    fun calculateTotalCompressionRatio(originalTokenCount: Int, compressedMessage: SCTPLiteMessage): Double = if (originalTokenCount > 0) originalTokenCount.toDouble() / compressedMessage.estimatedTokenCount else 0.0
    fun compressFullRequest(intent: String, sourceFiles: Map<String, String>, dialogueHistory: List<DialogueMessage>, buildOutput: String, buildSuccess: Boolean, typeDefinitions: List<TypeFingerprint>, fingerprints: Map<String, SemanticFingerprintCompact>): SCTPLiteMessage { val dialogueState = compressDialogueHistory(dialogueHistory); val buildSummary = compressBuildOutput(buildOutput, buildSuccess); typeDefinitions.forEach { type -> compressTypeDefinition(type) }; val targetFingerprint = fingerprints.values.firstOrNull() ?: return SCTPLiteMessage(intent = SCTPIntent(intent, "", ""), estimatedTokenCount = 50); return compressSemanticDiff(targetFingerprint = targetFingerprint, intent = intent, affectedFingerprints = fingerprints.values.drop(1), constraints = dialogueState.confirmed) }
}

data class DialogueMessage(val role: String, val content: String)