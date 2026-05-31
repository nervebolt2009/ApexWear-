package com.echostream.player

import android.content.Intent
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var wakeLock: WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        // Build ExoPlayer with audio focus
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(defaultPlaybackHeaders())

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Add listener to manage wake lock based on playback state
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY, Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) {
                            wakeLock?.let { lock ->
                                if (!lock.isHeld) {
                                    lock.acquire()
                                }
                            }
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                wakeLock?.let { lock ->
                    if (isPlaying && !lock.isHeld) {
                        lock.acquire()
                    } else if (!isPlaying && lock.isHeld) {
                        lock.release()
                    }
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(EchoStreamSessionCallback())
            .build()

        // WakeLock to keep CPU running during playback - initialize but don't acquire
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EchoStream::PlaybackWakeLock"
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // MUST release in this exact order
        player.stop()
        player.release()
        mediaSession.release()
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        super.onDestroy()
    }

    private fun defaultPlaybackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "audio/webm,audio/mp4,video/webm,video/mp4,application/x-mpegurl,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private inner class EchoStreamSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val currentItem = player.currentMediaItem
                ?: return Futures.immediateFailedFuture(IllegalStateException("No track available to resume"))

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(currentItem),
                    0,
                    player.currentPosition.coerceAtLeast(0L)
                )
            )
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Wear OS) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 EchoStream/1.0"
    }
}
