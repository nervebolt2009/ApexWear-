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
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InvidiousClient {
    private val instances = listOf(
        "https://invidious.privacydev.net",
        "https://yewtu.be",
        "https://inv.nadeko.net",
        "https://invidious.tiekoetter.com",
        "https://iv.datura.network",
        "https://invidious.f5.si"
    )

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi-libre.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://piped-api.privacy.com.de",
        "https://api.piped.yt",
        "https://pipedapi.drgns.space",
        "https://pipedapi.owo.si",
        "https://pipedapi.ducks.party",
        "https://piped-api.codespace.cz"
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

    private val newPipeReady = AtomicBoolean(false)

    // ✅ FIXED: Clean initialization without broken git merge text or double calls
    private fun ensureNewPipeInitialized() {
        if (newPipeReady.compareAndSet(false, true)) {
            try {
                NewPipe.init(NewPipeDownloader(client))
            } catch (error: Exception) {
                Log.e(TAG, "NewPipe initialization failed", error)
                newPipeReady.set(false)
            }
        }
    }

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
                    if (!body.looksLikeJson()) error("Non-JSON response from $instance")
                    parseInvidiousAudioUrl(body)?.let { return@withContext it }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Invidious stream fetch failed for $instance", error)
            }
        }

        fetchNewPipeStreamUrl(videoId)?.let { return@withContext it }

        fetchPipedAudioStreamUrl(videoId)
    }

    private fun fetchNewPipeStreamUrl(videoId: String): String? {
        return try {
            ensureNewPipeInitialized()
            val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId"
            )
            extractor.fetchPage()
            val audioStreams: List<AudioStream> = extractor.audioStreams
            audioStreams
                .filter { it.content.isNotBlank() }
                .maxByOrNull { stream ->
                    val formatScore = stream.format?.let { fmt ->
                        when {
                            fmt.mimeType.contains("audio/mp4") -> 2_000_000
                            fmt.mimeType.contains("audio/webm") -> 1_000_000
                            else -> 0
                        }
                    } ?: 0
                    formatScore + stream.averageBitrate
                }
                ?.content
        } catch (error: Exception) {
            Log.e(TAG, "NewPipe extraction failed for $videoId", error)
            null
        }
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
            val url = format.optString("url")
            if (url.isBlank() || format.has("signatureCipher") || format.has("cipher")) return@selectHighestBitrateUrl false
            val type = format.optString("type")
            type.contains("audio/webm") || type.contains("audio/mp4")
        }
    }

    private fun fetchPipedAudioStreamUrl(videoId: String): String? {
        pipedInstances.forEach { instance ->
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .defaultHeaders()
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (!body.looksLikeJson()) error("Non-JSON response from $instance")
                    parsePipedStreamUrl(body)?.let { return it }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Piped stream fetch failed for $instance", error)
            }
        }
        return null
    }

    private fun parsePipedStreamUrl(body: String): String? {
        val root = JSONObject(body)
        val audioStreams = root.optJSONArray("audioStreams")
        selectBestStreamUrl(audioStreams, ::scoreAudioStream)?.let { return it }

        val videoStreams = root.optJSONArray("videoStreams")
        selectBestStreamUrl(videoStreams, ::scorePlayableVideoStream)?.let { return it }

        val hls = root.optString("hls")
        return hls.takeIf { it.isNotBlank() }
    }

    private fun selectHighestBitrateUrl(
        formats: JSONArray,
        predicate: (JSONObject) -> Boolean
    ): String? = selectBestStreamUrl(formats) { format ->
        if (predicate(format)) scoreAudioStream(format) else null
    }

    private fun selectBestStreamUrl(
        formats: JSONArray?,
        score: (JSONObject) -> Int?
    ): String? {
        if (formats == null || formats.length() == 0) return null
        var selectedUrl: String? = null
        var selectedScore = Int.MIN_VALUE
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val url = format.optString("url")
            val formatScore = score(format) ?: continue
            if (url.isBlank()) continue
            if (formatScore > selectedScore) {
                selectedScore = formatScore
                selectedUrl = url
            }
        }
        return selectedUrl
    }

    private fun scoreAudioStream(format: JSONObject): Int {
        val type = format.optString("type", format.optString("mimeType"))
        val container = format.optString("container", format.optString("format"))
        val codecScore = when {
            type.contains("audio/mp4", ignoreCase = true) || container.contains("m4a", ignoreCase = true) -> 2_000_000
            type.contains("audio/webm", ignoreCase = true) || container.contains("webm", ignoreCase = true) -> 1_000_000
            else -> 0
        }
        return codecScore + format.optInt("bitrate", format.optInt("quality", 0))
    }

    private fun scorePlayableVideoStream(format: JSONObject): Int? {
        if (format.optBoolean("videoOnly", false)) return null
        val type = format.optString("type", format.optString("mimeType"))
        val container = format.optString("container", format.optString("format"))
        val isHls = type.contains("mpegurl", ignoreCase = true) || container.contains("hls", ignoreCase = true)
        val isMp4 = type.contains("video/mp4", ignoreCase = true) || container.contains("mp4", ignoreCase = true)
        if (!isMp4 && !isHls) return null
        val formatScore = when {
            isMp4 -> 2_000_000
            isHls -> 1_000_000
            else -> 0
        }
        return formatScore + format.optInt("bitrate", 0) + format.optInt("height", 0)
    }

    private fun String.looksLikeJson(): Boolean = trimStart().let { it.startsWith("{") || it.startsWith("[") }

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
