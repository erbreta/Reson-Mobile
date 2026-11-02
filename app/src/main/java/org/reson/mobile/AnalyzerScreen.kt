package org.reson.mobile

import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnalyzerScreen(model: AnalyzerViewModel, studio: StudioState, theme: String, onThemeChange: (String) -> Unit, onBack: () -> Unit,
    onPlay: (Double, Double) -> Unit, onStop: () -> Unit, onIpa: () -> Unit,
    grid: TextGridViewModel, onOpenGrid: () -> Unit, onGridAudio: (Recording) -> Unit, onGridStandalone: () -> Unit) {
    val state by model.state.collectAsState()
    val annotations by grid.state.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }
    var layersOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var measurementsOpen by remember { mutableStateOf(true) }
    val duration = state.recording?.durationSeconds ?: 0.0
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = onBack) { Text("‹ Studio") }
                Box {
                    TextButton(onClick = { layersOpen = true }) { Text("Layers") }
                    DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                        AnalysisLayer.entries.forEach { layer ->
                            DropdownMenuItem(text = { Text("Show ${layer.label}") },
                                leadingIcon = { Checkbox(checked = layer in state.layers, onCheckedChange = null) },
                                onClick = { model.layer(layer, layer !in state.layers) })
                        }
                    }
                }
                TextButton(onClick = { sheet = "Settings" }) { Text("Settings") }
                Box {
                    TextButton(onClick = { moreOpen = true }) { Text("More") }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("IPA Studio") }, onClick = { moreOpen = false; onIpa() })
                        DropdownMenuItem(text = { Text("Open TextGrid") }, onClick = { moreOpen = false; onOpenGrid() })
                        DropdownMenuItem(text = { Text("TextGrid Viewer") }, onClick = { moreOpen = false; onGridStandalone() })
                    }
                }
            }
            Text("Acoustic analyser", style = MaterialTheme.typography.titleLarge)
            Text(state.recording?.file?.name.orEmpty(), style = MaterialTheme.typography.labelSmall)
            state.busy?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.errors.forEach { (layer, error) -> Text("$layer: $error", color = MaterialTheme.colorScheme.error) }
            if (state.audio != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.selecting, onClick = { model.selecting(false) }, label = { Text("Navigate") })
                    FilterChip(selected = state.selecting, onClick = { model.selecting(true) }, label = { Text("Select") })
                    TextButton(onClick = { model.viewport(TimeViewport(0.0, duration)) }) { Text("Fit") }
                    TextButton(onClick = { model.viewport(state.view.transform(duration, 2.0, 0.0, 0.5)) }) { Text("Zoom +") }
                    TextButton(onClick = { model.viewport(state.view.transform(duration, 0.5, 0.0, 0.5)) }) { Text("Zoom −") }
                }
                Text(if (state.selecting) "Drag a range or its endpoints. Pinch to zoom. Tap for a cursor."
                    else "Tap for a cursor. Drag to pan; pinch to zoom.", style = MaterialTheme.typography.bodySmall)
                AnalysisPlot(state, studio, model)
                if (annotations.audioPath == state.recording?.file?.absolutePath && annotations.document != null) {
                    TextGridPanel(grid, state.view, state.cursor, studio.recordings, state.recording, onOpenGrid, onGridAudio,
                        onStandalone = onGridStandalone,
                        onSelect = { tier, item ->
                            if (tier.type == "IntervalTier") {
                                val range = item.audioRange(duration)
                                if (range != null) model.selection(range.start, range.endInclusive) else model.cursor(state.cursor)
                            } else if (item.start.seconds in 0.0..duration) model.cursor(item.start.seconds)
                        },
                        onNavigate = { zoom, pan, anchor -> model.viewport(state.view.transform(duration, zoom, pan, anchor)) },
                        onZoom = { model.viewport(annotationView(it, TimeViewport(0.0, duration))) },
                        onPlay = { it.audioRange(duration)?.let { range -> onPlay(range.start, range.endInclusive) } })
                } else {
                    if (annotations.loading) Text("Reading TextGrid…")
                    annotations.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${number(state.view.start, 3)} s"); Text("${number(state.view.end, 3)} s")
                }
                if (AnalysisLayer.PITCH in state.layers) Text("Blue pitch: ${number(state.settings.pitchFloor, 0)}–${number(state.settings.pitchCeiling, 0)} Hz · independent scale",
                    style = MaterialTheme.typography.labelSmall)
                if (AnalysisLayer.INTENSITY in state.layers) Text("Yellow-green intensity: −80 to 0 dBFS · independent scale",
                    style = MaterialTheme.typography.labelSmall)
                Text("Spectrogram/formants: 0–5,500 Hz (bottom–top). Waveform: ±1 full scale.", style = MaterialTheme.typography.labelSmall)
                if (AnalysisLayer.PULSES in state.layers) Text(if (state.view.span > 0.5) "Estimated Pulses: zoom to ≤500 ms to see markers."
                    else "Estimated Pulses: correlated cycles in high-confidence voiced regions.", style = MaterialTheme.typography.labelSmall)
                Text("Cursor ${number(state.cursor, 3)} s", style = MaterialTheme.typography.labelMedium)
                Slider(value = state.cursor.toFloat(), onValueChange = { model.cursor(it.toDouble()) }, valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Time cursor in seconds" })
                state.selection?.let { range ->
                    Text("Selection ${number(range.start, 3)}–${number(range.endInclusive, 3)} s · ${number(range.endInclusive - range.start, 3)} s")
                    RangeSlider(value = range.start.toFloat()..range.endInclusive.toFloat(),
                        onValueChange = { model.selection(it.start.toDouble(), it.endInclusive.toDouble()) }, valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "Selection start and end in seconds" })
                    TextButton(onClick = { model.cursor(state.cursor) }) { Text("Clear selection") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val selected = state.selection
                        onPlay(selected?.start ?: state.cursor, selected?.endInclusive ?: duration)
                    }, enabled = !studio.preparingPlayback && !studio.playing && (state.selection != null || state.cursor < duration)) {
                        Text(if (studio.preparingPlayback) "Preparing…" else if (state.selection != null) "Play selection" else "Play from cursor")
                    }
                    OutlinedButton(onClick = onStop, enabled = studio.playing || studio.preparingPlayback) { Text("Stop") }
                }
                studio.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.hasMeasurement) {
                    TextButton(onClick = { measurementsOpen = !measurementsOpen }) {
                        Text(if (measurementsOpen) "Measurements (collapse)" else "Measurements (expand)")
                    }
                    if (measurementsOpen) Measurements(state)
                }
            }
        }
    }
    sheet?.let { title ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "Light", "Dark").forEach { option ->
                        FilterChip(selected = theme == option, onClick = { onThemeChange(option) }, label = { Text(option) })
                    }
                }
                Text("Acoustic analysis", style = MaterialTheme.typography.titleMedium)
                    var floor by remember(state.settings) { mutableStateOf(number(state.settings.pitchFloor, 0)) }
                    var ceiling by remember(state.settings) { mutableStateOf(number(state.settings.pitchCeiling, 0)) }
                    var invalid by remember { mutableStateOf(false) }
                    OutlinedTextField(value = floor, onValueChange = { floor = it }, label = { Text("Pitch floor (50–300 Hz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = ceiling, onValueChange = { ceiling = it }, label = { Text("Pitch ceiling (150–800 Hz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        val settings = runCatching { state.settings.copy(pitchFloor = floor.toDouble(), pitchCeiling = ceiling.toDouble()) }.getOrNull()
                        invalid = settings == null
                        settings?.let(model::settings)
                    }) { Text("Apply pitch range") }
                    if (invalid) Text("Enter finite values in range; ceiling must exceed floor.", color = MaterialTheme.colorScheme.error)
                    Text("Formant ceiling")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5000.0, 5500.0).forEach { ceilingHz ->
                            FilterChip(selected = state.settings.formantCeiling == ceilingHz,
                                onClick = { model.settings(state.settings.copy(formantCeiling = ceilingHz)) }, label = { Text("${ceilingHz.toInt()} Hz") })
                        }
                    }
                    TextButton(onClick = { model.settings(AnalysisSettings()); invalid = false }) { Text("Restore acoustic defaults") }
                    Text("Burg LPC · 5 formants · display F1–F4 · 25 ms window parameter (50 ms support) · 50 Hz pre-emphasis.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Measurements(state: AnalyzerState) {
    val selection = state.selection
    val start = selection?.start ?: state.cursor
    val end = selection?.endInclusive ?: start
    val interval = selection != null
    val measurementView = TimeViewport(start - 0.006, end + 0.006)
    val formantTracks = remember(state.formants, start, end) {
        val frames = state.formants?.visible(measurementView) { it.time }
        (0..3).map { i -> frames?.map { AcousticPoint(it.time, it.hz.getOrNull(i)) } }
    }
    val pitch = state.pitch?.visible(measurementView) { it.time }
    val intensityTrack = state.intensity?.visible(measurementView) { it.time }
    @Composable fun measurementRow(label: String, first: String, second: String? = null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(first, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (second != null) Text(second, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    fun summary(values: List<AcousticPoint>?, minimum: Double, median: Boolean = false): Double? {
        if (values == null || (interval && end - start < minimum)) return null
        return if (!interval) AnalysisMeasurements.at(values, start)
            else if (median) AnalysisMeasurements.median(values, start, end)
            else AnalysisMeasurements.mean(values, start, end)
    }
    if (interval) {
        Text("Selected interval: ${number(start, 3)}–${number(end, 3)} s", style = MaterialTheme.typography.titleMedium)
        Text("Duration ${number(end - start, 3)} s")
        if (end - start < max(0.05, 3 / state.settings.pitchFloor))
            Text("Short selection: some estimates need more signal and are unavailable.", style = MaterialTheme.typography.bodySmall)
        measurementRow("Parameter", "Mean", "Median")
    } else {
        Text("Cursor measurements", style = MaterialTheme.typography.titleMedium)
        measurementRow("Time", "${number(start, 3)} s")
    }
    fun display(value: Double?, decimals: Int, unit: String) = if (value == null) "—" else "${number(value, decimals)} $unit"
    val pitchMinimum = 3 / state.settings.pitchFloor
    measurementRow("Pitch (F0)", display(summary(pitch, pitchMinimum), 1, "Hz"),
        if (interval) display(summary(pitch, pitchMinimum, true), 1, "Hz") else null)
    for (i in 0..3) {
        val values = formantTracks[i]
        measurementRow("F${i + 1}", display(summary(values, 0.05), 0, "Hz"),
            if (interval) display(summary(values, 0.05, true), 0, "Hz") else null)
    }
    if (interval) {
        val frames = pitch?.filter { it.time in start..end }
        val valid = frames?.mapNotNull { it.value }
        Text(if (frames == null) "Valid pitch frames: —" else "Valid pitch frames: ${valid!!.size} / ${frames.size}" +
            if (frames.isEmpty()) "" else " (${number(100.0 * valid!!.size / frames.size, 0)}% voiced)")
        val usable = valid?.takeIf { end - start >= pitchMinimum && it.isNotEmpty() }
        Text("Pitch range: ${if (usable == null) "—" else "${number(usable.min(), 1)}–${number(usable.max(), 1)} Hz"}")
    }
    val intensity = if (interval) {
        if (end - start >= 0.04) intensityTrack?.let { AnalysisMeasurements.meanPower(it, start, end) } else null
    } else intensityTrack?.let { AnalysisMeasurements.at(it, start) }
    measurementRow(if (interval) "Mean intensity" else "Intensity", display(intensity, 1, "dBFS"))
    Text("— means unavailable. Enable a layer to calculate its track. Intensity is uncalibrated; interval intensity averages linear power. Formant summaries describe estimated frames, not necessarily steady-state vowels.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AnalysisPlot(state: AnalyzerState, studio: StudioState, model: AnalyzerViewModel) {
    val latest by rememberUpdatedState(state)
    val foreground = MaterialTheme.colorScheme.onSurface
    val cursorColor = MaterialTheme.colorScheme.primary
    val lightPlot = (AnalysisLayer.SPECTROGRAM in state.layers && state.bitmaps.isNotEmpty()) || foreground.luminance() < 0.5f
    val pitchColor = if (lightPlot) Color(0xFF005CC5) else Color(0xFF66ADFF)
    val formantColor = if (lightPlot) Color(0xFFB62B16) else Color(0xFFFF865D)
    val intensityColor = if (lightPlot) Color(0xFF586600) else Color(0xFFCBDE57)
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxWidth().height(320.dp).semantics {
        contentDescription = "Waveform above spectrogram, shared time axis. Use time cursor and selection sliders for measurements."
    }.pointerInput(state.selecting, state.recording) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val initial = latest
            val start = initial.view.timeAt(down.position.x.toDouble() / size.width)
            val range = initial.selection
            val touch = 24.dp.toPx() / size.width * initial.view.span
            val anchor = when {
                range != null && abs(start - range.start) < touch -> range.endInclusive
                range != null && abs(start - range.endInclusive) < touch -> range.start
                else -> start
            }
            var moved = false
            var transforming = false
            var travel = Offset.Zero
            do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (event.changes.count { it.pressed } > 1) transforming = true
                travel += pan
                if (travel.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.001f) moved = true
                if (moved && event.changes.any { it.pressed }) {
                    val current = latest
                    if (transforming || !current.selecting) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        model.viewport(current.view.transform(current.audio!!.duration, zoom.toDouble(),
                            pan.x.toDouble() / size.width, (centroid.x / size.width).toDouble().coerceIn(0.0, 1.0)))
                    } else {
                        val position = event.changes.first().position.x.toDouble() / size.width
                        model.selection(anchor, current.view.timeAt(position))
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (!moved) model.cursor(start)
        }
    }) {
        val audio = state.audio ?: return@Canvas
        val view = state.view
        fun x(time: Double) = (view.fraction(time) * size.width).toFloat()
        val waveHeight = size.height * 0.29f
        val spectralTop = size.height * 0.34f
        val spectralHeight = size.height - spectralTop
        fun frequencyY(hz: Double) = size.height - (hz / 5500 * spectralHeight).toFloat()
        clipRect {
            val detail = state.detail?.takeIf { view.span <= 0.5 && state.detailBitmaps.isNotEmpty() &&
                view.start >= it.firstTime - 0.003 && view.end <= it.firstTime + (it.columns - 1) * it.step + 0.003 }
            val spectrum = detail ?: state.spectrum
            val bitmaps = if (detail != null) state.detailBitmaps else state.bitmaps
            if (AnalysisLayer.SPECTROGRAM in state.layers && spectrum != null) bitmaps.forEachIndexed { tile, bitmap ->
                val edge = spectrum.firstTime - spectrum.step / 2
                val base = tile * 1024
                val start = max(0, base - 1)
                val end = min(spectrum.columns, base + 1024)
                if (edge + end * spectrum.step < view.start || edge + base * spectrum.step > view.end) return@forEachIndexed
                val canvas = drawContext.canvas.nativeCanvas
                val save = canvas.save()
                canvas.clipRect(x(edge + base * spectrum.step), spectralTop, x(edge + end * spectrum.step), size.height)
                canvas.drawBitmap(bitmap, null,
                    RectF(x(edge + start * spectrum.step), frequencyY((spectrum.bins - 0.5) * spectrum.frequencyStep),
                        x(edge + (start + bitmap.width) * spectrum.step), frequencyY(-spectrum.frequencyStep / 2)), bitmapPaint)
                canvas.restoreToCount(save)
            }
            state.selection?.let {
                drawRect(cursorColor.copy(alpha = 0.15f), Offset(x(it.start), 0f), Size(x(it.endInclusive) - x(it.start), size.height))
                for (t in listOf(it.start, it.endInclusive)) drawLine(cursorColor, Offset(x(t), 0f), Offset(x(t), size.height), 2.dp.toPx())
            }
            drawLine(foreground.copy(alpha = 0.3f), Offset(0f, waveHeight / 2), Offset(size.width, waveHeight / 2))
            for (pixel in 0 until size.width.toInt()) {
                val a = (view.timeAt(pixel / size.width.toDouble()) * audio.rate).toInt().coerceIn(0, audio.samples.size)
                val b = (view.timeAt((pixel + 1) / size.width.toDouble()) * audio.rate).toInt().coerceIn(a, audio.samples.size)
                var low = 0f; var high = 0f
                if (b - a >= 256) {
                    val first = (a + 127) / 128
                    val last = b / 128
                    for (i in a until first * 128) { low = min(low, audio.samples[i]); high = max(high, audio.samples[i]) }
                    for (block in first until last) {
                        low = min(low, state.envelope[block * 2]); high = max(high, state.envelope[block * 2 + 1])
                    }
                    for (i in last * 128 until b) { low = min(low, audio.samples[i]); high = max(high, audio.samples[i]) }
                } else for (i in a until max(a + 1, b).coerceAtMost(audio.samples.size)) {
                    low = min(low, audio.samples[i]); high = max(high, audio.samples[i])
                }
                drawLine(foreground, Offset(pixel.toFloat(), waveHeight * (0.5f - high * 0.48f)),
                    Offset(pixel.toFloat(), waveHeight * (0.5f - low * 0.48f)), 1f)
            }
            if (AnalysisLayer.PULSES in state.layers && view.span <= 0.5) {
                state.pulses.orEmpty().visible(view) { it }.forEach { t ->
                    drawLine(pitchColor.copy(alpha = 0.7f), Offset(x(t), 0f), Offset(x(t), waveHeight), 1f)
                }
            }
            clipRect(top = spectralTop) {
                for (hz in listOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0))
                    drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, frequencyY(hz)), Offset(size.width, frequencyY(hz)))
                if (AnalysisLayer.FORMANTS in state.layers) {
                    var lastX = -10f
                    state.formants.orEmpty().visible(view) { it.time }.forEach { frame ->
                        val px = x(frame.time)
                        if (px - lastX >= 2f) {
                            frame.hz.filterNotNull().forEach { drawCircle(formantColor, 1.5.dp.toPx(), Offset(px, frequencyY(it))) }
                            lastX = px
                        }
                    }
                }
                if (AnalysisLayer.PITCH in state.layers) contour(state.pitch.orEmpty(), view, pitchColor, ::x) {
                    size.height - ((it - state.settings.pitchFloor) / (state.settings.pitchCeiling - state.settings.pitchFloor) * spectralHeight).toFloat()
                }
                if (AnalysisLayer.INTENSITY in state.layers) contour(state.intensity.orEmpty(), view, intensityColor, ::x) {
                    size.height - ((it.coerceIn(-80.0, 0.0) + 80) / 80 * spectralHeight).toFloat()
                }
            }
            drawLine(cursorColor, Offset(x(state.cursor), 0f), Offset(x(state.cursor), size.height), 1.5.dp.toPx())
            if (studio.playing) drawLine(formantColor, Offset(x(studio.positionMs / 1000.0), 0f), Offset(x(studio.positionMs / 1000.0), size.height), 2.dp.toPx())
        }
    }
}

private fun <T> List<T>.visible(view: TimeViewport, time: (T) -> Double): List<T> {
    val from = binarySearchBy(view.start, selector = time).let { if (it < 0) -it - 1 else it }
    val to = binarySearchBy(view.end, selector = time).let { if (it < 0) -it - 1 else it + 1 }
    return subList(from.coerceIn(0, size), to.coerceIn(from.coerceIn(0, size), size))
}

private fun DrawScope.contour(track: List<AcousticPoint>, view: TimeViewport, color: Color,
    x: (Double) -> Float, y: (Double) -> Float) {
    var previous: Offset? = null
    var previousTime = 0.0
    track.visible(view) { it.time }.forEach { frame ->
        val value = frame.value
        if (value == null) previous = null else {
            val point = Offset(x(frame.time), y(value))
            val last = previous
            if (last == null || frame.time - previousTime >= 0.015) {
                drawCircle(color, 1.dp.toPx(), point)
                previous = point
            } else if (point.x - last.x >= 1f) {
                drawLine(color, last, point, 1.5.dp.toPx())
                previous = point
            }
        }
        previousTime = frame.time
    }
}

private fun number(value: Double?, decimals: Int): String = if (value == null || !value.isFinite()) "—"
    else String.format(Locale.ROOT, "%.${decimals}f", value)
