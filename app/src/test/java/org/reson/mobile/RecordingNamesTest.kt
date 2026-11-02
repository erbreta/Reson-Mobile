package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RecordingNamesTest {
    @Test fun validatesNamesAndDuplicates() {
        assertEquals("صوت ə.wav", recordingName(" صوت ə ", listOf("other.wav")))
        assertNull(recordingName("  ", emptyList()))
        assertNull(recordingName("bad/name", emptyList()))
        assertNull(recordingName("bad\\name", emptyList()))
        assertNull(recordingName("same", listOf("same.wav")))
        assertEquals("same.wav", recordingName("same.wav", emptyList()))
    }

    @Test fun deletesOnlyTheManagedRecording() {
        val root = Files.createTempDirectory("reson-test").toFile()
        try {
            val library = root.resolve("recordings").apply { mkdir() }
            val selected = library.resolve("one.wav").apply { writeText("audio") }
            val other = library.resolve("two.wav").apply { writeText("other") }
            val external = root.resolve("source.wav").apply { writeText("source") }
            assertFalse(deleteManagedRecording(library, external))
            assertTrue(deleteManagedRecording(library, selected))
            assertTrue(deleteManagedRecording(library, selected))
            assertEquals("other", other.readText())
            assertEquals("source", external.readText())
        } finally { root.deleteRecursively() }
    }
}
