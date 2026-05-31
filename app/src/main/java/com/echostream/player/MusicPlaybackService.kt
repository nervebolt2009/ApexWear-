package com.echostream.player

import android.content.Intent
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    /**
     * Initializes playback components: configures and creates the ExoPlayer instance, the MediaSession, and a CPU wake lock.
     *
     * Sets audio attributes for music playback, builds an OkHttp-backed media data source factory that applies the service's default playback headers, constructs the player with media source factory and noisy-audio handling, creates the MediaSession with its callback, and acquires a partial wake lock (up to 10 minutes) to keep the CPU running during playback.
     */
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

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(EchoStreamSessionCallback())
            .build()

        // WakeLock to keep CPU running during playback
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EchoStream::PlaybackWakeLock"
        ).apply { acquire(10 * 60 * 1000L) } // max 10 minutes
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * Shuts down playback and releases playback-related resources before delegating to the superclass.
     *
     * Stops the player, releases the player and media session, and releases the CPU wake lock if held; finally calls super.onDestroy().
     */
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

    /**
     * Provides the default HTTP headers applied to media playback requests.
     *
     * @return A map of header names to values containing `User-Agent`, `Accept`, and `Accept-Language`.
     */
    private fun defaultPlaybackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "audio/webm,audio/mp4,video/webm,video/mp4,application/x-mpegurl,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private inner class EchoStreamSessionCallback : MediaSession.Callback {
        /**
         * Accepts the provided media items for addition to the session without modification.
         *
         * @param mediaSession The media session receiving the items.
         * @param controller The controller requesting the addition.
         * @param mediaItems The list of media items to add.
         * @return A future containing the same list of media items.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)

        /**
         * Provides the current media item and its resume position when playback is resumed.
         *
         * Returns a `MediaItemsWithStartPosition` that contains the current media item as the sole item,
         * a start index of 0, and a start position equal to the player's current position (clamped to zero).
         *
         * @returns A `ListenableFuture` resolving to the described `MediaItemsWithStartPosition`, or a failed future with
         * an `IllegalStateException` when no current media item is available.
         */
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
