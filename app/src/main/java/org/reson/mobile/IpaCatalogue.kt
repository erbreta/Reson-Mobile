package org.reson.mobile

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class IpaSymbol(val id: String, val symbol: String, val name: String, val category: String,
    val subcategory: String = "", val place: String? = null, val manner: String? = null,
    val voicing: String? = null, val height: String? = null, val backness: String? = null,
    val rounding: String? = null, val description: String = "", val aliases: List<String> = emptyList(),
    val codepoints: List<String> = emptyList(), val combining: Boolean = false,
    val modifier: Boolean = false, val example: String? = null)

object IpaSearch {
    fun find(symbols: List<IpaSymbol>, query: String): List<IpaSymbol> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return symbols
        val tokens = q.split(Regex("\\s+"))
        return symbols.filter { s ->
            val fields = (listOfNotNull(s.name, s.category, s.subcategory, s.place, s.manner,
                s.voicing, s.height, s.backness, s.rounding, s.description) + s.aliases + s.codepoints)
                .joinToString(" ").lowercase(Locale.ROOT).replace('_', ' ')
            val words = fields.split(Regex("[^\\p{L}\\p{N}+]+"))
            q == s.symbol.lowercase(Locale.ROOT) || tokens.all { token ->
                // Feature words need boundaries: "voiced" must not match "voiceless" or "unvoiced".
                if (token in setOf("voiced", "voiceless", "rounded", "unrounded")) token in words else token in fields
            }
        }.sortedBy { if (it.symbol.lowercase(Locale.ROOT) == q) 0 else 1 }
    }
}

/** Keyboard pages partition the catalogue; chart positions remain authoritative. */
object IpaKeyboard {
    val families = listOf("Consonants", "Vowels", "Modifiers", "More")

    fun sections(symbols: List<IpaSymbol>, family: String): Map<String, List<IpaSymbol>> = symbols.filter {
        when (it.category) {
            "consonant_pulmonic" -> family == "Consonants"
            "vowel" -> family == "Vowels"
            "diacritic", "suprasegmental", "tone" -> family == "Modifiers"
            else -> family == "More"
        }
    }.groupBy {
        when (it.category) {
            "consonant_pulmonic" -> it.manner ?: it.subcategory
            "vowel" -> it.backness ?: it.subcategory
            "diacritic" -> "Diacritics"
            "suprasegmental" -> "Stress & length"
            "tone" -> "Tones"
            "consonant_non_pulmonic" -> "Non-pulmonic"
            "consonant_other" -> "Other consonants"
            else -> it.category
        }
    }
}

data class IpaEdit(val text: String, val cursor: Int)

object IpaEditing {
    fun insert(text: String, first: Int, last: Int, symbol: String): IpaEdit {
        fun boundary(index: Int, forward: Boolean): Int {
            val i = index.coerceIn(0, text.length)
            return if (i > 0 && i < text.length && text[i - 1].isHighSurrogate() && text[i].isLowSurrogate())
                i + if (forward) 1 else -1 else i
        }
        val collapsed = first == last
        val start = boundary(minOf(first, last), collapsed)
        val end = boundary(maxOf(first, last), true)
        return IpaEdit(text.replaceRange(start, end, symbol), start + symbol.length)
    }
}

/** Atomic UTF-8 replacement: a failed save retains the previous draft. */
class IpaDraftStore(private val file: File) {
    fun read(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""
    fun write(text: String) {
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            temporary.outputStream().use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
}
