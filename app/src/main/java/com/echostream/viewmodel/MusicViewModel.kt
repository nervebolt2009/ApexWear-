package com.echostream.viewmodel

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val trackDao: TrackDao = AppDatabase.getDatabase(application).trackDao()
    private val invidiousClient = InvidiousClient()
    private val controllerFuture = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, MusicPlaybackService::class.java))
    ).buildAsync()

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

    private var progressJob: Job? = null

    init {
        controllerFuture.addListener(
            {
                _mediaController.value = controllerFuture.get()
                startPlaybackProgressTracker()
            },
            ContextCompat.getMainExecutor(application)
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                invidiousClient.initialize()
            }
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                trackDao.getAllTracks().collect { tracks ->
                    _savedTracks.value = tracks
                    updateCurrentTrackSavedState()
                }
            }
        }
    }

    fun performSearch(query: String) {
        _isSearchLoading.value = true
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    invidiousClient.searchVideos(query)
                }
                _searchResults.value = results
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
                    controller.playWhenReady = true
                    _isPlaying.value = true
                } ?: run {
                    _errorMessage.value = "Player is not ready yet. Try again."
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
                _isPlaying.value = controller.isPlaying
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
        _mediaController.value?.release()
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
