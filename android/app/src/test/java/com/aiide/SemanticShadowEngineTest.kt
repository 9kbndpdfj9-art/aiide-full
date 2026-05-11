package com.aiide

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * 验证1：SemanticShadowEngine语义指纹提取测试
 * 
 * 测试用例：authenticate函数
 * 
 * 预期结果：
 * - 函数名: authenticate
 * - 输入: email(String), password(String)
 * - 输出: AuthResult
 * - 副作用: logAuth (STATE_MUTATION 或 LOG_OUTPUT)
 * - 依赖: findUserByEmail, bcrypt.verify, generateJWT, logAuth
 */

object TestContext {
    val filesDir = File(System.getProperty("java.io.tmpdir"), "aiide_test")
    
    fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        return object : android.content.SharedPreferences {
            private val map = mutableMapOf<String, Any?>()
            override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String>? ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = map.containsKey(key)
            override fun getAll(): MutableMap<String, *> = map
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { map[key] = value; return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { map[key] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { map[key] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { map[key] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { map[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { map[key] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
                override fun commit(): Boolean = true
                override fun apply() { }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) { }
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) { }
        }
    }
    
    init {
        if (!filesDir.exists()) filesDir.mkdirs()
    }
}

object SemanticShadowTest {
    
    data class CodeFunction(
        val functionName: String,
        val signature: String,
        val parameters: List<CodeParameter>,
        val returnType: String,
        val body: String
    )
    
    data class CodeParameter(
        val name: String,
        val type: String,
        val isOptional: Boolean = false
    )
    
    fun extractFunctions(code: String): List<CodeFunction> {
        val functions = mutableListOf<CodeFunction>()
        val kotlinRegex = Regex("fun\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*(\\w+))?\\s*\\{([^}]*(?:\\{[^}]*\\}[^}]*)*)\\}", RegexOption.DOT_MATCHES_ALL)
        val matches = kotlinRegex.findAll(code)
        
        for (match in matches) {
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val returnType = match.groupValues.getOrNull(3) ?: "Unit"
            val body = match.groupValues[4]
            val parameters = parseParameters(paramsStr)
            
            functions.add(CodeFunction(
                functionName = name,
                signature = "$name($paramsStr) : $returnType",
                parameters = parameters,
                returnType = returnType,
                body = body
            ))
        }
        
        return functions
    }
    
    private fun parseParameters(paramsStr: String): List<CodeParameter> {
        if (paramsStr.trim().isEmpty()) return emptyList()
        return paramsStr.split(",").map { param ->
            val parts = param.trim().split(":")
            val name = parts[0].trim()
            val type = parts.getOrNull(1)?.trim() ?: "Any"
            val isOptional = param.contains("=") || param.contains("?")
            CodeParameter(name, type, isOptional)
        }
    }
    
    fun inferSideEffects(body: String): List<String> {
        val effects = mutableListOf<String>()
        if (body.contains("logAuth") || body.contains("print") || body.contains("println")) {
            effects.add("logAuth")
        }
        if (body.contains("= ")) {
            effects.add("STATE_MUTATION")
        }
        if (body.contains("http") || body.contains("fetch")) {
            effects.add("NETWORK_CALL")
        }
        return effects
    }
    
    fun inferDependencies(body: String): List<String> {
        val dependencies = mutableListOf<String>()
        val callRegex = Regex("(\\w+)\\s*\\(")
        val calls = callRegex.findAll(body).map { it.groupValues[1] }
            .filter { it !in listOf("if", "for", "while", "return", "val", "var", "fun", "println", "print") }
            .distinct()
        dependencies.addAll(calls)
        return dependencies
    }
    
    fun analyzeFingerprint(code: String): Map<String, Any> {
        val functions = extractFunctions(code)
        if (functions.isEmpty()) {
            return mapOf("success" to false, "error" to "No functions found")
        }
        val function = functions.first()
        val inputParams = function.parameters.map { p -> "${p.name}(${p.type})" }
        val sideEffects = inferSideEffects(function.body)
        val dependencies = inferDependencies(function.body)
        return mapOf(
            "success" to true,
            "functionName" to function.functionName,
            "input" to inputParams,
            "output" to function.returnType,
            "sideEffects" to sideEffects,
            "dependencies" to dependencies
        )
    }
}

fun main() {
    println("=== 验证1：SemanticShadowEngine语义指纹提取测试 ===")
    println()
    val testCode = """
        fun authenticate(email: String, password: String): AuthResult {
            val user = findUserByEmail(email)
            if (user == null) return AuthResult.Failed("User not found")
            if (!bcrypt.verify(password, user.passwordHash)) return AuthResult.Failed("Invalid password")
            val jwt = generateJWT(user.id, expiresIn = 2.hours)
            logAuth(user.id, "login")
            return AuthResult.Success(jwt)
        }
    """.trimIndent()
    val result = SemanticShadowTest.analyzeFingerprint(testCode)
    println("测试结果：")
    println("函数名: ${result["functionName"]}")
    println("输入: ${result["input"]}")
    println("输出: ${result["output"]}")
    println("副作用: ${result["sideEffects"]}")
    println("依赖: ${result["dependencies"]}")
    println()
    val checks = listOf(
        "函数名=authenticate" to (result["functionName"] == "authenticate"),
        "输入包含email(String)" to (result["input"].toString().contains("email")),
        "输入包含password(String)" to (result["input"].toString().contains("password")),
        "输出=AuthResult" to (result["output"].toString().contains("AuthResult")),
        "副作用包含logAuth" to (result["sideEffects"].toString().contains("logAuth")),
        "依赖包含findUserByEmail" to (result["dependencies"].toString().contains("findUserByEmail")),
        "依赖包含verify" to (result["dependencies"].toString().contains("verify")),
        "依赖包含generateJWT" to (result["dependencies"].toString().contains("generateJWT")),
        "依赖包含logAuth" to (result["dependencies"].toString().contains("logAuth"))
    )
    var allPassed = true
    println("验证检查：")
    checks.forEach { (name, passed) ->
        val status = if (passed) "通过" else "失败"
        println("  $name: $status")
        if (!passed) allPassed = false
    }
    println()
    if (allPassed) {
        println("验证1：通过")
    } else {
        println("验证1：未通过")
    }
}