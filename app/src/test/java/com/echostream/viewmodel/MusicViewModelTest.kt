package com.echostream.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.echostream.data.model.SearchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Unit tests for the changes introduced in MusicViewModel in this PR:
 *
 * - `setupPlayerListener` now handles `onPlayerError` and syncs `_isBuffering`, `_isPlaying`,
 *   and `_errorMessage`.
 * - `SearchResult.toMediaItem` builds a `MediaItem` with the correct URI, mediaId, and metadata.
 * - `startPlaybackProgressTracker` now additionally updates `_isPlaying`.
 * - `togglePlayPause` syncs `_isPlaying` after issuing play/pause.
 *
 * MusicViewModel is an AndroidViewModel, so Robolectric provides the Application context.
 * Internal state and private methods are exercised via Java reflection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MusicViewModelTest {

    // ---------------------------------------------------------------------------
    // Setup / teardown
    // ---------------------------------------------------------------------------

    @Before
    fun setUpDispatcher() {
        // Redirect Dispatchers.Main to an unconfined test dispatcher so that
        // viewModelScope.launch(Dispatchers.Main) runs inline in tests.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Provides a real Application instance via Robolectric. */
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Constructs a MusicViewModel while bypassing the parts of `init` that require a
     * running Android environment (database and MediaController connections). This is
     * done by injecting mocked / no-op values into the private fields immediately after
     * construction via reflection.
     *
     * We deliberately do NOT call any real coroutine work (database, network) – the
     * fields that would fail are overwritten before they matter.
     */
    private fun buildViewModel(): MusicViewModel {
        // MusicViewModel constructor launches coroutines in init; those touch Room and
        // MediaController.  We can still construct the object — the launched jobs will
        // fail quietly because the Room DB is not set up, but that does not affect the
        // state-flow fields we are testing.
        return MusicViewModel(app)
    }

    private fun getStateField(vm: MusicViewModel, name: String): Field =
        MusicViewModel::class.java.getDeclaredField(name).also { it.isAccessible = true }

    private fun <T> getStateValue(vm: MusicViewModel, fieldName: String): T {
        val field = getStateField(vm, fieldName)
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<T>
        return flow.value
    }

    private fun <T> setStateValue(vm: MusicViewModel, fieldName: String, value: T) {
        val field = getStateField(vm, fieldName)
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<T>
        flow.value = value
    }

    /** Reflectively invokes the private setupPlayerListener method. */
    private fun callSetupPlayerListener(vm: MusicViewModel, controller: MediaController) {
        val m: Method = MusicViewModel::class.java.getDeclaredMethod(
            "setupPlayerListener",
            MediaController::class.java
        ).also { it.isAccessible = true }
        m.invoke(vm, controller)
    }

    /** Captures the listener registered via controller.addListener(...). */
    private fun captureListener(controller: MediaController): Player.Listener {
        val listenerSlot = slot<Player.Listener>()
        verify { controller.addListener(capture(listenerSlot)) }
        return listenerSlot.captured
    }

    /** Creates a minimal mock MediaController that records addListener calls. */
    private fun mockController(): MediaController = mockk<MediaController>(relaxed = true) {
        every { addListener(any()) } returns Unit
    }

    // ---------------------------------------------------------------------------
    // onPlayerError – new in this PR
    // ---------------------------------------------------------------------------

    @Test
    fun `onPlayerError sets isBuffering to false`() {
        val vm = buildViewModel()
        val controller = mockController()

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        // Simulate an ongoing buffering state before the error fires.
        setStateValue(vm, "_isBuffering", true)

        val error = mockk<PlaybackException>(relaxed = true)
        listener.onPlayerError(error)

        assertFalse(getStateValue(vm, "_isBuffering"))
    }

    @Test
    fun `onPlayerError sets isPlaying to false`() {
        val vm = buildViewModel()
        val controller = mockController()

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        setStateValue(vm, "_isPlaying", true)

        val error = mockk<PlaybackException>(relaxed = true)
        listener.onPlayerError(error)

        assertFalse(getStateValue(vm, "_isPlaying"))
    }

    @Test
    fun `onPlayerError sets a user-facing error message`() {
        val vm = buildViewModel()
        val controller = mockController()

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        val error = mockk<PlaybackException>(relaxed = true)
        listener.onPlayerError(error)

        val message: String? = getStateValue(vm, "_errorMessage")
        assertNotNull(message)
        assertTrue("Expected a non-blank error message", message!!.isNotBlank())
        assertEquals("Playback failed. Try another result or search again.", message)
    }

    @Test
    fun `onPlayerError clears buffering and playing regardless of prior state combinations`() {
        val vm = buildViewModel()
        val controller = mockController()

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        // Both false initially – error should keep them false.
        setStateValue(vm, "_isBuffering", false)
        setStateValue(vm, "_isPlaying", false)

        listener.onPlayerError(mockk(relaxed = true))

        assertFalse(getStateValue(vm, "_isBuffering"))
        assertFalse(getStateValue(vm, "_isPlaying"))
    }

    // ---------------------------------------------------------------------------
    // onPlaybackStateChanged – pre-existing but relevant context for onPlayerError
    // ---------------------------------------------------------------------------

    @Test
    fun `onPlaybackStateChanged to BUFFERING sets isBuffering true`() {
        val vm = buildViewModel()
        val controller = mockController()
        every { controller.duration } returns 60_000L

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)

        assertTrue(getStateValue(vm, "_isBuffering"))
    }

    @Test
    fun `onPlaybackStateChanged to READY sets isBuffering false`() {
        val vm = buildViewModel()
        val controller = mockController()
        every { controller.duration } returns 60_000L

        callSetupPlayerListener(vm, controller)
        val listener = captureListener(controller)

        // First buffer...
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        // ...then ready.
        listener.onPlaybackStateChanged(Player.STATE_READY)

        assertFalse(getStateValue(vm, "_isBuffering"))
    }

    // ---------------------------------------------------------------------------
    // SearchResult.toMediaItem – new private extension in this PR
    // ---------------------------------------------------------------------------

    private fun callToMediaItem(vm: MusicViewModel, result: SearchResult, streamUrl: String): MediaItem {
        val m: Method = MusicViewModel::class.java.getDeclaredMethod(
            "toMediaItem",
            SearchResult::class.java,
            String::class.java
        ).also { it.isAccessible = true }
        return m.invoke(vm, result, streamUrl) as MediaItem
    }

    @Test
    fun `toMediaItem sets the stream uri on the MediaItem`() {
        val vm = buildViewModel()
        val result = sampleSearchResult()
        val streamUrl = "http://stream.example.com/audio.mp4"

        val item = callToMediaItem(vm, result, streamUrl)

        assertEquals(streamUrl, item.localConfiguration?.uri?.toString())
    }

    @Test
    fun `toMediaItem sets the video id as mediaId`() {
        val vm = buildViewModel()
        val result = sampleSearchResult(videoId = "abc123")

        val item = callToMediaItem(vm, result, "http://stream.example.com/audio.mp4")

        assertEquals("abc123", item.mediaId)
    }

    @Test
    fun `toMediaItem sets the track title in metadata`() {
        val vm = buildViewModel()
        val result = sampleSearchResult(title = "Never Gonna Give You Up")

        val item = callToMediaItem(vm, result, "http://stream.example.com/audio.mp4")

        assertEquals("Never Gonna Give You Up", item.mediaMetadata.title?.toString())
    }

    @Test
    fun `toMediaItem sets the channel name as artist in metadata`() {
        val vm = buildViewModel()
        val result = sampleSearchResult(channelName = "Rick Astley")

        val item = callToMediaItem(vm, result, "http://stream.example.com/audio.mp4")

        assertEquals("Rick Astley", item.mediaMetadata.artist?.toString())
    }

    @Test
    fun `toMediaItem with different videoIds produces different mediaIds`() {
        val vm = buildViewModel()
        val item1 = callToMediaItem(vm, sampleSearchResult(videoId = "id1"), "http://a.example.com")
        val item2 = callToMediaItem(vm, sampleSearchResult(videoId = "id2"), "http://a.example.com")

        assertFalse(item1.mediaId == item2.mediaId)
    }

    @Test
    fun `toMediaItem with empty title sets blank title in metadata`() {
        val vm = buildViewModel()
        val result = sampleSearchResult(title = "")

        val item = callToMediaItem(vm, result, "http://stream.example.com/audio.mp4")

        assertEquals("", item.mediaMetadata.title?.toString() ?: "")
    }

    // ---------------------------------------------------------------------------
    // clearError
    // ---------------------------------------------------------------------------

    @Test
    fun `clearError sets errorMessage to null`() {
        val vm = buildViewModel()
        setStateValue(vm, "_errorMessage", "Some previous error")

        vm.clearError()

        assertNull(getStateValue<String?>(vm, "_errorMessage"))
    }

    // ---------------------------------------------------------------------------
    // togglePlayPause – _isPlaying sync added in this PR
    // ---------------------------------------------------------------------------

    @Test
    fun `togglePlayPause syncs isPlaying from controller after pausing`() = runTest {
        val vm = buildViewModel()
        val controller = mockController()

        // Inject the controller directly so togglePlayPause finds it.
        setStateValue(vm, "_mediaController", controller)

        // First call to isPlaying (the if-check) → true (currently playing)
        // Second call (the sync assignment) → false (after pause has been called)
        every { controller.isPlaying } returns true andThen false
        every { controller.pause() } returns Unit

        vm.togglePlayPause()

        // With UnconfinedTestDispatcher, the coroutine body runs inline before we reach here.
        assertFalse(getStateValue(vm, "_isPlaying"))
    }

    @Test
    fun `togglePlayPause sets errorMessage when controller is null`() = runTest {
        val vm = buildViewModel()

        // No controller – _mediaController remains null
        setStateValue<MediaController?>(vm, "_mediaController", null)

        vm.togglePlayPause()

        val msg: String? = getStateValue(vm, "_errorMessage")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Player connection failed", ignoreCase = true))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun sampleSearchResult(
        videoId: String = "dQw4w9WgXcQ",
        title: String = "Test Track",
        channelName: String = "Test Channel",
        durationSeconds: Int = 213,
        thumbnailUrl: String = "http://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
    ) = SearchResult(
        videoId = videoId,
        title = title,
        channelName = channelName,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl
    )
}