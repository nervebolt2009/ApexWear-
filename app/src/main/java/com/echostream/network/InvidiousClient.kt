package com.echostream.network

import android.util.Log
import com.echostream.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class InvidiousClient {
    private val instances = listOf(
        "https://invidious.tiekoetter.com",
        "https://yewtu.be",
        "https://inv.nadeko.net",
        "https://invidious.f5.si",
        "https://vid.puffyan.us"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val pingClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var healthyInstances: List<String> = emptyList()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            healthyInstances = instances.filter { instance ->
                runCatching {
                    val request = Request.Builder()
                        .url("$instance/api/v1/stats")
                        .defaultHeaders()
                        .get()
                        .build()
                    pingClient.newCall(request).execute().use { response -> response.isSuccessful }
                }.getOrElse { error ->
                    Log.e(TAG, "Invidious health check failed for $instance", error)
                    false
                }
            }.ifEmpty { instances }
        }
    }

    suspend fun searchVideos(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        ensureInstancesInitialized()
        healthyInstances.forEach { instance ->
            try {
                val url = "$instance/api/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("type", "video")
                    .addQueryParameter("fields", "videoId,title,author,lengthSeconds,videoThumbnails")
                    .build()
                val request = Request.Builder().url(url).defaultHeaders().get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val results = parseSearchResults(body)
                    if (results.isNotEmpty()) return@withContext results
                    Log.w(TAG, "Invidious search returned no videos for $instance")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Search failed for $instance", error)
            }
        }
        searchYouTubeMusic(query)
    }

    suspend fun fetchAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInstancesInitialized()
        healthyInstances.forEach { instance ->
            try {
                val request = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .defaultHeaders()
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    parseInvidiousAudioUrl(body)?.let { return@withContext it }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Stream fetch failed for $instance", error)
            }
        }
        fetchPipedAudioStreamUrl(videoId)
    }

    private fun ensureInstancesInitialized() {
        if (healthyInstances.isEmpty()) {
            healthyInstances = instances
        }
    }

    private fun parseSearchResults(body: String): List<SearchResult> {
        return JSONArray(body).let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val videoId = item.optString("videoId")
                    if (videoId.isBlank()) continue
                    add(
                        SearchResult(
                            videoId = videoId,
                            title = item.optString("title").ifBlank { "Untitled" },
                            channelName = item.optString("author").ifBlank { "Unknown artist" },
                            durationSeconds = item.optInt("lengthSeconds"),
                            thumbnailUrl = extractThumbnail(item.optJSONArray("videoThumbnails"))
                        )
                    )
                }
            }
        }
    }

    private fun searchYouTubeMusic(query: String): List<SearchResult> {
        return try {
            val requestBody = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB_REMIX")
                            .put("clientVersion", "1.20240520.01.00")
                    )
                )
                .put("query", query)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?key=$YOUTUBE_MUSIC_KEY")
                .defaultHeaders()
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/search")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                parseYouTubeMusicResults(JSONObject(body))
            }
        } catch (error: Exception) {
            Log.e(TAG, "YouTube Music search fallback failed", error)
            emptyList()
        }
    }

    private fun parseYouTubeMusicResults(root: JSONObject): List<SearchResult> {
        val results = linkedMapOf<String, SearchResult>()
        collectMusicResults(root, results)
        if (results.isEmpty()) collectVideoResults(root, results)
        return results.values.take(MAX_SEARCH_RESULTS)
    }

    private fun collectMusicResults(value: Any?, results: MutableMap<String, SearchResult>) {
        if (results.size >= MAX_SEARCH_RESULTS) return
        when (value) {
            is JSONObject -> {
                value.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                    val videoId = findVideoId(renderer)
                    val title = renderer.optJSONArray("flexColumns")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.firstRunText()
                        .orEmpty()
                    if (videoId.isNotBlank() && title.isNotBlank() && !results.containsKey(videoId)) {
                        results[videoId] = SearchResult(
                            videoId = videoId,
                            title = title,
                            channelName = findByline(renderer).ifBlank { "YouTube Music" },
                            durationSeconds = parseDurationSeconds(findDurationText(renderer)),
                            thumbnailUrl = findThumbnailUrl(renderer)
                        )
                    }
                }
                value.keys().forEach { key -> collectMusicResults(value.opt(key), results) }
            }
            is JSONArray -> for (index in 0 until value.length()) collectMusicResults(value.opt(index), results)
        }
    }

    private fun collectVideoResults(value: Any?, results: MutableMap<String, SearchResult>) {
        if (results.size >= MAX_SEARCH_RESULTS) return
        when (value) {
            is JSONObject -> {
                value.optJSONObject("videoRenderer")?.let { renderer ->
                    val videoId = renderer.optString("videoId")
                    val title = renderer.optJSONObject("title")?.firstRunText().orEmpty()
                    if (videoId.isNotBlank() && title.isNotBlank() && !results.containsKey(videoId)) {
                        results[videoId] = SearchResult(
                            videoId = videoId,
                            title = title,
                            channelName = renderer.optJSONObject("ownerText")?.firstRunText().orEmpty().ifBlank { "YouTube" },
                            durationSeconds = parseDurationSeconds(renderer.optJSONObject("lengthText")?.optString("simpleText")),
                            thumbnailUrl = extractThumbnail(renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                        )
                    }
                }
                value.keys().forEach { key -> collectVideoResults(value.opt(key), results) }
            }
            is JSONArray -> for (index in 0 until value.length()) collectVideoResults(value.opt(index), results)
        }
    }

    private fun findVideoId(value: Any?): String {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
                value.keys().forEach { key ->
                    val found = findVideoId(value.opt(key))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val found = findVideoId(value.opt(index))
                if (found.isNotBlank()) return found
            }
        }
        return ""
    }

    private fun findByline(renderer: JSONObject): String {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return ""
        for (index in 1 until flexColumns.length()) {
            val text = flexColumns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.runsText()
                .orEmpty()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun findDurationText(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("fixedColumnMusicResponsiveListItemRenderer")
                    ?.optJSONObject("text")
                    ?.runsText()
                    ?.takeIf { it.contains(':') }
                    ?.let { return it }
                value.optString("simpleText").takeIf { it.contains(':') }?.let { return it }
                value.keys().forEach { key ->
                    val found = findDurationText(value.opt(key))
                    if (!found.isNullOrBlank()) return found
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val found = findDurationText(value.opt(index))
                if (!found.isNullOrBlank()) return found
            }
        }
        return null
    }

    private fun findThumbnailUrl(value: Any?): String {
        when (value) {
            is JSONObject -> {
                value.optJSONArray("thumbnails")?.let { return extractThumbnail(it) }
                value.keys().forEach { key ->
                    val found = findThumbnailUrl(value.opt(key))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val found = findThumbnailUrl(value.opt(index))
                if (found.isNotBlank()) return found
            }
        }
        return ""
    }

    private fun extractThumbnail(thumbnails: JSONArray?): String {
        if (thumbnails == null || thumbnails.length() == 0) return ""
        var selectedUrl = ""
        var selectedWidth = -1
        for (index in 0 until thumbnails.length()) {
            val thumbnail = thumbnails.optJSONObject(index) ?: continue
            val width = thumbnail.optInt("width", 0)
            if (width > selectedWidth) {
                selectedWidth = width
                selectedUrl = thumbnail.optString("url")
            }
        }
        return selectedUrl
    }

    private fun parseDurationSeconds(duration: String?): Int {
        if (duration.isNullOrBlank()) return 0
        return duration.split(':')
            .mapNotNull { it.toIntOrNull() }
            .fold(0) { total, part -> total * 60 + part }
    }

    private fun JSONObject.firstRunText(): String = optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")
        .orEmpty()
        .ifBlank { optString("simpleText") }

    private fun JSONObject.runsText(): String = optJSONArray("runs")?.let { runs ->
        buildString {
            for (index in 0 until runs.length()) {
                append(runs.optJSONObject(index)?.optString("text").orEmpty())
            }
        }
    }.orEmpty().ifBlank { optString("simpleText") }

    private fun parseInvidiousAudioUrl(body: String): String? {
        val formats = JSONObject(body).optJSONArray("adaptiveFormats") ?: return null
        return selectHighestBitrateUrl(formats) { format ->
            val type = format.optString("type")
            type.contains("audio/webm") || type.contains("audio/mp4")
        }
    }

    private fun fetchPipedAudioStreamUrl(videoId: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://pipedapi.kavin.rocks/streams/$videoId")
                .defaultHeaders()
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val streams = JSONObject(body).optJSONArray("audioStreams") ?: return null
                selectHighestBitrateUrl(streams) { true }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Piped stream fetch failed", error)
            null
        }
    }

    private fun selectHighestBitrateUrl(
        formats: JSONArray,
        predicate: (JSONObject) -> Boolean
    ): String? {
        var selectedUrl: String? = null
        var selectedBitrate = -1
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            if (!predicate(format)) continue
            val bitrate = format.optInt("bitrate", 0)
            val url = format.optString("url")
            if (url.isNotBlank() && bitrate > selectedBitrate) {
                selectedBitrate = bitrate
                selectedUrl = url
            }
        }
        return selectedUrl
    }

    private fun Request.Builder.defaultHeaders(): Request.Builder = header("User-Agent", USER_AGENT)
        .header("Accept", "application/json,text/plain,*/*")
        .header("Accept-Language", "en-US,en;q=0.9")

    private companion object {
        const val TAG = "InvidiousClient"
        const val MAX_SEARCH_RESULTS = 20
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Wear OS) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 EchoStream/1.0"
        const val YOUTUBE_MUSIC_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
