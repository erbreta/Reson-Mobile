package org.reson.mobile

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CapturePhase { IDLE, STARTING, RECORDING, STOPPING }

internal data class StudioState(
    val loading: Boolean = true,
    val phase: CapturePhase = CapturePhase.IDLE,
    val seconds: Double = 0.0,
    val level: Float = 0f,
    val captureInfo: String = "Preferred: 48,000 Hz · mono · PCM 16-bit\n44,100 Hz fallback · unprocessed input when supported",
    val recordings: List<Recording> = emptyList(),
    val selected: Recording? = null,
    val playing: Boolean = false,
    val preparingPlayback: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val message: String? = null,
) {
    val capturing: Boolean get() = phase != CapturePhase.IDLE
}

internal class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val directory = File(application.filesDir, "recordings")
    private val names = application.getSharedPreferences("recording_names", 0)
    private val mutableState = MutableStateFlow(StudioState())
    val state = mutableState.asStateFlow()
    private var capture: CaptureSession? = null
    private var player: MediaPlayer? = null
    private var playbackEndMs: Int? = null
    private val audioManager = checkNotNull(application.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) pausePlayback()
        }.build()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Recording storage unavailable" }
                    directory.listFiles()?.filter { it.extension == "pending" }?.forEach { it.delete() }
                }
                refreshLibrary()
            } catch (e: Exception) { report("Cannot load recordings: ${e.message}") }
            finally { mutableState.update { it.copy(loading = false) } }
        }
        viewModelScope.launch {
            while (isActive) {
                val active = player
                if (active != null && state.value.playing) {
                    try {
                        val position = active.currentPosition
                        if (playbackEndMs?.let { position >= it } == true) stopPlayback()
                        else mutableState.update { it.copy(positionMs = position) }
                    }
                    catch (e: IllegalStateException) { playbackFailure("Playback unavailable: ${e.message}") }
                }
                delay(if (playbackEndMs != null) 20 else 100)
            }
        }
    }

    private suspend fun refreshLibrary(preferred: File? = null) {
        val (recordings, invalid) = withContext(Dispatchers.IO) {
            var invalid = 0
            val recordings = directory.listFiles().orEmpty().filter { it.extension == "wav" }.mapNotNull {
                try { WavFile.inspect(it).copy(displayName = names.getString(it.name, it.name) ?: it.name) } catch (_: Exception) { invalid++; null }
            }.sortedByDescending { it.file.lastModified() }
            recordings to invalid
        }
        mutableState.update { previous ->
            val selection = preferred ?: previous.selected?.file
            previous.copy(recordings = recordings,
                selected = recordings.find { it.file == selection } ?: recordings.firstOrNull(),
                message = if (invalid > 0) "$invalid incomplete or unreadable WAV file(s) excluded." else previous.message)
        }
    }

    fun rename(recording: Recording, input: String): Boolean {
        if (state.value.capturing || state.value.importing || state.value.exporting) return false
        val current = state.value.recordings.find { it.file == recording.file } ?: return false
        val name = recordingName(input, state.value.recordings.filter { it.file != current.file }.map { it.displayName })
        if (name == null) { report("Choose a unique, nonempty name without path separators."); return false }
        if (!names.edit().putString(current.file.name, name).commit()) { report("Could not save recording name."); return false }
        mutableState.update { state ->
            val updated = current.copy(displayName = name)
            state.copy(recordings = state.recordings.map { if (it.file == current.file) updated else it },
                selected = if (state.selected?.file == current.file) updated else state.selected, message = null)
        }
        return true
    }

    fun delete(recording: Recording): Boolean {
        if (state.value.capturing || state.value.importing || state.value.exporting || state.value.recordings.none { it.file == recording.file }) return false
        val file = recording.file
        if (state.value.selected?.file == file) stopPlayback()
        if (!deleteManagedRecording(directory, file)) { report("Could not delete recording."); return false }
        names.edit().remove(file.name).apply()
        mutableState.update { state ->
            val remaining = state.recordings.filterNot { it.file == file }
            state.copy(recordings = remaining, selected = if (state.selected?.file == file) remaining.firstOrNull() else state.selected, message = null)
        }
        return true
    }

    fun report(message: String?) { mutableState.update { it.copy(message = message) } }

    fun startRecording() {
        if (state.value.capturing || state.value.loading || state.value.importing) return
        stopPlayback()
        val session = CaptureSession(directory) { Microphone.open(getApplication<Application>()) }
        capture = session
        mutableState.update { it.copy(phase = CapturePhase.STARTING, seconds = 0.0, level = 0f, message = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    session.run { seconds, level, description ->
                        mutableState.update {
                            it.copy(phase = if (it.phase == CapturePhase.STOPPING) it.phase else CapturePhase.RECORDING,
                                seconds = seconds, level = level, captureInfo = description)
                        }
                    }
                }
                if (file != null) {
                    refreshLibrary(file)
                    report("Saved ${file.name}")
                }
            } catch (e: Exception) { report("Recording not saved: ${e.message}") }
            finally {
                capture = null
                mutableState.update { it.copy(phase = CapturePhase.IDLE, level = 0f) }
            }
        }
    }

    fun stopRecording() {
        val session = capture ?: return
        mutableState.update { it.copy(phase = CapturePhase.STOPPING) }
        try { session.stop() } catch (e: Exception) { report("Cannot stop microphone: ${e.message}") }
    }

    fun select(recording: Recording) {
        if (state.value.capturing) return
        stopPlayback()
        mutableState.update { it.copy(selected = recording) }
    }

    fun play(startSeconds: Double? = null, endSeconds: Double? = null) {
        if (startSeconds != null && !state.value.capturing) stopPlayback()
        val current = state.value
        if (current.capturing || current.preparingPlayback || current.playing) return
        val recording = current.selected ?: return
        if (startSeconds != null && (!startSeconds.isFinite() || endSeconds == null || !endSeconds.isFinite() ||
            startSeconds < 0 || endSeconds <= startSeconds || endSeconds > recording.durationSeconds + 0.001)) {
            report("Select a nonempty playback interval within the recording.")
            return
        }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            report("Audio output is in use. Try playback again.")
            return
        }
        try {
            val existing = player
            if (existing != null) {
                existing.start()
                mutableState.update { it.copy(playing = true) }
                return
            }
            val created = MediaPlayer()
            player = created
            playbackEndMs = endSeconds?.let { (it * 1000).toInt() }
            mutableState.update { it.copy(preparingPlayback = true, message = null) }
            created.setAudioAttributes(attributes)
            created.setDataSource(recording.file.absolutePath)
            created.setOnPreparedListener {
                if (player === it && !state.value.capturing) {
                    mutableState.update { state -> state.copy(durationMs = it.duration) }
                    if (startSeconds != null && startSeconds > 0) {
                        it.setOnSeekCompleteListener { sought ->
                            if (player === sought && !state.value.capturing) {
                                sought.start()
                                mutableState.update { state -> state.copy(playing = true, preparingPlayback = false, positionMs = sought.currentPosition) }
                            }
                        }
                        it.seekTo((startSeconds * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        it.start()
                        mutableState.update { state -> state.copy(playing = true, preparingPlayback = false) }
                    }
                }
            }
            created.setOnCompletionListener { stopPlayback() }
            created.setOnErrorListener { _, what, extra ->
                playbackFailure("Cannot play WAV ($what/$extra).")
                true
            }
            created.prepareAsync()
        } catch (e: Exception) { playbackFailure("Cannot play WAV: ${e.message}") }
    }

    fun pausePlayback() {
        if (state.value.preparingPlayback) { stopPlayback(); return }
        try {
            player?.let {
                if (it.isPlaying) it.pause()
                mutableState.update { state -> state.copy(playing = false, positionMs = it.currentPosition) }
            }
        } catch (e: Exception) { playbackFailure("Cannot pause playback: ${e.message}") }
        finally { audioManager.abandonAudioFocusRequest(focus) }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playbackEndMs = null
        audioManager.abandonAudioFocusRequest(focus)
        mutableState.update { it.copy(playing = false, preparingPlayback = false, positionMs = 0, durationMs = 0) }
    }

    private fun playbackFailure(message: String) { stopPlayback(); report(message) }

    fun importWav(uri: Uri) {
        if (state.value.capturing || state.value.loading || state.value.importing) return
        stopPlayback()
        mutableState.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val file = withContext(Dispatchers.IO) {
                    checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "Cannot read selected WAV" }.use {
                        WavImport.read(it, directory) { context.ensureActive() }
                    }
                }
                refreshLibrary(file)
                report("Imported mono PCM16 analysis copy. Stereo channels are averaged; sample rate/timing retained. Original unchanged.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("WAV import failed: ${e.message}. Original unchanged.") }
            finally { mutableState.update { it.copy(importing = false) } }
        }
    }

    fun save(file: File, uri: Uri) {
        if (state.value.exporting) return
        mutableState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val output = checkNotNull(resolver.openOutputStream(uri, "wt")) { "Destination unavailable" }
                    output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
                }
                report("WAV exported without conversion.")
            } catch (e: Exception) { report("Export failed; destination may be incomplete. Original retained. ${e.message}") }
            finally { mutableState.update { it.copy(exporting = false) } }
        }
    }

    fun background() { stopRecording(); stopPlayback() }

    override fun onCleared() {
        try { stopRecording() } finally { stopPlayback(); super.onCleared() }
    }
}
