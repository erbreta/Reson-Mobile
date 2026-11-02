package org.reson.mobile

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val studio: StudioViewModel by viewModels()
    private val analyzer: AnalyzerViewModel by viewModels()
    private val ipa: IpaStudioViewModel by viewModels()
    private val textGrid: TextGridViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var theme by remember { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
            val state by studio.state.collectAsState()
            var analysisPath by rememberSaveable { mutableStateOf<String?>(null) }
            var ipaOpen by rememberSaveable { mutableStateOf(false) }
            var textGridOpen by rememberSaveable { mutableStateOf(false) }
            var pendingGridAudioPath by rememberSaveable { mutableStateOf<String?>(null) }
            var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
            var permissionDenied by rememberSaveable { mutableStateOf(false) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                permissionDenied = !granted
                if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) studio.startRecording()
                else if (!granted) studio.report("Microphone access is required to record. Existing WAVs remain available.")
            }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
                val path = exportPath
                exportPath = null
                if (uri != null && path != null) studio.save(File(path), uri)
            }
            val openGrid = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) textGrid.open(uri, pendingGridAudioPath)
                pendingGridAudioPath = null
            }
            val openWav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) studio.importWav(uri)
            }
            fun requestGrid(audioPath: String?) {
                pendingGridAudioPath = audioPath
                try { openGrid.launch(arrayOf("*/*")) }
                catch (e: Exception) { pendingGridAudioPath = null; textGrid.report("Cannot open document picker: ${e.message}") }
            }
            fun associateGrid(recording: Recording) {
                studio.select(recording)
                textGrid.associate(recording.file.absolutePath)
                analysisPath = recording.file.absolutePath
                textGridOpen = false
            }
            ResonTheme(theme) {
                val analysing = state.recordings.find { it.file.absolutePath == analysisPath }
                if (ipaOpen) {
                    IpaStudioScreen(ipa, onBack = { ipaOpen = false })
                } else if (textGridOpen) {
                    TextGridScreen(textGrid, state.recordings, onBack = { textGridOpen = false },
                        onOpen = { requestGrid(null) }, onAudio = ::associateGrid)
                } else if (analysing != null) {
                    LaunchedEffect(analysing) {
                        if (state.selected?.file != analysing.file) studio.select(analysing)
                        analyzer.open(analysing)
                    }
                    AnalyzerScreen(analyzer, state, theme, onThemeChange = {
                        theme = it
                        preferences.edit().putString("theme", it).apply()
                    }, onBack = {
                        studio.stopPlayback(); analyzer.close(); analysisPath = null
                    }, onPlay = { start, end -> studio.play(start, end) }, onStop = studio::stopPlayback,
                        onIpa = { studio.stopPlayback(); ipaOpen = true }, grid = textGrid,
                        onOpenGrid = { requestGrid(analysing.file.absolutePath) }, onGridAudio = ::associateGrid,
                        onGridStandalone = { textGridOpen = true })
                } else {
                StudioScreen(state, permissionDenied, theme, onThemeChange = {
                    theme = it
                    preferences.edit().putString("theme", it).apply()
                },
                    onRecord = {
                        if (state.capturing) studio.stopRecording()
                        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            permissionDenied = false
                            studio.startRecording()
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                    onSelect = studio::select, onRename = studio::rename, onDelete = { recording ->
                        if (studio.delete(recording)) {
                            if (analyzer.state.value.recording?.file == recording.file) analyzer.close()
                            if (analysisPath == recording.file.absolutePath) analysisPath = null
                            if (textGrid.state.value.audioPath == recording.file.absolutePath) textGrid.associate(null)
                        }
                    }, onPlay = { studio.play() },
                    onAnalyse = { studio.stopPlayback(); analysisPath = state.selected?.file?.absolutePath },
                    onIpa = { studio.stopPlayback(); ipaOpen = true },
                    onTextGrid = { studio.stopPlayback(); textGridOpen = true },
                    onImport = {
                        try { openWav.launch(arrayOf("*/*")) }
                        catch (e: Exception) { studio.report("Cannot open WAV picker: ${e.message}") }
                    },
                    onPause = studio::pausePlayback, onStop = studio::stopPlayback,
                    onDismiss = { studio.report(null) },
                    onSave = { state.selected?.let {
                        exportPath = it.file.absolutePath
                        try { save.launch(it.displayName) }
                        catch (e: Exception) { exportPath = null; studio.report("No document picker available: ${e.message}") }
                    } },
                    onShare = { state.selected?.let {
                        try {
                            val uri = FileProvider.getUriForFile(this, "$packageName.files", it.file, it.displayName)
                            val intent = Intent(Intent.ACTION_SEND).setType("audio/wav")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .apply { clipData = ClipData.newRawUri("WAV recording", uri) }
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(intent, "Share WAV"))
                        } catch (e: Exception) { studio.report("Cannot share WAV: ${e.message}") }
                    } })
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) studio.background()
        super.onStop()
    }
}

@Composable
private fun ResonTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController((view.context as ComponentActivity).window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF76BDB7), onPrimary = Color(0xFF1D2A33),
        primaryContainer = Color(0xFF315854), onPrimaryContainer = Color(0xFFF4EBDD),
        secondary = Color(0xFFCAD4D0), onSecondary = Color(0xFF1D2A33),
        secondaryContainer = Color(0xFF304550), onSecondaryContainer = Color(0xFFF4EBDD),
        background = Color(0xFF1D2A33), onBackground = Color(0xFFF4EBDD),
        surface = Color(0xFF273843), onSurface = Color(0xFFF4EBDD),
        surfaceVariant = Color(0xFF304550), onSurfaceVariant = Color(0xFFCAD4D0),
        surfaceContainerLowest = Color(0xFF273843), surfaceContainerLow = Color(0xFF273843),
        surfaceContainer = Color(0xFF273843), surfaceContainerHigh = Color(0xFF304550),
        surfaceContainerHighest = Color(0xFF304550), outline = Color(0xFF52666F),
        outlineVariant = Color(0xFF52666F), surfaceTint = Color(0xFF76BDB7),
        error = Color(0xFFFF9CA4), onError = Color(0xFF1D2A33),
    ) else lightColorScheme(
        primary = Color(0xFF367572), onPrimary = Color.White,
        primaryContainer = Color(0xFFDDECE7), onPrimaryContainer = Color(0xFF273843),
        secondary = Color(0xFF4D6269), onSecondary = Color.White,
        secondaryContainer = Color(0xFFECE5DA), onSecondaryContainer = Color(0xFF273843),
        background = Color(0xFFF4EBDD), onBackground = Color(0xFF273843),
        surface = Color(0xFFFFFAF3), onSurface = Color(0xFF273843),
        surfaceVariant = Color(0xFFECE5DA), onSurfaceVariant = Color(0xFF4D6269),
        surfaceContainerLowest = Color(0xFFFFFAF3), surfaceContainerLow = Color(0xFFFFFAF3),
        surfaceContainer = Color(0xFFFFFAF3), surfaceContainerHigh = Color(0xFFECE5DA),
        surfaceContainerHighest = Color(0xFFECE5DA), outline = Color(0xFFD8DDD9),
        outlineVariant = Color(0xFFD8DDD9), surfaceTint = Color(0xFF367572),
        error = Color(0xFFB63F48), onError = Color.White,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StudioScreen(
    state: StudioState, permissionDenied: Boolean, theme: String, onThemeChange: (String) -> Unit,
    onRecord: () -> Unit, onSettings: () -> Unit, onSelect: (Recording) -> Unit,
    onRename: (Recording, String) -> Boolean, onDelete: (Recording) -> Unit,
    onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAnalyse: () -> Unit, onIpa: () -> Unit, onTextGrid: () -> Unit,
    onImport: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var name by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }
    renaming?.let { recording ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename recording") },
            text = { Column {
                OutlinedTextField(value = name, onValueChange = { name = it; renameError = false }, label = { Text("Recording name") }, singleLine = true, isError = renameError)
                if (renameError) Text(state.message ?: "Cannot rename recording.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = { if (onRename(recording, name)) renaming = null else renameError = true }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { recording ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete recording?") },
            text = { Text("Are you sure you want to permanently delete \"${recording.displayName}\"? This action cannot be undone.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(recording) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reson", style = MaterialTheme.typography.headlineMedium)
                            Text("WAV RECORDING STUDIO · OFFLINE", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) { Text("Theme: $theme") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("System", "Light", "Dark").forEach { option ->
                                    DropdownMenuItem(text = { Text(option) }, onClick = {
                                        onThemeChange(option)
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onImport, enabled = !state.loading && !state.capturing && !state.importing,
                        modifier = Modifier.fillMaxWidth()) { Text(if (state.importing) "Importing WAV…" else "Import WAV for analysis") }
                    OutlinedButton(onClick = onIpa, enabled = !state.capturing, modifier = Modifier.fillMaxWidth()) { Text("IPA Studio") }
                    OutlinedButton(onClick = onTextGrid, enabled = !state.capturing, modifier = Modifier.fillMaxWidth()) { Text("TextGrid Viewer") }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(when (state.phase) {
                                CapturePhase.IDLE -> "Ready to record"
                                CapturePhase.STARTING -> "Opening microphone…"
                                CapturePhase.RECORDING -> "Recording microphone"
                                CapturePhase.STOPPING -> "Finalising WAV…"
                            }, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text(time(state.seconds), style = MaterialTheme.typography.displayMedium,
                                fontFamily = FontFamily.Monospace)
                            LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth()
                                .height(8.dp).semantics { contentDescription = "Microphone peak level" })
                            Text("Peak level · uncalibrated", style = MaterialTheme.typography.labelSmall)
                            Button(onClick = onRecord, enabled = !state.loading && !state.importing && state.phase != CapturePhase.STOPPING,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                colors = if (state.capturing) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
                                Text(if (state.loading) "Loading recordings…" else if (state.capturing) "Stop recording" else "Record WAV")
                            }
                            Text(state.captureInfo, style = MaterialTheme.typography.bodySmall)
                            Text("Recording stops when you leave the app.", style = MaterialTheme.typography.bodySmall)
                            if (permissionDenied) TextButton(onClick = onSettings) { Text("Microphone permission settings") }
                        }
                    }
                }
                if (state.message != null) item {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        SelectionContainer { Text(state.message, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                }
                item {
                    Text("Playback & transfer", style = MaterialTheme.typography.titleLarge)
                    val selected = state.selected
                    if (selected == null) Text("Record a WAV to begin. Audio stays on this device.")
                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.displayName, style = MaterialTheme.typography.bodyMedium)
                        val total = if (state.durationMs > 0) state.durationMs / 1000.0 else selected.durationSeconds
                        LinearProgressIndicator(progress = {
                            if (total > 0) (state.positionMs / (total * 1000)).toFloat().coerceIn(0f, 1f) else 0f
                        }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback progress" })
                        Text("${time(state.positionMs / 1000.0)} / ${time(total)}", fontFamily = FontFamily.Monospace)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = if (state.playing) onPause else onPlay,
                                enabled = !state.capturing && !state.preparingPlayback) {
                                Text(if (state.preparingPlayback) "Loading…" else if (state.playing) "Pause" else "Play")
                            }
                            OutlinedButton(onClick = onStop, enabled = !state.capturing && (state.playing || state.positionMs > 0 || state.preparingPlayback)) { Text("Stop") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAnalyse, enabled = !state.capturing) { Text("Analyse") }
                            OutlinedButton(onClick = onSave, enabled = !state.capturing && !state.exporting) {
                                Text(if (state.exporting) "Saving…" else "Save WAV…")
                            }
                            OutlinedButton(onClick = onShare, enabled = !state.capturing) { Text("Share WAV") }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text("Recordings (${state.recordings.size})", style = MaterialTheme.typography.titleLarge)
                    Text("Select a recording to listen or transfer.", style = MaterialTheme.typography.bodySmall)
                }
                items(state.recordings, key = { it.file.name }) { recording ->
                    val selected = state.selected?.file == recording.file
                    Card(onClick = { onSelect(recording) }, enabled = !state.capturing,
                        colors = CardDefaults.cardColors(containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(recording.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(recording.file.lastModified())), style = MaterialTheme.typography.bodySmall)
                            Text("${time(recording.durationSeconds)} · ${recording.sampleRate} Hz · ${fileSize(recording.file.length())}",
                                style = MaterialTheme.typography.bodySmall)
                          }
                          IconButton(onClick = { name = recording.displayName.removeSuffix(".wav"); renameError = false; renaming = recording }, enabled = !state.capturing && !state.importing && !state.exporting) {
                              Text("✎", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Rename ${recording.displayName}" })
                          }
                          IconButton(onClick = { deleting = recording }, enabled = !state.capturing && !state.importing && !state.exporting) {
                              Text("🗑", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Delete ${recording.displayName}" })
                          }
                        }
                    }
                }
            }
        }
    }
}

private fun time(seconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).toLong()
    return String.format(Locale.ROOT, "%02d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10)
}

private fun fileSize(bytes: Long): String = if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
