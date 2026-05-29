package com.echostream.data.model

data class Track(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnailUrl: String
)
