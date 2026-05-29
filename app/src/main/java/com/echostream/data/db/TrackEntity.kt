package com.echostream.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_tracks")
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val savedAt: Long = System.currentTimeMillis()
)
