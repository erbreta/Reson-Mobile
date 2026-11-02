package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavFileTest {
    @Test fun streamsPcmAndPublishesCorrectHeaderOnlyOnFinish() {
        val directory = Files.createTempDirectory("reson-wav").toFile()
        try {
            val writer = WavFile(directory, 48000)
            writer.append(shortArrayOf(0, 32767, -32768, -1), 4)
            assertTrue(directory.listFiles()!!.none { it.extension == "wav" })
            val result = writer.finish()
            val bytes = result.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(44, header.getInt(4))
            assertEquals("WAVEfmt ", String(bytes, 8, 8))
            assertEquals(16, header.getInt(16))
            assertEquals(1, header.getShort(20).toInt())
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(48000, header.getInt(24))
            assertEquals(96000, header.getInt(28))
            assertEquals(2, header.getShort(32).toInt())
            assertEquals(16, header.getShort(34).toInt())
            assertEquals("data", String(bytes, 36, 4))
            assertEquals(8, header.getInt(40))
            assertArrayEquals(byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1), bytes.copyOfRange(44, 52))
            assertEquals(8L, WavFile.inspect(result).dataBytes)
            assertEquals(4.0 / 48000, WavFile.inspect(result).durationSeconds, 1e-10)
        } finally { directory.deleteRecursively() }
    }

    @Test fun abortAndEmptyCaptureNeverAppearAsRecordings() {
        val directory = Files.createTempDirectory("reson-abort").toFile()
        try {
            val writer = WavFile(directory, 44100)
            writer.append(shortArrayOf(9), 1)
            writer.abort()
            assertTrue(directory.listFiles()!!.isEmpty())
            val empty = WavFile(directory, 44100)
            assertThrows(IllegalArgumentException::class.java) { empty.finish() }
            empty.abort()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun uniqueFilesAndTruncationValidation() {
        val directory = Files.createTempDirectory("reson-unique").toFile()
        try {
            val files = (1..2).map {
                WavFile(directory, 44100).apply { append(ShortArray(44100), 44100) }.finish()
            }
            assertNotEquals(files[0], files[1])
            assertEquals(1.0, WavFile.inspect(files[0]).durationSeconds, 0.0)
            val truncated = File(directory, "broken.wav").apply { writeBytes(files[0].readBytes().take(45).toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { WavFile.inspect(truncated) }
        } finally { directory.deleteRecursively() }
    }
}
