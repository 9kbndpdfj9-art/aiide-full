package com.aiide

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors

class VisualFeedbackLoop(private val context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val eventLogger = EventLogger(context)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VisualFeedbackLoop").apply { isDaemon = true }
    }

    @Volatile
    private var isShutdown = false

    companion object {
        private const val SCORE_THRESHOLD = 80
    }

    interface FeedbackCallback {
        fun onAnalysisStart()
        fun onAnalysisResult(analysis: VisualAnalysis)
        fun onAutoFixStart(issues: List<VisualIssue>)
        fun onAutoFixComplete(fixes: List<VisualFix>)
        fun onError(message: String)
    }

    data class VisualAnalysis(
        val score: Int,
        val issues: List<VisualIssue>,
        val description: String,
        val matchesIntent: Boolean
    )

    data class VisualIssue(
        val type: String,
        val description: String,
        val severity: String,
        val suggestion: String,
        val region: String?
    )

    data class VisualFix(
        val issue: VisualIssue,
        val codeChange: String,
        val applied: Boolean
    )

    fun analyzeScreen(webView: WebView, userIntent: String, callback: FeedbackCallback) {
        if (isShutdown) {
            callback.onError("VisualFeedbackLoop is shut down")
            return
        }
        callback.onAnalysisStart()

        val screenshot = ScreenshotCapture.captureToBase64(webView) ?: run {
            callback.onError("Failed to capture screenshot")
            return
        }

        executor.execute {
            try {
                val analysis = callVisionModel(screenshot, userIntent)
                callback.onAnalysisResult(analysis)

                if (analysis.score < SCORE_THRESHOLD && analysis.issues.isNotEmpty()) {
                    callback.onAutoFixStart(analysis.issues)
                    val fixes = generateFixesViaLLM(analysis.issues, userIntent)
                    callback.onAutoFixComplete(fixes)
                }
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun analyzeScreenshotSync(screenshotBase64: String, userIntent: String): String {
        val apiKey = dbHelper.getConfig("api_key")
        if (apiKey.isNullOrEmpty()) return jsonError("API key not configured")
        if (screenshotBase64.isEmpty()) return jsonError("No screenshot provided")

        return try {
            val startTime = System.currentTimeMillis()
            val analysis = callVisionModel(screenshotBase64, userIntent)
            val duration = System.currentTimeMillis() - startTime
            eventLogger.logToolCall("visual_analyze", true, duration)

            val issuesArr = JSONArray()
            analysis.issues.forEach { issue ->
                issuesArr.put(JSONObject().apply {
                    put("type", issue.type)
                    put("description", issue.description)
                    put("severity", issue.severity)
                    put("suggestion", issue.suggestion)
                    put("region", issue.region)
                })
            }

            JSONObject().apply {
                put("score", analysis.score)
                put("matches_intent", analysis.matchesIntent)
                put("description", analysis.description)
                put("issues", issuesArr)
                put("success", true)
            }.toString()
        } catch (e: Exception) {
            eventLogger.logToolCall("visual_analyze", false, 0)
            jsonError("Vision error: ${e.message}")
        }
    }

    private fun callVisionModel(screenshotBase64: String, userIntent: String): VisualAnalysis {
        val apiKey = dbHelper.getConfig("api_key") ?: throw Exception("API key not configured")
        val visionModel = dbHelper.getConfig("vision_model") ?: "gpt-4o-mini"
        val apiEndpoint = dbHelper.getConfig("api_endpoint") ?: "https://api.openai.com/v1/chat/completions"

        val url = URL(apiEndpoint)
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            val systemPrompt = """You are a UI/UX quality inspector. Analyze the screenshot and respond in JSON format."""

            val userMessage = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "User's intent: \"$userIntent\"\n\nAnalyze this screenshot:")
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$screenshotBase64")
                        put("detail", "high")
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("model", visionModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                })
                put("max_tokens", 2048)
            }

            conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            }

            if (responseCode != 200) {
                throw Exception("Vision API error: $responseBody")
            }

            val content = JSONObject(responseBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""

            return parseAnalysis(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun generateFixesViaLLM(issues: List<VisualIssue>, userIntent: String): List<VisualFix> {
        val apiKey = dbHelper.getConfig("api_key") ?: return emptyList()
        val model = dbHelper.getConfig("api_model") ?: "gpt-4o-mini"
        val apiEndpoint = dbHelper.getConfig("api_endpoint") ?: "https://api.openai.com/v1/chat/completions"

        val criticalIssues = issues.filter { it.severity == "critical" || it.severity == "major" }
        if (criticalIssues.isEmpty()) return emptyList()

        return try {
            val url = URL(apiEndpoint)
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", "Generate code fixes for UI issues.") })
                        put(JSONObject().apply { put("role", "user"); put("content", "Issues: ${criticalIssues.joinToString { it.description }}") })
                    })
                    put("max_tokens", 2048)
                }

                conn.outputStream.use { os -> os.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode != 200) return emptyList()

                val content = JSONObject(conn.inputStream.bufferedReader().readText())
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "") ?: ""

                parseFixes(content, criticalIssues)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseFixes(content: String, issues: List<VisualIssue>): List<VisualFix> {
        return try {
            val json = JSONObject(content.trim())
            val fixesArr = json.optJSONArray("fixes") ?: JSONArray()
            val result = mutableListOf<VisualFix>()
            for (i in 0 until fixesArr.length()) {
                val fixObj = fixesArr.optJSONObject(i) ?: continue
                val issueType = fixObj.optString("issue_type", "")
                val code = fixObj.optString("code", "")
                val matchedIssue = issues.find { it.type == issueType } ?: issues.getOrNull(i) ?: continue
                result.add(VisualFix(issue = matchedIssue, codeChange = code, applied = false))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAnalysis(content: String): VisualAnalysis {
        return try {
            val json = JSONObject(content.trim())
            val issues = mutableListOf<VisualIssue>()
            val issuesArr = json.optJSONArray("issues") ?: JSONArray()
            for (i in 0 until issuesArr.length()) {
                val issueObj = issuesArr.optJSONObject(i) ?: continue
                issues.add(VisualIssue(
                    type = issueObj.optString("type", "unknown"),
                    description = issueObj.optString("description", ""),
                    severity = issueObj.optString("severity", "minor"),
                    suggestion = issueObj.optString("suggestion", ""),
                    region = issueObj.optString("region")
                ))
            }
            VisualAnalysis(
                score = json.optInt("score", 0),
                issues = issues,
                description = json.optString("description", ""),
                matchesIntent = json.optBoolean("matches_intent", false)
            )
        } catch (e: Exception) {
            VisualAnalysis(score = 0, issues = emptyList(), description = "Failed to parse: ${e.message}", matchesIntent = false)
        }
    }

    private fun jsonError(message: String): String {
        return JSONObject().put("success", false).put("error", message).toString()
    }

    fun shutdown() {
        isShutdown = true
        executor.shutdown()
    }
}

class DatabaseHelper(private val context: Context) {
    private val prefs = context.getSharedPreferences("aiide_config", Context.MODE_PRIVATE)
    fun getConfig(key: String): String? = prefs.getString(key, null)
    fun setConfig(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

class EventLogger(private val context: Context) {
    private val prefs = context.getSharedPreferences("event_log", Context.MODE_PRIVATE)
    fun logToolCall(toolName: String, success: Boolean, durationMs: Long) {
        prefs.edit()
            .putLong("last_${toolName}_${if (success) "success" else "failure"}", System.currentTimeMillis())
            .putLong("last_${toolName}_duration", durationMs)
            .apply()
    }
}

object ScreenshotCapture {
    fun captureToBase64(webView: WebView): String? {
        return try {
            val picture = webView.capturePicture()
            val bitmap = android.graphics.Bitmap.createBitmap(
                picture.width.coerceAtLeast(1), picture.height.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            picture.draw(canvas)
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
