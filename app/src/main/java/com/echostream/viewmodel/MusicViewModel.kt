package com.echostream.viewmodel

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.echostream.data.db.AppDatabase
import com.echostream.data.db.TrackDao
import com.echostream.data.db.TrackEntity
import com.echostream.data.model.SearchResult
import com.echostream.data.model.Track
import com.echostream.network.InvidiousClient
import com.echostream.player.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val trackDao: TrackDao by lazy { AppDatabase.getDatabase(getApplication()).trackDao() }
    private val invidiousClient = InvidiousClient()

    private val _mediaController = MutableStateFlow<MediaController?>(null)

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _savedTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val savedTracks: StateFlow<List<TrackEntity>> = _savedTracks

    private val _isTrackSaved = MutableStateFlow(false)
    val isTrackSaved: StateFlow<Boolean> = _isTrackSaved

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var progressJob: Job? = null

    init {
        try {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        invidiousClient.initialize()
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Invidious init failed: ${e.message}", e)
                    }
                }
            }

            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        trackDao.getAllTracks().collect { tracks ->
                            _savedTracks.value = tracks
                            updateCurrentTrackSavedState()
                        }
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Saved tracks load failed: ${e.message}", e)
                    }
                }
            }

            connectMediaController()
        } catch (e: Exception) {
            Log.e("MusicViewModel", "ViewModel init crashed: ${e.message}", e)
            _errorMessage.value = "Startup error. Please restart the app."
        }
    }

    private fun connectMediaController() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val token = SessionToken(
                    getApplication(),
                    ComponentName(getApplication(), MusicPlaybackService::class.java)
                )
                val future = MediaController.Builder(getApplication<Application>(), token).buildAsync()
                controllerFuture = future
                future.addListener(
                    {
                        try {
                            val controller = future.get()
                            _mediaController.value = controller
                            setupPlayerListener(controller)
                            startPlaybackProgressTracker()
                        } catch (e: Exception) {
                            Log.e("MusicViewModel", "MediaController failed: ${e.message}", e)
                            _errorMessage.value = "Player connection failed. Restart the app."
                        }
                    },
                    getApplication<Application>().mainExecutor
                )
            } catch (e: Exception) {
                Log.e("MusicViewModel", "MediaController failed: ${e.message}", e)
                _errorMessage.value = "Player connection failed. Restart the app."
            }
        }
    }


    /**
     * Attaches a Player.Listener to the given MediaController that keeps the view-model's playback state (buffering, playing, duration, and error message) in sync with player events.
     *
     * @param controller The MediaController to attach the listener to.
     */
    private fun setupPlayerListener(controller: MediaController) {
        controller.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _isBuffering.value = playbackState == Player.STATE_BUFFERING
                    _duration.value = controller.duration.coerceAtLeast(0L)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("MusicViewModel", "Playback failed: ${error.message}", error)
                    _isBuffering.value = false
                    _isPlaying.value = false
                    _errorMessage.value = "Playback failed. Try another result or search again."
                }
            }
        )
    }

    fun performSearch(query: String) {
        _isSearchLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    invidiousClient.searchVideos(query)
                }
                _searchResults.value = results
                if (results.isEmpty()) {
                    _errorMessage.value = "No results found. Check the watch internet connection and try again."
                }
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "Search failed. Try again."
            } finally {
                _isSearchLoading.value = false
            }
        }
    }

    /**
     * Starts playback of the given search result's audio stream.
     *
     * Sets the current track and buffering state, attempts to obtain the audio stream URL and a media controller,
     * and begins playback. Updates the playing and buffering state and sets a user-facing error message if the stream
     * or player controller cannot be obtained or if playback fails.
     *
     * @param result The search result whose audio should be played.
     */
    fun playTrack(result: SearchResult) {
        _isBuffering.value = true
        _currentTrack.value = result.toTrack()
        updateCurrentTrackSavedState()

        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    invidiousClient.fetchAudioStreamUrl(result.videoId)
                }
                if (url == null) {
                    _errorMessage.value = "Could not load stream. Try another song."
                    _isBuffering.value = false
                    return@launch
                }

                val controller = getControllerForPlayback()
                if (controller == null) {
                    _errorMessage.value = "Player connection failed. Restart the app."
                    _isBuffering.value = false
                    return@launch
                }

                controller.setMediaItem(result.toMediaItem(url))
                controller.prepare()
                controller.play()
                _isPlaying.value = controller.isPlaying
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "Playback failed. Try again."
                _isBuffering.value = false
            }
        }
    }

    /**
     * Ensures a MediaController is available for playback by returning the existing controller or waiting up to 5 seconds for the async controller build; when obtained, stores it and initializes the player listener and playback progress tracker.
     *
     * @return The prepared `MediaController` if available, `null` otherwise.
     */
    private suspend fun getControllerForPlayback(): MediaController? {
        _mediaController.value?.let { return it }
        return try {
            val controller = withContext(Dispatchers.IO) {
                controllerFuture?.get(5, TimeUnit.SECONDS)
            }
            if (controller != null) {
                _mediaController.value = controller
                setupPlayerListener(controller)
                startPlaybackProgressTracker()
            }
            controller
        } catch (error: Exception) {
            Log.e("MusicViewModel", "MediaController unavailable for playback", error)
            null
        }
    }

    /**
         * Builds a MediaItem for this SearchResult using the provided audio stream URL.
         *
         * @param streamUrl The audio stream URI to use for playback.
         * @return A configured MediaItem whose URI is `streamUrl`, whose mediaId is this result's `videoId`, and whose metadata contains the result's `title` and `channelName` as artist.
         */
        private fun SearchResult.toMediaItem(streamUrl: String): MediaItem = MediaItem.Builder()
        .setUri(streamUrl)
        .setMediaId(videoId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(channelName)
                .build()
        )
        .build()

    /**
     * Starts a periodic tracker that polls the current MediaController and updates playback state flows.
     *
     * Every 500 milliseconds, if a controller is available the tracker updates:
     * - current playback position,
     * - duration (clamped to at least 0),
     * - whether playback is active,
     * - whether the player is buffering.
     *
     * Cancels any existing tracker before starting a new one.
     */
    private fun startPlaybackProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                _mediaController.value?.let { controller ->
                    if (controller.isPlaying) {
                        _currentPosition.value = controller.currentPosition
                        _duration.value = controller.duration.coerceAtLeast(0L)
                    }
                    _isPlaying.value = controller.isPlaying
                    _isBuffering.value = controller.playbackState == Player.STATE_BUFFERING
                }
                delay(500L)
            }
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch(Dispatchers.Main) {
            _mediaController.value?.let { controller ->
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
                _isPlaying.value = controller.isPlaying
            } ?: run {
                _errorMessage.value = "Player connection failed. Restart the app."
            }
        }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch(Dispatchers.Main) {
            _mediaController.value?.seekTo(positionMs)
        }
    }

    fun skipToNext() {
        viewModelScope.launch(Dispatchers.Main) {
            _mediaController.value?.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        viewModelScope.launch(Dispatchers.Main) {
            _mediaController.value?.seekToPreviousMediaItem()
        }
    }

    fun saveTrack(result: SearchResult) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                trackDao.insertTrack(
                    TrackEntity(
                        videoId = result.videoId,
                        title = result.title,
                        channelName = result.channelName,
                        durationSeconds = result.durationSeconds,
                        thumbnailUrl = result.thumbnailUrl
                    )
                )
            }
            updateCurrentTrackSavedState()
        }
    }

    fun deleteTrack(entity: TrackEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                trackDao.deleteTrack(entity)
            }
            updateCurrentTrackSavedState()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun updateCurrentTrackSavedState() {
        val videoId = _currentTrack.value?.videoId
        _isTrackSaved.value = videoId != null && _savedTracks.value.any { it.videoId == videoId }
    }

    override fun onCleared() {
        progressJob?.cancel()
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        } ?: _mediaController.value?.release()
        super.onCleared()
    }
}
