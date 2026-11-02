package org.reson.mobile

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TextGridState(val document: TextGridDocument? = null, val filename: String = "",
    val audioPath: String? = null, val hidden: Set<Int> = emptySet(),
    val selection: Pair<Int, Int>? = null, val view: TimeViewport = TimeViewport(0.0, 1.0),
    val cursor: Double = 0.0, val loading: Boolean = false, val error: String? = null)

class TextGridViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(TextGridState())
    val state = mutable.asStateFlow()
    private var importing: Job? = null

    fun open(uri: Uri, audioPath: String?) {
        importing?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        importing = viewModelScope.launch {
            try {
                val context = currentCoroutineContext()
                val (name, document) = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "TextGrid"
                    val bytes = ByteArrayOutputStream().use { output ->
                        checkNotNull(resolver.openInputStream(uri)) { "Document provider did not supply a readable stream" }.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > TextGridParser.MAX_BYTES) throw TextGridException("TextGrid exceeds 16 MiB import limit")
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                    name to TextGridParser.parse(bytes) { context.ensureActive() }
                }
                mutable.value = TextGridState(document, name, audioPath, view = TimeViewport(document.start.seconds, document.end.seconds), cursor = document.start.seconds)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loading = false, error = "Cannot open TextGrid: ${e.message}. Previous document retained.") } }
        }
    }

    fun report(message: String) { mutable.update { it.copy(error = message) } }
    fun associate(path: String?) { mutable.update { it.copy(audioPath = path, selection = null) } }
    fun visible(tier: Int, visible: Boolean) { mutable.update { it.copy(hidden = if (visible) it.hidden - tier else it.hidden + tier) } }
    fun select(tier: Int, annotation: Int) {
        val item = mutable.value.document?.tiers?.getOrNull(tier)?.annotations?.getOrNull(annotation) ?: return
        mutable.update { it.copy(selection = tier to annotation, cursor = item.start.seconds) }
    }
    fun viewport(view: TimeViewport) { mutable.update { it.copy(view = view) } }
}
