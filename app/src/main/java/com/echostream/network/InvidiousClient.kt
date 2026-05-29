package com.echostream.network

import android.util.Log
import com.echostream.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    return@withContext parseSearchResults(body)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Search failed for $instance", error)
            }
        }
        emptyList()
    }

    suspend fun fetchAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInstancesInitialized()
        healthyInstances.forEach { instance ->
            try {
                val request = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
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
                            title = item.optString("title"),
                            channelName = item.optString("author"),
                            durationSeconds = item.optInt("lengthSeconds"),
                            thumbnailUrl = extractThumbnail(item.optJSONArray("videoThumbnails"))
                        )
                    )
                }
            }
        }
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

    private companion object {
        const val TAG = "InvidiousClient"
    }
}
