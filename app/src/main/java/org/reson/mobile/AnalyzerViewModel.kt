package org.reson.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.*

enum class AnalysisLayer(val label: String) {
    SPECTROGRAM("Spectrogram"), PITCH("Pitch"), FORMANTS("Formants"), INTENSITY("Intensity"), PULSES("Estimated Pulses")
}

data class AnalyzerState(
    val recording: Recording? = null, val audio: AudioSignal? = null,
    val envelope: FloatArray = floatArrayOf(),
    val settings: AnalysisSettings = AnalysisSettings(), val layers: Set<AnalysisLayer> = setOf(AnalysisLayer.SPECTROGRAM),
    val view: TimeViewport = TimeViewport(0.0, 1.0), val cursor: Double = 0.0,
    val selection: ClosedFloatingPointRange<Double>? = null, val selecting: Boolean = false, val hasMeasurement: Boolean = false,
    val pitch: List<AcousticPoint>? = null, val formants: List<FormantPoint>? = null,
    val intensity: List<AcousticPoint>? = null, val pulses: List<Double>? = null,
    val spectrum: SpectralImage? = null, val bitmaps: List<Bitmap> = emptyList(),
    val detail: SpectralImage? = null, val detailBitmaps: List<Bitmap> = emptyList(),
    val busy: String? = null, val errors: Map<String, String> = emptyMap(),
)

class AnalyzerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("analysis", 0)
    private val initialSettings = runCatching { AnalysisSettings(
        preferences.getFloat("floor", 75f).toDouble(), preferences.getFloat("ceiling", 500f).toDouble(),
        preferences.getFloat("formant", 5500f).toDouble()) }.getOrDefault(AnalysisSettings())
    private val mutable = MutableStateFlow(AnalyzerState(settings = initialSettings,
        layers = AnalysisLayer.entries.filter { preferences.getBoolean(it.name, it == AnalysisLayer.SPECTROGRAM) }.toSet()))
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var analysis: Job? = null
    private var detailJob: Job? = null

    fun open(recording: Recording) {
        if (mutable.value.recording == recording) return
        loading?.cancel(); analysis?.cancel(); detailJob?.cancel()
        mutable.value = AnalyzerState(recording = recording, settings = mutable.value.settings, layers = mutable.value.layers,
            view = TimeViewport(0.0, recording.durationSeconds), busy = "Loading WAV…")
        loading = viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val (audio, envelope) = withContext(Dispatchers.IO) {
                    val audio = AudioAnalysis.load(recording) { context.ensureActive() }
                    val envelope = FloatArray(((audio.samples.size + 127) / 128) * 2)
                    for (block in envelope.indices step 2) {
                        context.ensureActive()
                        val start = block / 2 * 128
                        var low = 1f; var high = -1f
                        for (i in start until min(start + 128, audio.samples.size)) { low = min(low, audio.samples[i]); high = max(high, audio.samples[i]) }
                        envelope[block] = low; envelope[block + 1] = high
                    }
                    audio to envelope
                }
                mutable.update { it.copy(audio = audio, envelope = envelope, busy = null) }
                calculate()
                updateDetail()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = null, errors = mapOf("WAV" to (e.message ?: "Cannot load WAV"))) } }
        }
    }

    fun close() {
        loading?.cancel(); analysis?.cancel(); detailJob?.cancel()
        mutable.update { AnalyzerState(settings = it.settings, layers = it.layers) }
    }

    fun layer(layer: AnalysisLayer, enabled: Boolean) {
        preferences.edit().putBoolean(layer.name, enabled).apply()
        mutable.update { it.copy(layers = if (enabled) it.layers + layer else it.layers - layer) }
        if (enabled) calculate()
        updateDetail()
    }

    fun settings(settings: AnalysisSettings) {
        val old = mutable.value.settings
        preferences.edit().putFloat("floor", settings.pitchFloor.toFloat()).putFloat("ceiling", settings.pitchCeiling.toFloat())
            .putFloat("formant", settings.formantCeiling.toFloat()).apply()
        val pitchChanged = old.pitchFloor != settings.pitchFloor || old.pitchCeiling != settings.pitchCeiling
        val formantsChanged = old.formantCeiling != settings.formantCeiling
        mutable.update { it.copy(settings = settings,
            pitch = if (pitchChanged) null else it.pitch, pulses = if (pitchChanged) null else it.pulses,
            formants = if (formantsChanged) null else it.formants, errors = emptyMap()) }
        calculate()
    }

    fun viewport(view: TimeViewport) {
        mutable.update { it.copy(view = view) }
        updateDetail()
    }

    private fun updateDetail() {
        val state = mutable.value
        val audio = state.audio ?: return
        detailJob?.cancel()
        if (state.view.span > 0.5 || AnalysisLayer.SPECTROGRAM !in state.layers) return
        val cached = state.detail
        if (cached != null && state.view.start >= cached.firstTime - 0.003 &&
            state.view.end <= cached.firstTime + (cached.columns - 1) * cached.step + 0.003) return
        detailJob = viewModelScope.launch {
            delay(100)
            val context = currentCoroutineContext()
            val margin = state.view.span.coerceAtLeast(0.05)
            val start = (state.view.start - margin).coerceAtLeast(0.0)
            val end = (state.view.end + margin).coerceAtMost(audio.duration)
            val result = withContext(Dispatchers.Default) {
                val image = AudioAnalysis.spectrogram(audio, { context.ensureActive() }, start, end, 0.001)
                image to makeBitmaps(image) { context.ensureActive() }
            }
            mutable.update { it.copy(detail = result.first, detailBitmaps = result.second) }
        }
    }

    private fun makeBitmaps(spectrum: SpectralImage, check: () -> Unit): List<Bitmap> =
        (0 until spectrum.columns step 1024).map { base ->
            val start = max(0, base - 1)
            val width = min(spectrum.columns, base + 1025) - start
            val bitmap = Bitmap.createBitmap(width, spectrum.bins, Bitmap.Config.ARGB_8888)
            val row = IntArray(width)
            for (bin in 0 until spectrum.bins) {
                check()
                for (column in row.indices) {
                    val power = spectrum.power[(start + column) * spectrum.bins + bin].toInt() and 255
                    val grey = 255 - ((power - 51) * 255 / 179).coerceIn(0, 255)
                    row[column] = android.graphics.Color.rgb(grey, grey, grey)
                }
                bitmap.setPixels(row, 0, width, 0, spectrum.bins - 1 - bin, width, 1)
            }
            bitmap
        }
    fun cursor(time: Double) { mutable.update { it.copy(cursor = time.coerceIn(0.0, it.recording?.durationSeconds ?: 0.0), selection = null, hasMeasurement = true) } }
    fun selection(start: Double, end: Double) {
        val duration = mutable.value.recording?.durationSeconds ?: return
        val a = min(start, end).coerceIn(0.0, duration); val b = max(start, end).coerceIn(0.0, duration)
        // Imported TextGrid intervals can be shorter than the previous 100 µs UI threshold.
        mutable.update { it.copy(selection = if (b > a) a..b else null, cursor = b, hasMeasurement = true) }
    }
    fun selecting(value: Boolean) { mutable.update { it.copy(selecting = value) } }

    private fun calculate() {
        val audio = mutable.value.audio ?: return
        analysis?.cancel()
        analysis = viewModelScope.launch {
            val context = currentCoroutineContext()
            val check = { context.ensureActive() }
            val settings = mutable.value.settings
            suspend fun <T> compute(layer: AnalysisLayer, needed: Boolean, work: () -> T, publish: (AnalyzerState, T) -> AnalyzerState) {
                if (!needed) return
                mutable.update { it.copy(busy = "Analysing ${layer.label.lowercase()}…", errors = it.errors - layer.label) }
                try {
                    val result = withContext(Dispatchers.Default) { work() }
                    mutable.update { publish(it, result) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(errors = it.errors + (layer.label to (e.message ?: "Unavailable"))) } }
            }
            try {
                val s = mutable.value
                compute(AnalysisLayer.SPECTROGRAM, AnalysisLayer.SPECTROGRAM in s.layers && s.spectrum == null, {
                    val spectrum = AudioAnalysis.spectrogram(audio, check)
                    val bitmaps = makeBitmaps(spectrum, check)
                    spectrum to bitmaps
                }) { state, result -> state.copy(spectrum = result.first, bitmaps = result.second) }
                compute(AnalysisLayer.PITCH, (AnalysisLayer.PITCH in s.layers || AnalysisLayer.PULSES in s.layers) && s.pitch == null,
                    { AudioAnalysis.pitch(audio, settings, check) }) { state, result -> state.copy(pitch = result) }
                compute(AnalysisLayer.FORMANTS, AnalysisLayer.FORMANTS in s.layers && s.formants == null,
                    { AudioAnalysis.formants(audio, settings, check) }) { state, result -> state.copy(formants = result) }
                compute(AnalysisLayer.INTENSITY, AnalysisLayer.INTENSITY in s.layers && s.intensity == null,
                    { AudioAnalysis.intensity(audio, check) }) { state, result -> state.copy(intensity = result) }
                compute(AnalysisLayer.PULSES, AnalysisLayer.PULSES in s.layers && s.pulses == null && mutable.value.pitch != null,
                    { AudioAnalysis.pulses(audio, mutable.value.pitch.orEmpty(), check) }) { state, result -> state.copy(pulses = result) }
            } finally { if (context.isActive) mutable.update { it.copy(busy = null) } }
        }
    }
}
