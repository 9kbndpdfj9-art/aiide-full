package com.aiide

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val publishedDate: String? = null,
    val relevanceScore: Double = 0.0
)

data class WebContent(
    val url: String,
    val title: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val fetchedAt: Long = System.currentTimeMillis()
)

data class SearchConfig(
    val engine: String = "google",
    val language: String = "en",
    val country: String = "us",
    val maxResults: Int = 10,
    val safeSearch: Boolean = true,
    val useProxy: Boolean = false,
    val timeoutMs: Long = 10000,
    val apiKey: String? = null,
    val searchEngineId: String? = null
)

data class SearchCacheEntry(
    val query: String,
    val results: List<SearchResult>,
    val cachedAt: Long,
    val expiresAt: Long
)

class WebSearchEngine(
    private val eventLogger: EventLogger? = null
) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
    private val searchCache = ConcurrentHashMap<String, SearchCacheEntry>()
    private val fetchCache = ConcurrentHashMap<String, WebContent>()
    @Volatile private var config = SearchConfig()
    @Volatile private var isShutdown = false

    companion object {
        private const val MAX_CACHE_SIZE = 100
        private const val CACHE_TTL_MS = 3600000L
        private const val MAX_CONCURRENT_SEARCHES = 5
        private const val MAX_RESULTS_PER_SEARCH = 20
        private const val MAX_CONTENT_LENGTH = 100000

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/53736"
        )

        private val BLOCKED_DOMAINS = setOf("facebook.com", "twitter.com", "instagram.com", "youtube.com")
        private val RE_HEAD = Regex("""<head[^>]*>.*?</head>""", RegexOption.DOT_MATCHES_ALL)
        private val RE_SCRIPT = Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL)
        private val RE_STYLE = Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL)
        private val RE_ANY_TAG = Regex("""<[^>]+>""")
    }

    fun search(query: String, config: SearchConfig? = null): Result<List<SearchResult>> {
        if (isShutdown) return Result.failure(IllegalStateException("WebSearchEngine is shutdown"))
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Search query cannot be empty"))

        val searchConfig = config ?: this.config
        val cacheKey = "${searchConfig.engine}:${query}:${searchConfig.language}"
        val cachedResult = searchCache[cacheKey]
        if (cachedResult != null && System.currentTimeMillis() < cachedResult.expiresAt) {
            return Result.success(cachedResult.results)
        }

        return try {
            val results = when (searchConfig.engine) {
                "google" -> searchGoogle(query, searchConfig)
                "duckduckgo" -> searchDuckDuckGo(query, searchConfig)
                else -> searchDuckDuckGo(query, searchConfig)
            }

            val filteredResults = results.filter { !isBlockedDomain(it.url) }
                .mapIndexed { index, result -> result.copy(relevanceScore = calculateRelevanceScore(result, query, index)) }
                .sortedByDescending { it.relevanceScore }
                .take(searchConfig.maxResults)

            val cacheEntry = SearchCacheEntry(query, filteredResults, System.currentTimeMillis(), System.currentTimeMillis() + CACHE_TTL_MS)
            searchCache[cacheKey] = cacheEntry

            Result.success(filteredResults)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchUrl(url: String, extractContent: Boolean = true): Result<WebContent> {
        if (isShutdown) return Result.failure(IllegalStateException("WebSearchEngine is shutdown"))
        if (url.isBlank()) return Result.failure(IllegalArgumentException("URL cannot be empty"))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL must start with http:// or https://"))
        }

        val cachedContent = fetchCache[url]
        if (cachedContent != null && System.currentTimeMillis() - cachedContent.fetchedAt < CACHE_TTL_MS) {
            return Result.success(cachedContent)
        }

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = config.timeoutMs.toInt()
                connection.readTimeout = config.timeoutMs.toInt()
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENTS.random())
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return Result.failure(IllegalStateException("HTTP $responseCode"))
                }

                val contentType = connection.contentType ?: ""
                if (!contentType.startsWith("text/html") && !contentType.startsWith("text/plain")) {
                    return Result.failure(IllegalArgumentException("Unsupported content type: $contentType"))
                }

                val html = connection.inputStream.bufferedReader().use { it.readText().take(MAX_CONTENT_LENGTH) }
                val title = extractTitle(html)
                val content = if (extractContent) extractContent(html) else html

                val webContent = WebContent(url = url, title = title, content = content)
                fetchCache[url] = webContent
                Result.success(webContent)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchAndFetch(query: String, fetchTopN: Int = 3, config: SearchConfig? = null): Result<Pair<List<SearchResult>, List<WebContent>>> {
        val searchResult = search(query, config)
        if (searchResult.isFailure) {
            return Result.failure(searchResult.exceptionOrNull() ?: IllegalStateException("Search failed"))
        }
        val results = searchResult.getOrNull() ?: emptyList()
        val urlsToFetch = results.take(fetchTopN).map { it.url }
        val fetchResult = fetchMultipleUrls(urlsToFetch)
        val contents = fetchResult.getOrNull()?.values?.toList() ?: emptyList()
        return Result.success(results to contents)
    }

    fun fetchMultipleUrls(urls: List<String>, extractContent: Boolean = true): Result<Map<String, WebContent>> {
        if (urls.isEmpty()) return Result.success(emptyMap())
        if (urls.size > MAX_CONCURRENT_SEARCHES) {
            return Result.failure(IllegalArgumentException("Too many URLs: ${urls.size}"))
        }

        val futures = urls.map { url ->
            executor.submit<Pair<String, Result<WebContent>>> {
                val result = fetchUrl(url, extractContent)
                url to result
            }
        }

        val results = mutableMapOf<String, WebContent>()
        futures.zip(urls).forEach { (future, url) ->
            try {
                val (_, result) = future.get(15, TimeUnit.SECONDS)
                result.onSuccess { results[url] = it }
            } catch (e: Exception) { }
        }
        return Result.success(results)
    }

    private fun searchGoogle(query: String, config: SearchConfig): List<SearchResult> {
        val apiKey = config.apiKey
        val searchEngineId = config.searchEngineId
        if (apiKey != null && searchEngineId != null) {
            return searchGoogleApi(query, config, apiKey, searchEngineId)
        }
        return searchGoogleOrganic(query, config)
    }

    private fun searchGoogleApi(query: String, config: SearchConfig, apiKey: String, searchEngineId: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$searchEngineId&q=$encodedQuery&num=${config.maxResults}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val items = json.optJSONArray("items") ?: return emptyList()
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                results.add(SearchResult(
                    title = item.optString("title", ""),
                    url = item.optString("link", ""),
                    snippet = item.optString("snippet", ""),
                    source = "google"
                ))
            }
            return results
        } finally {
            connection.disconnect()
        }
    }

    private fun searchGoogleOrganic(query: String, config: SearchConfig): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$encodedQuery&num=${config.maxResults}&hl=${config.language}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()
            connection.setRequestProperty("User-Agent", USER_AGENTS.random())
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.disconnect()
                return emptyList()
            }
            val html = connection.inputStream.bufferedReader().readText()
            return parseGoogleResults(html)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGoogleResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkPattern = Regex("""<a[^>]*href="([^"]*)"[^>]*>""")
        val titlePattern = Regex("""<h3[^>]*>([^<]+)</h3>""")
        val titles = titlePattern.findAll(html).map { it.groupValues[1].trim() }.toList()
        var index = 0
        linkPattern.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (url.startsWith("http") && !url.contains("google.com") && !url.contains("youtube.com")) {
                val cleanUrl = url.substringBefore("&").substringBefore("?")
                val title = titles.getOrNull(index) ?: cleanUrl.substringAfterLast("/")
                results.add(SearchResult(title = title, url = cleanUrl, snippet = "", source = "google"))
                index++
            }
        }
        return results.take(MAX_RESULTS_PER_SEARCH)
    }

    private fun searchDuckDuckGo(query: String, config: SearchConfig): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()
            connection.setRequestProperty("User-Agent", USER_AGENTS.random())
            val html = connection.inputStream.bufferedReader().readText()
            return parseDuckDuckGoResults(html)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDuckDuckGoResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val resultPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>([^<]+)</a>""")
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>([^<]+)</a>""")
        val snippets = snippetPattern.findAll(html).map { it.groupValues[1].trim() }.toList()
        var index = 0
        resultPattern.findAll(html).forEach { match ->
            val url = try { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8").substringBefore("&") } catch (e: Exception) { match.groupValues[1] }
            val title = match.groupValues[2].trim()
            val snippet = snippets.getOrNull(index) ?: ""
            if (url.isNotBlank()) {
                results.add(SearchResult(title = title, url = url, snippet = snippet, source = "duckduckgo"))
                index++
            }
        }
        return results.take(MAX_RESULTS_PER_SEARCH)
    }

    private fun extractTitle(html: String): String {
        return Regex("""<title[^>]*>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.trim() ?: "No Title"
    }

    private fun extractContent(html: String): String {
        var content = html
        content = RE_HEAD.replace(content, "")
        content = RE_SCRIPT.replace(content, "")
        content = RE_STYLE.replace(content, "")
        content = RE_ANY_TAG.replace(content, "")
        content = content.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        content = Regex("""\s+""").replace(content, " ").trim()
        return content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(MAX_CONTENT_LENGTH)
    }

    private fun calculateRelevanceScore(result: SearchResult, query: String, position: Int): Double {
        var score = 1.0 - (position * 0.05)
        val queryTerms = query.lowercase().split(Regex("""\s+"""))
        val titleLower = result.title.lowercase()
        queryTerms.forEach { term ->
            if (titleLower.contains(term)) score += 0.3
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun isBlockedDomain(url: String): Boolean {
        return BLOCKED_DOMAINS.any { domain -> url.contains(domain, ignoreCase = true) }
    }

    fun clearCache() { searchCache.clear(); fetchCache.clear() }
    fun setConfig(newConfig: SearchConfig) { config = newConfig }
    fun getConfig(): SearchConfig = config

    fun cleanup() {
        isShutdown = true
        searchCache.clear()
        fetchCache.clear()
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}