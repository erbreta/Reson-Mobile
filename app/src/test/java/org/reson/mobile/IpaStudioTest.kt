package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test

class IpaStudioTest {
    @Test fun keyboardFamiliesPartitionEveryCatalogueCategoryAndKeepFeatureSections() {
        val samples = listOf(
            IpaSymbol("p", "p", "Plosive", "consonant_pulmonic", manner = "plosive"),
            IpaSymbol("i", "i", "Vowel", "vowel", backness = "near_front"),
            IpaSymbol("d", "̃", "Diacritic", "diacritic", "nasalization"),
            IpaSymbol("s", "ˈ", "Stress", "suprasegmental", "stress"),
            IpaSymbol("t", "˥", "Tone", "tone", "level"),
            IpaSymbol("c", "ǀ", "Click", "consonant_non_pulmonic", "click"),
            IpaSymbol("o", "w", "Other", "consonant_other", "coarticulated")
        )
        val groups = IpaKeyboard.families.flatMap { IpaKeyboard.sections(samples, it).values.flatten() }
        assertEquals(samples.toSet(), groups.toSet())
        assertEquals(samples.size, groups.size)
        assertEquals(listOf(samples[0]), IpaKeyboard.sections(samples, "Consonants")["plosive"])
        assertEquals(listOf(samples[1]), IpaKeyboard.sections(samples, "Vowels")["near_front"])
        assertEquals(setOf("Diacritics", "Stress & length", "Tones"), IpaKeyboard.sections(samples, "Modifiers").keys)
        assertEquals(2, IpaKeyboard.sections(samples, "More").values.flatten().size)
    }

    private val schwa = IpaSymbol("schwa", "ə", "Mid central vowel", "vowel", aliases = listOf("schwa"), codepoints = listOf("U+0259"))
    private val voiced = IpaSymbol("b", "b", "Voiced bilabial plosive", "consonant_pulmonic", place = "bilabial", manner = "plosive", voicing = "voiced")
    private val voiceless = IpaSymbol("p", "p", "Voiceless bilabial plosive", "consonant_pulmonic", place = "bilabial", manner = "plosive", voicing = "voiceless")

    @Test fun searchFindsGlyphAliasCodepointAndCombinedFeatures() {
        val symbols = listOf(schwa, voiced, voiceless)
        for (query in listOf("ə", "schwa", "U+0259", "0259", "  SCHWA  "))
            assertEquals(listOf(schwa), IpaSearch.find(symbols, query))
        assertEquals(listOf(voiced), IpaSearch.find(symbols, "voiced bilabial"))
        assertEquals(listOf(voiceless), IpaSearch.find(symbols, "bilabial voiceless"))
        assertTrue(IpaSearch.find(symbols, "uvular").isEmpty())
    }

    @Test fun insertionRespectsCursorReversedSelectionAndCombiningSequences() {
        assertEquals(IpaEdit("at͡ʃə", 4), IpaEditing.insert("aə", 1, 1, "t͡ʃ"))
        assertEquals(IpaEdit("aŋə", 2), IpaEditing.insert("abcə", 3, 1, "ŋ"))
        assertEquals(IpaEdit("n\u0325\u0303", 3), IpaEditing.insert("n\u0325", 2, 2, "\u0303"))
        assertEquals(IpaEdit("ə", 1), IpaEditing.insert("", -9, 500, "ə"))
    }

    @Test fun insertionDoesNotSplitSupplementaryUnicodeCharacters() {
        assertEquals(IpaEdit("😀əx", 3), IpaEditing.insert("😀x", 1, 1, "ə"))
        assertEquals(IpaEdit("əx", 1), IpaEditing.insert("😀x", 1, 2, "ə"))
    }

    @Test fun insertionAndPersistencePreserveLongMixedDirectionText() {
        val text = "العربية ə̃ 😀\n".repeat(1000)
        val inserted = IpaEditing.insert(text, 8, 10, "t͡ʃ")
        assertEquals(text.take(8) + "t͡ʃ" + text.drop(10), inserted.text)
        assertEquals(11, inserted.cursor)
        val directory = java.nio.file.Files.createTempDirectory("reson-ipa-long").toFile()
        try {
            val store = IpaDraftStore(java.io.File(directory, "draft.txt"))
            store.write(inserted.text)
            assertEquals(inserted.text, store.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun draftSurvivesReopeningAndClearIsSavedExactly() {
        val directory = java.nio.file.Files.createTempDirectory("reson-ipa").toFile()
        try {
            val file = java.io.File(directory, "draft.txt")
            IpaDraftStore(file).write("[t͡ʃə̃] 😀\nsecond line")
            assertEquals("[t͡ʃə̃] 😀\nsecond line", IpaDraftStore(file).read())
            IpaDraftStore(file).write("")
            assertEquals("", IpaDraftStore(file).read())
        } finally { directory.deleteRecursively() }
    }
}
