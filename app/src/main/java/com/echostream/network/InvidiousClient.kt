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

    /**
     * Selects an audio stream URL for the given YouTube video by probing configured Invidious instances and,
     * if those fail to produce a usable audio URL, falling back to the YouTube player stream and Piped endpoints.
     *
     * @param videoId The YouTube video identifier to fetch streams for.
     * @return The selected audio stream URL, or `null` if no usable stream was found.
     */
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
                Log.e(TAG, "Stream fetch failed for $instance", error)
            }
        }
        fetchYouTubePlayerStreamUrl(videoId) ?: fetchPipedAudioStreamUrl(videoId)
    }

    /**
     * Ensures the cached list of healthy Invidious instances is populated.
     *
     * If no healthy instances are recorded, assigns the configured `instances` list to `healthyInstances`.
     */
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

    /**
     * Extracts the highest-bitrate audio stream URL from an Invidious video JSON response.
     *
     * @param body JSON response body returned by an Invidious `/api/v1/videos` request.
     * @return The URL of the highest-bitrate audio stream whose MIME indicates `audio/webm` or `audio/mp4`, or `null` if none is found.
     */
    private fun parseInvidiousAudioUrl(body: String): String? {
        val formats = JSONObject(body).optJSONArray("adaptiveFormats") ?: return null
        return selectHighestBitrateUrl(formats) { format ->
            val type = format.optString("type")
            type.contains("audio/webm") || type.contains("audio/mp4")
        }
    }


    /**
     * Attempts to obtain an audio stream URL for the specified YouTube video using the YouTube Music player API.
     *
     * @param videoId The YouTube video identifier to fetch a player stream for.
     * @return The selected audio stream URL as a `String`, or `null` if no usable stream could be retrieved.
     */
    private fun fetchYouTubePlayerStreamUrl(videoId: String): String? {
        return try {
            val requestBody = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "ANDROID_MUSIC")
                            .put("clientVersion", "7.03.52")
                            .put("androidSdkVersion", 34)
                    )
                )
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player?key=$YOUTUBE_MUSIC_KEY")
                .defaultHeaders()
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/watch?v=$videoId")
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.03.52")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (!body.looksLikeJson()) error("Non-JSON response from YouTube player")
                parseYouTubePlayerStreamUrl(body)
            }
        } catch (error: Exception) {
            Log.e(TAG, "YouTube player stream fetch failed", error)
            null
        }
    }

    /**
     * Attempts to retrieve an audio stream URL for the given video from configured Piped instances.
     *
     * @param videoId The video identifier to request streams for.
     * @return The selected audio stream URL, or `null` if no playable stream was found.
     */
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


    /**
     * Selects the best audio or playable stream URL from a YouTube player JSON response.
     *
     * Parses the provided YouTube player response JSON and first attempts to choose the highest-scoring
     * audio stream from `streamingData.adaptiveFormats`. If no audio adaptive stream is selected, it
     * falls back to choosing the best playable stream from `streamingData.formats`.
     *
     * @param body The raw JSON response body returned by the YouTube player endpoint.
     * @return The selected stream URL, or `null` if no suitable stream was found.
     */
    private fun parseYouTubePlayerStreamUrl(body: String): String? {
        val streamingData = JSONObject(body).optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        selectBestStreamUrl(adaptiveFormats) { format ->
            val url = format.optString("url")
            val type = format.optString("mimeType")
            if (url.isNotBlank() && type.startsWith("audio/", ignoreCase = true)) scoreAudioStream(format) else null
        }?.let { return it }

        val formats = streamingData.optJSONArray("formats")
        return selectBestStreamUrl(formats, ::scorePlayableVideoStream)
    }

    /**
     * Selects the most suitable stream URL from a Piped JSON response.
     *
     * First attempts to choose the best audio stream from `audioStreams`, then a playable
     * video stream from `videoStreams`, and finally falls back to the `hls` field.
     *
     * @return The selected stream URL, or `null` if no usable URL is present.
     */
    private fun parsePipedStreamUrl(body: String): String? {
        val root = JSONObject(body)
        val audioStreams = root.optJSONArray("audioStreams")
        selectBestStreamUrl(audioStreams, ::scoreAudioStream)?.let { return it }

        val videoStreams = root.optJSONArray("videoStreams")
        selectBestStreamUrl(videoStreams, ::scorePlayableVideoStream)?.let { return it }

        val hls = root.optString("hls")
        return hls.takeIf { it.isNotBlank() }
    }

    /**
     * Selects the stream URL with the highest audio score among formats that satisfy the given predicate.
     *
     * @param formats A JSONArray of format objects to evaluate.
     * @param predicate A predicate invoked for each format; formats for which this returns `true` are scored using the audio scoring function and considered for selection.
     * @return The URL of the best-scoring matching format, or `null` if none qualify.
     */
    private fun selectHighestBitrateUrl(
        formats: JSONArray,
        predicate: (JSONObject) -> Boolean
    ): String? = selectBestStreamUrl(formats) { format ->
        if (predicate(format)) scoreAudioStream(format) else null
    }

    /**
     * Selects the highest-scoring stream URL from an array of format objects.
     *
     * @param formats A JSONArray of format JSONObject entries to evaluate; each object is expected to contain a `url` field.
     * @param score A scoring function that returns an Int score for a given format JSONObject or `null` to exclude that format.
     * @return The `url` of the format with the highest score, or `null` if no valid URL was found or `formats` is empty.
     */
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

    /**
     * Computes a numeric desirability score for an audio stream format.
     *
     * @param format A JSON object describing a stream format (expected keys include `type`/`mimeType`, `container`/`format`, and `bitrate` or `quality`).
     * @return An integer score where higher values indicate a more desirable audio stream. Preferred codecs/containers receive large base scores (MP4/m4a > WebM) and the returned score is increased by the format's `bitrate` (or `quality`) value.
     */
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

    /**
     * Score a stream format for suitability as a playable video stream (MP4 or HLS).
     *
     * Returns a higher score for MP4 over HLS and adds the format's bitrate and height to prefer higher-quality streams.
     *
     * @param format A JSON object describing a stream format; expected keys include `type`/`mimeType`, `container`/`format`, `bitrate`, `height`, and `videoOnly`.
     * @return An integer score where higher is better, or `null` if the format is video-only or not an MP4/HLS playable stream.
     */
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

    /**
 * Determines whether the string appears to be JSON by checking if, after skipping leading whitespace, it starts with `{` or `[` .
 *
 * @return `true` if the string (ignoring leading whitespace) begins with `{` or `[`, `false` otherwise.
 */
private fun String.looksLikeJson(): Boolean = trimStart().let { it.startsWith("{") || it.startsWith("[") }

    /**
         * Adds the standard default HTTP headers used by the client to this request builder.
         *
         * @return The same [Request.Builder] with the default headers applied.
         */
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
