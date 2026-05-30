package com.echostream.viewmodel

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
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

                _mediaController.value?.let { controller ->
                    controller.setMediaItem(MediaItem.fromUri(url))
                    controller.prepare()
                    controller.play()
                } ?: run {
                    _errorMessage.value = "Player connection failed. Restart the app."
                    _isBuffering.value = false
                }
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "Playback failed. Try again."
                _isBuffering.value = false
            }
        }
    }

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
            } ?: run {
                _errorMessage.value = "Player connection failed. Restart the app."
            }
        }
    }

    fun seekTo(positionMs: Long) {

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
