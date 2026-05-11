package com.aiide

import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

data class ImageAnalysisResult(
    val description: String,
    val labels: List<String>,
    val objects: List<String>,
    val text: String,
    val confidence: Double,
    val imageType: String,
    val dimensions: ImageDimensions
)

data class ImageDimensions(val width: Int, val height: Int)

data class VisionRequest(
    val imageBase64: String,
    val prompt: String,
    val mimeType: String = "image/png",
    val temperature: Double = 0.3,
    val maxTokens: Int = 1000
)

data class VisionResponse(
    val success: Boolean,
    val content: String,
    val model: String,
    val tokensUsed: Int,
    val cost: Double,
    val error: String? = null
)

class MultimodalEngine(
    private val modelRouter: ModelRouter? = null,
    private val apiKey: String = "",
    private val maxImageSize: Int = 10 * 1024 * 1024,
    private val supportedFormats: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp")
) {
    @Volatile private var isShutdown = false
    private val analysisCache = ConcurrentHashMap<String, CachedAnalysis>()

    companion object {
        private const val CACHE_MAX = 100
        private const val CACHE_TTL_MS = 600000L
        private val IMAGE_TYPE_PATTERNS = mapOf(
            "code_screenshot" to Regex("""(?:code|editor|IDE|terminal|function|class|import)""", RegexOption.IGNORE_CASE),
            "ui_screenshot" to Regex("""(?:button|menu|dialog|screen|app|interface|layout)""", RegexOption.IGNORE_CASE),
            "chart" to Regex("""(?:chart|graph|diagram|plot|bar|pie|line)""", RegexOption.IGNORE_CASE),
            "document" to Regex("""(?:document|text|page|article|paragraph)""", RegexOption.IGNORE_CASE)
        )
    }

    fun analyzeImage(imageBase64: String, prompt: String = "Describe this image in detail", mimeType: String = "image/png"): Result<ImageAnalysisResult> {
        if (isShutdown) return Result.failure(IllegalStateException("MultimodalEngine is shutdown"))
        if (imageBase64.isBlank()) return Result.failure(IllegalArgumentException("Image base64 cannot be empty"))

        val cleanedBase64 = cleanBase64(imageBase64)
        val cacheKey = "${cleanedBase64.hashCode()}_${prompt.hashCode()}"
        val cached = analysisCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return Result.success(cached.result)
        }

        val visionRequest = VisionRequest(imageBase64 = cleanedBase64, prompt = prompt, mimeType = mimeType)
        val response = callVisionModel(visionRequest)

        if (!response.success) {
            return Result.failure(RuntimeException("Vision model failed: ${response.error}"))
        }

        val analysis = performLocalAnalysis(cleanedBase64, response.content, mimeType)
        addToCache(cacheKey, analysis)
        return Result.success(analysis)
    }

    fun analyzeScreenshot(screenshotBase64: String, prompt: String = "Analyze this UI screenshot"): Result<ImageAnalysisResult> {
        return analyzeImage(screenshotBase64, prompt, "image/png")
    }

    fun analyzeCodeImage(codeImageBase64: String, prompt: String = "Extract and explain the code in this image"): Result<ImageAnalysisResult> {
        return analyzeImage(codeImageBase64, prompt, "image/png")
    }

    fun callVisionModel(request: VisionRequest): VisionResponse {
        if (isShutdown) return VisionResponse(success = false, content = "", model = "", tokensUsed = 0, cost = 0.0, error = "Engine is shutdown")

        val cleanedBase64 = cleanBase64(request.imageBase64)
        val estimatedBytes = (cleanedBase64.length / 1.37).toInt()
        if (estimatedBytes > maxImageSize) {
            return VisionResponse(success = false, content = "", model = "", tokensUsed = 0, cost = 0.0, error = "Image too large")
        }

        val apiKeyToUse = if (apiKey.isNotBlank()) apiKey else resolveApiKey()
        if (apiKeyToUse.isBlank()) {
            return VisionResponse(success = false, content = "", model = "", tokensUsed = 0, cost = 0.0, error = "No API key configured")
        }

        val multimodalModels = modelRouter?.getRegisteredModels()?.filter {
            it.capabilities.contains("vision") || it.capabilities.contains("multimodal") || it.capabilities.contains("image")
        }
        val selectedModel = multimodalModels?.firstOrNull()

        if (selectedModel == null) {
            return VisionResponse(success = false, content = "", model = "", tokensUsed = 0, cost = 0.0, error = "No multimodal model configured")
        }

        return try {
            val url = URL(selectedModel.endpoint)
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKeyToUse")

                val requestBody = buildVisionRequest(selectedModel, request.copy(imageBase64 = cleanedBase64))
                connection.outputStream.use { os -> os.write(requestBody.toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                }

                if (responseCode in 200..299) parseVisionResponse(responseBody, selectedModel)
                else VisionResponse(success = false, content = "", model = selectedModel.name, tokensUsed = 0, cost = 0.0, error = responseBody)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            VisionResponse(success = false, content = "", model = "", tokensUsed = 0, cost = 0.0, error = e.message)
        }
    }

    fun isMultimodalAvailable(): Boolean {
        val models = modelRouter?.getRegisteredModels() ?: emptyList()
        return models.any { it.capabilities.contains("vision") || it.capabilities.contains("multimodal") || it.capabilities.contains("image") }
    }

    fun getAvailableMultimodalModels(): List<ModelRouter.ModelConfig> {
        return modelRouter?.getRegisteredModels()?.filter {
            it.capabilities.contains("vision") || it.capabilities.contains("multimodal") || it.capabilities.contains("image")
        } ?: emptyList()
    }

    fun imageToBase64(imagePath: String): Result<String> {
        return try {
            val file = java.io.File(imagePath)
            if (!file.exists()) return Result.failure(IllegalArgumentException("Image not found: $imagePath"))
            if (!file.canRead()) return Result.failure(IllegalStateException("Cannot read image: $imagePath"))
            if (!supportedFormats.contains(file.extension.lowercase())) {
                return Result.failure(IllegalArgumentException("Unsupported format: ${file.extension}"))
            }
            if (file.length() > maxImageSize) {
                return Result.failure(IllegalStateException("Image too large: ${file.length()} bytes"))
            }
            val bytes = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)
            Result.success("data:image/${file.extension.lowercase()};base64,$base64")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cleanup() { isShutdown = true; analysisCache.clear() }

    private fun cleanBase64(input: String): String {
        val trimmed = input.trim()
        val headerEnd = trimmed.indexOf("base64,")
        return if (headerEnd >= 0) {
            val start = headerEnd + "base64,".length
            if (start < trimmed.length) trimmed.substring(start) else ""
        } else { trimmed }
    }

    private fun resolveApiKey(): String {
        return modelRouter?.getRegisteredModels()?.firstOrNull {
            it.capabilities.contains("vision") || it.capabilities.contains("multimodal")
        }?.let { model ->
            val endpoint = model.endpoint
            when {
                endpoint.contains("openai", ignoreCase = true) -> System.getenv("OPENAI_API_KEY") ?: ""
                endpoint.contains("anthropic", ignoreCase = true) -> System.getenv("ANTHROPIC_API_KEY") ?: ""
                endpoint.contains("google", ignoreCase = true) -> System.getenv("GOOGLE_API_KEY") ?: ""
                else -> ""
            }
        } ?: ""
    }

    private fun performLocalAnalysis(imageBase64: String, visionContent: String, mimeType: String): ImageAnalysisResult {
        val labels = extractLabels(visionContent)
        val objects = extractObjects(visionContent)
        val text = extractText(visionContent)
        val imageType = classifyImageType(visionContent)
        val confidence = calculateConfidence(visionContent, labels, objects)
        val dimensions = estimateDimensions(imageBase64)

        return ImageAnalysisResult(
            description = visionContent,
            labels = labels,
            objects = objects,
            text = text,
            confidence = confidence,
            imageType = imageType,
            dimensions = dimensions
        )
    }

    private fun extractLabels(content: String): List<String> {
        val stopWords = setOf("this", "that", "the", "and", "for", "are", "but", "not", "you", "all")
        val words = content.split(Regex("""[\s,.!?;:()]+"""))
            .filter { it.length > 3 }.filter { it !in stopWords }.map { it.lowercase() }.distinct()
        return words.take(10)
    }

    private fun extractObjects(content: String): List<String> {
        val objects = mutableListOf<String>()
        listOf("button", "text", "image", "icon", "input", "menu", "dialog", "window", "panel", "list", "form", "header", "footer").forEach { obj ->
            if (content.lowercase().contains(obj)) objects.add(obj)
        }
        return objects
    }

    private fun extractText(content: String): String {
        val matchedTexts = Regex("""["']([^"'\"]{2,})["']""").findAll(content).map { it.groupValues[1] }.filter { it.isNotBlank() }.take(5).toList()
        return if (matchedTexts.isNotEmpty()) matchedTexts.joinToString(" ") else ""
    }

    private fun classifyImageType(content: String): String {
        val lowerContent = content.lowercase()
        for ((type, pattern) in IMAGE_TYPE_PATTERNS) {
            if (pattern.containsMatchIn(lowerContent)) return type
        }
        return "unknown"
    }

    private fun calculateConfidence(content: String, labels: List<String>, objects: List<String>): Double {
        if (content.isBlank()) return 0.0
        val lengthScore = (content.length.coerceAtMost(500) / 500.0).coerceIn(0.0, 1.0)
        val labelScore = (labels.size.coerceAtMost(5) / 5.0).coerceIn(0.0, 1.0)
        val objectScore = (objects.size.coerceAtMost(5) / 5.0).coerceIn(0.0, 1.0)
        return (lengthScore * 0.4 + labelScore * 0.3 + objectScore * 0.3).coerceIn(0.1, 1.0)
    }

    private fun estimateDimensions(imageBase64: String): ImageDimensions {
        return try {
            val bytes = Base64.getDecoder().decode(imageBase64.trim())
            when {
                bytes.size < 24 -> ImageDimensions(0, 0)
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> {
                    val width = ((bytes[16].toInt() and 0xFF) shl 24) or ((bytes[17].toInt() and 0xFF) shl 16) or ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
                    val height = ((bytes[20].toInt() and 0xFF) shl 24) or ((bytes[21].toInt() and 0xFF) shl 16) or ((bytes[22].toInt() and 0xFF) shl 8) or (bytes[23].toInt() and 0xFF)
                    ImageDimensions(width, height)
                }
                else -> ImageDimensions(0, 0)
            }
        } catch (e: Exception) { ImageDimensions(0, 0) }
    }

    private fun buildVisionRequest(model: ModelRouter.ModelConfig, request: VisionRequest): String {
        val imagePayload = if (request.imageBase64.length > 5_000_000) request.imageBase64.take(5_000_000) else request.imageBase64
        return """{"model": "${model.name}", "messages": [{"role": "user", "content": [{"type": "text", "text": "${request.prompt.replace("\"", "\\\"")}"}, {"type": "image_url", "image_url": {"url": "data:${request.mimeType};base64,$imagePayload"}}]}, "max_tokens": ${request.maxTokens}, "temperature": ${request.temperature}]}"""
    }

    private fun parseVisionResponse(responseBody: String, model: ModelRouter.ModelConfig): VisionResponse {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val content = if (choices != null && choices.length() > 0) {
                choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
            } else { json.optJSONObject("content")?.optString("text", "") ?: "" }
            val usage = json.optJSONObject("usage")
            val tokensUsed = usage?.optInt("total_tokens", 0) ?: 0
            VisionResponse(success = true, content = content, model = model.name, tokensUsed = tokensUsed, cost = model.costPerMTok * tokensUsed / 1_000_000.0)
        } catch (e: Exception) {
            VisionResponse(success = false, content = "", model = model.name, tokensUsed = 0, cost = 0.0, error = "Failed to parse response: ${e.message}")
        }
    }

    private fun addToCache(key: String, result: ImageAnalysisResult) {
        if (analysisCache.size >= CACHE_MAX) {
            val oldest = analysisCache.entries.minByOrNull { it.value.timestamp }?.key
            if (oldest != null) analysisCache.remove(oldest)
        }
        analysisCache[key] = CachedAnalysis(result, System.currentTimeMillis())
    }

    private data class CachedAnalysis(val result: ImageAnalysisResult, val timestamp: Long)
}