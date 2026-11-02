package org.reson.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextGridScreen(model: TextGridViewModel, recordings: List<Recording>, onBack: () -> Unit,
    onOpen: () -> Unit, onAudio: (Recording) -> Unit) {
    val state by model.state.collectAsState()
    val document = state.document
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("TextGrid Viewer", style = MaterialTheme.typography.titleLarge)
            Text("Read-only · no WAV required", style = MaterialTheme.typography.labelMedium)
            if (document != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.viewport(TimeViewport(document.start.seconds, document.end.seconds)) }) { Text("Fit") }
                    TextButton(onClick = { model.viewport(document.navigate(state.view, 2.0, 0.0, 0.5)) }) { Text("Zoom +") }
                    TextButton(onClick = { model.viewport(document.navigate(state.view, 0.5, 0.0, 0.5)) }) { Text("Zoom −") }
                    TextButton(onClick = { model.viewport(document.navigate(state.view, 1.0, 0.5, 0.5)) }) { Text("Earlier") }
                    TextButton(onClick = { model.viewport(document.navigate(state.view, 1.0, -0.5, 0.5)) }) { Text("Later") }
                }
                Text("Visible ${state.view.start}–${state.view.end} s. Tap annotations; drag to pan; pinch to zoom.", style = MaterialTheme.typography.bodySmall)
            }
            TextGridPanel(model, state.view, state.cursor, recordings, null, onOpen, onAudio,
                onStandalone = {}, onSelect = { _, _ -> },
                onNavigate = { zoom, pan, anchor -> document?.let { model.viewport(it.navigate(state.view, zoom, pan, anchor)) } },
                onZoom = { item -> document?.let { model.viewport(annotationView(item, TimeViewport(it.start.seconds, it.end.seconds))) } },
                onPlay = {})
        }
    }
}

internal fun annotationView(item: GridAnnotation, domain: TimeViewport): TimeViewport {
    val width = max(item.end.seconds - item.start.seconds, min(0.005, domain.span)).coerceAtMost(domain.span)
    val left = ((item.start.seconds + item.end.seconds) / 2 - width / 2).coerceIn(domain.start, domain.end - width)
    return TimeViewport(left, left + width)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TextGridPanel(model: TextGridViewModel, view: TimeViewport, cursor: Double,
    recordings: List<Recording>, audio: Recording?, onOpen: () -> Unit, onAudio: (Recording) -> Unit,
    onStandalone: () -> Unit, onSelect: (GridTier, GridAnnotation) -> Unit,
    onNavigate: (Double, Double, Double) -> Unit, onZoom: (GridAnnotation) -> Unit, onPlay: (GridAnnotation) -> Unit) {
    val state by model.state.collectAsState()
    val document = state.document
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tiers by rememberSaveable { mutableStateOf(false) }
    var chooseAudio by rememberSaveable { mutableStateOf(false) }
    var browse by rememberSaveable { mutableStateOf<Int?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    fun select(tier: Int, index: Int) {
        val t = document?.tiers?.getOrNull(tier) ?: return
        val item = t.annotations.getOrNull(index) ?: return
        model.select(tier, index); onSelect(t, item); details = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text("Open TextGrid") }
            TextButton(onClick = { chooseAudio = true }, enabled = document != null && !state.loading) { Text(if (audio == null) "Associate WAV" else "Change WAV") }
            if (document != null) {
                TextButton(onClick = { tiers = true }) { Text("Tiers (${document.tiers.size})") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse tiers" else "Show tiers") }
                if (audio != null) TextButton(onClick = { model.associate(null); onStandalone() }) { Text("TextGrid only") }
            }
        }
        if (state.loading) { Text("Reading TextGrid…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (document == null) Text("Open a long- or short-text .TextGrid from the system document picker.")
        else {
            SelectionContainer { Text(state.filename, style = MaterialTheme.typography.titleSmall) }
            Text(audio?.file?.name?.let { "WAV: $it" } ?: "Standalone TextGrid · original timestamps", style = MaterialTheme.typography.labelSmall)
            audio?.let { document.audioWarning(it.durationSeconds)?.let { warning -> Text(warning, color = MaterialTheme.colorScheme.error) } }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(document.tiers, key = { index, _ -> index }) { index, tier ->
                        if (index !in state.hidden) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("${index + 1}. ${tier.name}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = { browse = index }) { Text("Browse") }
                            }
                            TierTimeline(tier, view, cursor, state.selection?.takeIf { it.first == index }?.second,
                                onTap = { select(index, it) }, onNavigate = onNavigate, onBrowse = { browse = index })
                        }
                    }
                }
                if (document.tiers.isEmpty()) Text("This TextGrid has no tiers.")
                else if (state.hidden.size == document.tiers.size) Text("All tiers hidden. Open Tiers to show them.")
            }
        }
    }
    if (tiers && document != null) ModalBottomSheet(onDismissRequest = { tiers = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(16.dp)) {
            item { Text("Tiers · ${state.filename}", style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(document.tiers) { index, tier ->
                ListItem(headlineContent = { Text("${index + 1}. ${tier.name}") },
                    supportingContent = { Text("${tier.type} · ${tier.annotations.size} annotations") },
                    trailingContent = { Switch(checked = index !in state.hidden, onCheckedChange = { model.visible(index, it) },
                        modifier = Modifier.semantics { contentDescription = "Show tier ${index + 1}: ${tier.name}" }) })
            }
        }
    }
    if (chooseAudio) ModalBottomSheet(onDismissRequest = { chooseAudio = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(16.dp)) {
            item { Text("Associate a saved WAV", style = MaterialTheme.typography.titleLarge) }
            if (recordings.isEmpty()) item { Text("No saved recordings. Record or import a WAV in the studio; standalone viewing remains available.") }
            itemsIndexed(recordings) { _, recording ->
                ListItem(modifier = Modifier.clickable(enabled = !state.loading, role = Role.Button) { chooseAudio = false; onAudio(recording) },
                    headlineContent = { Text(recording.displayName) }, supportingContent = { Text("${recording.durationSeconds} s · ${recording.sampleRate} Hz") })
            }
        }
    }
    browse?.let { index -> document?.tiers?.getOrNull(index)?.let { tier ->
        ModalBottomSheet(onDismissRequest = { browse = null }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(16.dp)) {
                item { Text(tier.name, style = MaterialTheme.typography.titleLarge) }
                if (tier.annotations.isEmpty()) item { Text("No point annotations.") }
                itemsIndexed(tier.annotations) { itemIndex, annotation ->
                    ListItem(modifier = Modifier.clickable(role = Role.Button) { browse = null; select(index, itemIndex) },
                        headlineContent = { Text(annotation.label.ifEmpty { "(empty label)" }, fontFamily = ipaFont, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (tier.type == "TextTier") "${annotation.start.literal} s" else "${annotation.start.literal}–${annotation.end.literal} s") })
                }
            }
        }
    } }
    val selectedTier = state.selection?.first?.let { document?.tiers?.getOrNull(it) }
    val selected = state.selection?.second?.let { selectedTier?.annotations?.getOrNull(it) }
    if (details && selected != null && selectedTier != null) ModalBottomSheet(onDismissRequest = { details = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(selectedTier.name, style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(selected.label.ifEmpty { "(empty label)" }, fontFamily = ipaFont, fontSize = 22.sp) }
            SelectionContainer {
                Text(if (selectedTier.type == "TextTier") "Time: ${selected.start.literal} s"
                    else "Start: ${selected.start.literal} s\nEnd: ${selected.end.literal} s\nDuration: ${selected.duration} s")
            }
            val inAudio = audio == null || (selected.start.seconds >= 0 && selected.end.seconds <= audio.durationSeconds)
            if (!inAudio) Text("Annotation is outside the associated WAV. Use TextGrid only to inspect its full domain.", color = MaterialTheme.colorScheme.error)
            Button(enabled = inAudio, onClick = { onZoom(selected); details = false }) { Text("Zoom to ${if (selectedTier.type == "TextTier") "Point" else "Interval"}") }
            if (selectedTier.type == "IntervalTier") {
                val playable = audio?.let { selected.audioRange(it.durationSeconds) }?.let { it.endInclusive - it.start >= 0.001 } == true
                Button(enabled = playable, onClick = { onPlay(selected); details = false }) { Text("Play Interval") }
                if (audio == null) Text("Associate a saved WAV to play intervals.")
                else if (inAudio && !playable) Text("This interval is shorter than the player's 1 ms seek resolution. It remains selectable and zoomable.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TierTimeline(tier: GridTier, view: TimeViewport, cursor: Double, selected: Int?,
    onTap: (Int) -> Unit, onNavigate: (Double, Double, Double) -> Unit, onBrowse: () -> Unit) {
    val currentView by rememberUpdatedState(view)
    val navigate by rememberUpdatedState(onNavigate)
    val tap by rememberUpdatedState(onTap)
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.primary
    val measure = rememberTextMeasurer(cacheSize = 64)
    val style = TextStyle(color = foreground, fontFamily = ipaFont, fontSize = 14.sp, textDirection = TextDirection.Content)
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics {
        contentDescription = "${tier.name}, ${tier.type}, ${tier.annotations.size} annotations. Tap to inspect; drag to pan."
        customActions = listOf(CustomAccessibilityAction("Browse all annotations") { onBrowse(); true })
    }.pointerInput(tier) {
        detectTapGestures { position ->
            val visible = currentView
            tier.hit(visible.timeAt(position.x / size.width.toDouble()), 24.dp.toPx() / size.width * visible.span)?.let(tap)
        }
    }.pointerInput(tier) {
        detectTransformGestures { centre, pan, zoom, _ ->
            navigate(zoom.toDouble(), pan.x.toDouble() / size.width, (centre.x / size.width).toDouble().coerceIn(0.0, 1.0))
        }
    }) {
        drawRect(background)
        clipRect {
            var lastBoundary = -2f
            for (index in tier.visible(view)) {
                val item = tier.annotations[index]
                val x = (view.fraction(item.start.seconds) * size.width).toFloat()
                val end = (view.fraction(item.end.seconds) * size.width).toFloat()
                if (selected == index) drawRect(accent.copy(alpha = 0.18f), Offset(x, 0f), Size(max(1f, end - x), size.height))
                val boundaryVisible = x - lastBoundary >= 1f || selected == index
                if (boundaryVisible) {
                    drawLine(accent, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()); lastBoundary = x
                }
                if (tier.type == "TextTier" && boundaryVisible) drawCircle(accent, 3.dp.toPx(), Offset(x, size.height / 2))
                val left = max(0f, x) + 3.dp.toPx()
                val nextPoint = tier.annotations.getOrNull(index + 1)?.start?.seconds?.let { (view.fraction(it) * size.width).toFloat() } ?: size.width
                val right = if (tier.type == "TextTier") min(min(size.width, nextPoint), x + 120.dp.toPx()) else min(size.width, end)
                if (right - left >= 32.dp.toPx() && item.label.isNotEmpty()) {
                    val prefix = item.label.take(256).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                    val preview = prefix.replace('\n', ' ') + if (prefix.length < item.label.length) "…" else ""
                    val layout = measure.measure(preview, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (right - left).toInt().coerceAtLeast(1)))
                    drawText(layout, topLeft = Offset(left, (size.height - layout.size.height) / 2))
                }
                if (end in 0f..size.width && end - lastBoundary >= 1f) {
                    drawLine(accent, Offset(end, 0f), Offset(end, size.height), 1.dp.toPx()); lastBoundary = end
                }
            }
            val x = (view.fraction(cursor) * size.width).toFloat()
            drawLine(foreground, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
    }
}
