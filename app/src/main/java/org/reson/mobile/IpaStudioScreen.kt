package org.reson.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val ipaFont = FontFamily(Font(R.font.noto_sans))
private val ipaCategories = listOf("consonant_pulmonic", "vowel", "consonant_non_pulmonic", "consonant_other", "diacritic", "suprasegmental", "tone")
private fun categoryName(key: String): String = when (key) {
    "consonant_pulmonic" -> "Pulmonic consonants"
    "vowel" -> "Vowels"
    "consonant_non_pulmonic" -> "Non-pulmonic consonants"
    "consonant_other" -> "Other consonants"
    "diacritic" -> "Diacritics"
    "suprasegmental" -> "Suprasegmentals"
    "tone" -> "Tones & accents"
    "plosive" -> "Stops"
    "tap_flap" -> "Taps / flaps"
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun IpaStudioScreen(model: IpaStudioViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf("Type") }
    var family by rememberSaveable { mutableStateOf("Consonants") }
    var section by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("consonant_pulmonic") }
    var query by rememberSaveable { mutableStateOf("") }
    var inspected by rememberSaveable { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var draftActions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val catalogue = state.catalogue
    val results = remember(catalogue, query) { IpaSearch.find(catalogue?.symbols.orEmpty(), query) }
    val sections = remember(catalogue, family) { IpaKeyboard.sections(catalogue?.symbols.orEmpty(), family) }
    val activeSection = section.takeIf { it in sections } ?: sections.keys.firstOrNull().orEmpty()
    val grid = rememberLazyGridState()
    val keySize = 52.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(mode, family, activeSection, category, query) { grid.scrollToItem(0) }
    fun inspect(symbol: IpaSymbol) { keyboard?.hide(); inspected = symbol.id }
    fun insert(symbol: IpaSymbol) { model.insert(symbol); copied = false }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding().fillMaxSize().padding(horizontal = 12.dp)) {
            val compact = maxHeight < 360.dp
            // Very short IME/font-scale viewports can scroll the whole workspace.
            val shortViewport = maxHeight < 280.dp
            Column(if (shortViewport) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                    Text("IPA Studio", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (imeVisible) IconButton(onClick = { keyboard?.hide() }, modifier = Modifier.semantics { contentDescription = "Hide keyboard" }) {
                        Text("⌄", fontSize = 24.sp)
                    }
                    if (catalogue != null) Box {
                        IconButton(onClick = { draftActions = true }, modifier = Modifier.semantics { contentDescription = "Draft actions" }) {
                            Text("⋮", fontSize = 24.sp)
                        }
                        DropdownMenu(expanded = draftActions, onDismissRequest = { draftActions = false }) {
                            DropdownMenuItem(text = { Text(if (copied) "Copied" else "Copy draft") }, enabled = state.editor.text.isNotEmpty(), onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("IPA transcription", state.editor.text))
                                copied = true; draftActions = false
                            })
                            DropdownMenuItem(text = { Text("Paste") }, onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                val text = clip?.let { if (it.itemCount > 0) it.getItemAt(0).text?.toString() else null }
                                if (text != null) { model.insertText(text); copied = false }
                                else Toast.makeText(context, "Clipboard has no text", Toast.LENGTH_SHORT).show()
                                draftActions = false
                            })
                            DropdownMenuItem(text = { Text("Clear draft…") }, enabled = state.editor.text.isNotEmpty(), onClick = {
                                clearing = true; draftActions = false
                            })
                        }
                    }
                }
                if (catalogue != null) {
                    // The same ViewModel TextFieldValue owns the caret/selection in every mode.
                    OutlinedTextField(value = state.editor, onValueChange = { model.edit(it); copied = false },
                        label = { Text("Transcription") }, placeholder = { Text("Tap IPA keys to begin") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = ipaFont, fontSize = 24.sp),
                        minLines = 1, maxLines = if (compact) 1 else if (imeVisible) 2 else 4,
                        modifier = Modifier.fillMaxWidth())
                    if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.saving) "Saving…" else if (state.error != null) "Not saved" else if (copied) "Copied" else "Saved on device",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf("Type", "Chart", "Search").forEachIndexed { index, option ->
                            SegmentedButton(selected = mode == option, onClick = { mode = option },
                                shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) { Text(option) }
                        }
                    }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(keySize), state = grid,
                    modifier = if (shortViewport) Modifier.height(240.dp) else Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (catalogue == null && !state.loading) TextButton(onClick = model::load) { Text("Retry") }
                        }
                    } }
                    if (catalogue != null) when (mode) {
                        "Type" -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChoiceMenu(family, IpaKeyboard.families, { it }, { family = it; section = "" }, Modifier.weight(1f), "Symbol family")
                                    ChoiceMenu(activeSection, sections.keys.toList(), ::categoryName, { section = it }, Modifier.weight(1f), "Symbol group")
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("Tap to insert · hold for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(sections[activeSection].orEmpty(), key = { it.id }) { symbol ->
                                SymbolKey(symbol, ::insert, Modifier.fillMaxWidth().heightIn(min = keySize), onInspect = ::inspect)
                            }
                            if (state.recent.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text("Recent", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                                }
                                items(state.recent.mapNotNull { catalogue.byId[it] }.take(6), key = { "recent-" + it.id }) { symbol ->
                                    SymbolKey(symbol, ::insert, Modifier.fillMaxWidth().heightIn(min = keySize), onInspect = ::inspect)
                                }
                            }
                        }
                        "Search" -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                                    label = { Text("Search IPA") }, placeholder = { Text("schwa, voiced bilabial, U+0259") },
                                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Text("×") } },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            if (results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("No symbols found. Try a name, feature or Unicode codepoint.", modifier = Modifier.padding(vertical = 16.dp))
                            }
                            items(results, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { symbol ->
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Symbol details") { inspect(symbol) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(glyph(symbol), fontFamily = ipaFont, fontSize = 26.sp, modifier = Modifier.widthIn(min = keySize))
                                    Column(Modifier.weight(1f)) {
                                        Text(symbol.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(listOfNotNull(symbol.place, symbol.manner, symbol.voicing, symbol.height, symbol.backness, symbol.rounding)
                                            .joinToString(" · ").replace('_', ' ').ifEmpty { categoryName(symbol.category) },
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { insert(symbol) }, modifier = Modifier.semantics { contentDescription = "Insert ${symbol.name}" },
                                        contentPadding = PaddingValues(4.dp)) { Text("Insert") }
                                }
                            }
                        }
                        else -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ChoiceMenu(category, ipaCategories, ::categoryName, { category = it }, Modifier.fillMaxWidth(), "Chart category")
                            }
                            if (category == "consonant_pulmonic" || category == "vowel") {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    FocusedChart(if (category == "vowel") catalogue.vowels else catalogue.pulmonic, catalogue, category, ::inspect)
                                }
                            } else {
                                catalogue.symbols.filter { it.category == category }.groupBy { it.subcategory }.forEach { (group, symbols) ->
                                    item(span = { GridItemSpan(maxLineSpan) }) { Text(categoryName(group), style = MaterialTheme.typography.labelMedium) }
                                    items(symbols, key = { it.id }) { symbol -> SymbolKey(symbol, ::inspect, modifier = Modifier.fillMaxWidth().heightIn(min = keySize)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    catalogue?.byId?.get(inspected)?.let { symbol ->
        ModalBottomSheet(onDismissRequest = { inspected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(glyph(symbol), fontFamily = ipaFont, fontSize = 44.sp) }
                Text(symbol.name, style = MaterialTheme.typography.titleLarge)
                Text(categoryName(symbol.category) + " · " + symbol.subcategory.replace('_', ' '))
                listOf("Place" to symbol.place, "Manner" to symbol.manner, "Voicing" to symbol.voicing,
                    "Height" to symbol.height, "Backness" to symbol.backness, "Rounding" to symbol.rounding).forEach { (label, value) ->
                    value?.takeIf { it.isNotBlank() }?.let { Text("$label: ${it.replace('_', ' ')}") }
                }
                SelectionContainer { Text(symbol.codepoints.joinToString(" ")) }
                if (symbol.description.isNotEmpty()) Text(symbol.description)
                symbol.example?.takeIf { it.isNotBlank() }?.let { Text("Catalogue example: $it") }
                if (symbol.combining) Text("Combining mark: place the cursor after a base character. The dotted circle is a display aid and is not inserted.")
                if (symbol.id.startsWith("tie_bar")) Text("For an affricate, place the cursor between its consonants, then insert this tie bar.")
                Button(onClick = { model.insert(symbol); copied = false; inspected = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Insert ${glyph(symbol)}", fontFamily = ipaFont)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear transcription?") },
        text = { Text("This replaces the saved draft with empty text. Copy anything you want to keep first.") },
        confirmButton = { TextButton(onClick = { model.clear(); copied = false; clearing = false }) { Text("Clear draft") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Keep draft") } })
}

private fun glyph(symbol: IpaSymbol) = if (symbol.combining) "◌" + symbol.symbol else symbol.symbol

@Composable
private fun ChoiceMenu(value: String, options: List<String>, label: (String) -> String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, description: String) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$description: ${label(value)}" },
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(label(value), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(" ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); expanded = false },
                    modifier = Modifier.semantics { selected = value == option })
            }
        }
    }
}

@Composable
private fun SymbolKey(symbol: IpaSymbol, onClick: (IpaSymbol) -> Unit, modifier: Modifier = Modifier,
    onInspect: ((IpaSymbol) -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp).combinedClickable(
            role = Role.Button, onClickLabel = if (onInspect == null) "Inspect symbol" else "Insert symbol",
            onLongClickLabel = if (onInspect != null) "Symbol details" else null,
            onLongClick = onInspect?.let { { it(symbol) } }, onClick = { onClick(symbol) }
        ).semantics { contentDescription = symbol.name }) {
        Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            Text(glyph(symbol), fontFamily = ipaFont, fontSize = 24.sp)
        }
    }
}

@Composable
private fun FocusedChart(chart: IpaChart, catalogue: IpaCatalogue, category: String, onInspect: (IpaSymbol) -> Unit) {
    var selectedColumn by rememberSaveable(category) { mutableStateOf(chart.columns.first().key) }
    val column = chart.columns.firstOrNull { it.key == selectedColumn } ?: chart.columns.first()
    val vowel = category == "vowel"
    // Keep scientific left/right pair order even when the draft contains RTL text.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceMenu(column.key, chart.columns.map { it.key }, { key -> chart.columns.first { it.key == key }.label },
                { selectedColumn = it }, Modifier.fillMaxWidth(), if (vowel) "Backness" else "Place of articulation")
            Text(if (vowel) "Height · unrounded / rounded" else "Manner · voiceless / voiced",
                style = MaterialTheme.typography.labelMedium)
            chart.rows.forEach { row ->
                val cell = chart.cells.find { it.row == row.key && it.column == column.key }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Surface(color = if (cell?.impossible == true) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1.3f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (cell?.symbols ?: listOf(null, null)).forEach { id ->
                                val symbol = catalogue.byId[id]
                                if (symbol != null) SymbolKey(symbol, onInspect, modifier = Modifier.weight(1f))
                                else Box(Modifier.weight(1f).heightIn(min = 52.dp).semantics {
                                    contentDescription = if (cell?.impossible == true) "Articulatorily impossible" else "No catalogue symbol"
                                })
                            }
                        }
                    }
                }
            }
            if (!vowel) Text("Shaded pairs: articulatorily impossible", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tap a symbol for details. Use Type for direct input.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
