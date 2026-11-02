package org.reson.mobile

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class IpaAxis(val key: String, val label: String)
data class IpaCell(val row: String, val column: String, val symbols: List<String?>, val impossible: Boolean)
data class IpaChart(val rows: List<IpaAxis>, val columns: List<IpaAxis>, val cells: List<IpaCell>)
data class IpaCatalogue(val symbols: List<IpaSymbol>, val pulmonic: IpaChart, val vowels: IpaChart) {
    val byId = symbols.associateBy { it.id }

    companion object {
        fun parse(source: String): IpaCatalogue {
            val root = JSONObject(source)
            require(root.getInt("schema") == 1)
            fun JSONObject.optional(key: String) = if (isNull(key)) null else getString(key)
            fun JSONArray.strings() = (0 until length()).map { getString(it) }
            fun chart(name: String): IpaChart {
                val data = root.getJSONObject(name)
                fun axes(name: String): List<IpaAxis> = data.getJSONArray(name).let { items ->
                    (0 until items.length()).map { items.getJSONArray(it).let { IpaAxis(it.getString(0), it.getString(1)) } }
                }
                val cells = data.getJSONArray("cells")
                return IpaChart(axes("rows"), axes("columns"), (0 until cells.length()).map { index ->
                    val cell = cells.getJSONObject(index)
                    val ids = cell.getJSONArray("symbols")
                    require(ids.length() == 2)
                    IpaCell(cell.getString("row"), cell.getString("column"),
                        (0..1).map { if (ids.isNull(it)) null else ids.getString(it) }, cell.getBoolean("impossible"))
                })
            }
            val items = root.getJSONArray("symbols")
            val symbols = (0 until items.length()).map { index ->
                val s = items.getJSONObject(index)
                IpaSymbol(s.getString("id"), s.getString("symbol"), s.getString("name"), s.getString("category"),
                    s.getString("subcategory"), s.optional("place"), s.optional("manner"), s.optional("voicing"),
                    s.optional("height"), s.optional("backness"), s.optional("rounding"), s.getString("description"),
                    s.getJSONArray("aliases").strings(), s.getJSONArray("codepoints").strings(),
                    s.getBoolean("is_combining"), s.getBoolean("is_modifier"), s.optional("example"))
            }
            require(symbols.isNotEmpty() && symbols.all { it.id.isNotEmpty() && it.symbol.isNotEmpty() })
            require(symbols.map { it.id }.toSet().size == symbols.size)
            val catalogue = IpaCatalogue(symbols, chart("pulmonic"), chart("vowels"))
            for (grid in listOf(catalogue.pulmonic, catalogue.vowels)) {
                require(grid.cells.all { cell -> cell.symbols.filterNotNull().all { it in catalogue.byId } })
            }
            return catalogue
        }
    }
}

data class IpaStudioState(val catalogue: IpaCatalogue? = null, val editor: TextFieldValue = TextFieldValue(),
    val recent: List<String> = emptyList(), val loading: Boolean = true, val saving: Boolean = false,
    val error: String? = null)

class IpaStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(IpaStudioState())
    val state = mutable.asStateFlow()
    private val draft = IpaDraftStore(File(application.filesDir, "ipa-draft.txt"))
    private val preferences = application.getSharedPreferences("ipa", 0)
    private val writer = Executors.newSingleThreadExecutor()
    private var revision = 0L

    init { load() }

    fun load() {
        if (mutable.value.catalogue != null) return
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (catalogue, text) = withContext(Dispatchers.IO) {
                    val source = getApplication<Application>().assets.open("ipa_catalogue.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    IpaCatalogue.parse(source) to draft.read()
                }
                val recent = preferences.getString("recent", "").orEmpty().split('\n').filter { it in catalogue.byId }.distinct().take(12)
                mutable.value = IpaStudioState(catalogue, TextFieldValue(text, TextRange(text.length)), recent, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loading = false, error = "Cannot open IPA Studio: ${e.message}. Existing draft retained.") } }
        }
    }

    fun edit(value: TextFieldValue) {
        if (mutable.value.catalogue == null) return
        val changed = value.text != mutable.value.editor.text
        mutable.update { it.copy(editor = value) }
        if (!changed) return
        val version = ++revision
        mutable.update { it.copy(saving = true, error = null) }
        // Ordered disk writes survive screen navigation; shutdown drains pending saves.
        writer.execute {
            val failure = runCatching { draft.write(value.text) }.exceptionOrNull()
            viewModelScope.launch {
                if (revision == version) mutable.update {
                    it.copy(saving = false, error = failure?.let { "Draft not saved: ${it.message}. Copy your text before closing." })
                }
            }
        }
    }

    fun insert(symbol: IpaSymbol) {
        insertText(symbol.symbol)
        val recent = (listOf(symbol.id) + mutable.value.recent).distinct().take(12)
        mutable.update { it.copy(recent = recent) }
        preferences.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    fun insertText(text: String) {
        val editor = mutable.value.editor
        val result = IpaEditing.insert(editor.text, editor.selection.start, editor.selection.end, text)
        edit(TextFieldValue(result.text, TextRange(result.cursor)))
    }

    fun clear() { edit(TextFieldValue()) }

    override fun onCleared() {
        writer.shutdown()
        super.onCleared()
    }
}
