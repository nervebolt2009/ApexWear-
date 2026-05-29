package com.echostream.data.model

data class SearchResult(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnailUrl: String
) {
    fun toTrack() = Track(videoId, title, channelName, durationSeconds, thumbnailUrl)
    fun formatDuration(): String {
        val m = durationSeconds / 60
        val s = durationSeconds % 60
        return "%d:%02d".format(m, s)
    }
}
