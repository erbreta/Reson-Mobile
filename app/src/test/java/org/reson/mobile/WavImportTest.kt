package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException

class WavImportTest {
    private fun wav(samples: ShortArray, rate: Int = 16000, channels: Int = 1, junk: Boolean = false): ByteArray {
        val extra = if (junk) 10 else 0
        val bytes = ByteBuffer.allocate(44 + extra + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVE".toByteArray())
        if (junk) bytes.put("JUNK".toByteArray()).putInt(1).put(7).put(0)
        bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { bytes.putShort(it) }
        return bytes.array()
    }

    @Test fun importsChunkedMonoWithoutChangingSamplesRateOrDuration() {
        val directory = Files.createTempDirectory("reson-import").toFile()
        try {
            val input = wav(shortArrayOf(-32768, 0, 32767, 123), junk = true)
            val snapshot = input.copyOf()
            val result = WavImport.read(input.inputStream(), directory)
            val recording = WavFile.inspect(result)
            assertEquals(16000, recording.sampleRate)
            assertEquals(4.0 / 16000, recording.durationSeconds, 0.0)
            assertArrayEquals(input.takeLast(8).toByteArray(), result.readBytes().drop(44).toByteArray())
            assertArrayEquals(snapshot, input)
            val signal = AudioAnalysis.load(recording)
            assertEquals(-1f, signal.samples[0], 0f)
        } finally { directory.deleteRecursively() }
    }

    @Test fun downmixesStereoByArithmeticMeanWithoutResampling() {
        val directory = Files.createTempDirectory("reson-stereo").toFile()
        try {
            val result = WavImport.read(wav(shortArrayOf(1000, 3000, -1000, 1000), 44100, 2).inputStream(), directory)
            val signal = AudioAnalysis.load(WavFile.inspect(result))
            assertEquals(44100, signal.rate)
            assertEquals(2, signal.samples.size)
            assertEquals(2000 / 32768f, signal.samples[0], 0f)
            assertEquals(0f, signal.samples[1], 0f)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failuresAndCancellationNeverPublishPartialImports() {
        val directory = Files.createTempDirectory("reson-invalid-import").toFile()
        try {
            val valid = wav(ShortArray(3200))
            val unsupported = valid.copyOf().also { it[20] = 3 }
            for (input in listOf(byteArrayOf(1, 2), valid.dropLast(1).toByteArray(), unsupported, valid + byteArrayOf(1))) {
                assertThrows(IllegalArgumentException::class.java) { WavImport.read(input.inputStream(), directory) }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
            var checks = 0
            assertThrows(CancellationException::class.java) {
                WavImport.read(valid.inputStream(), directory) { if (++checks >= 4) throw CancellationException() }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun importedSixteenKhzToneKeepsAnalysisTimingAndPitch() {
        val directory = Files.createTempDirectory("reson-import-analysis").toFile()
        try {
            val samples = ShortArray(6400) { (16384 * kotlin.math.sin(2 * Math.PI * 120 * it / 16000)).toInt().toShort() }
            val recording = WavFile.inspect(WavImport.read(wav(samples).inputStream(), directory))
            assertEquals(0.4, recording.durationSeconds, 0.0)
            val audio = AudioAnalysis.load(recording)
            val pitch = AudioAnalysis.pitch(audio, AnalysisSettings())
            assertEquals(0.02, pitch.first().time, 1e-12)
            assertEquals(120.0, pitch.mapNotNull { it.value }.average(), 1.5)
            assertEquals(0.0025, AudioAnalysis.spectrogram(audio).firstTime, 1e-12)
        } finally { directory.deleteRecursively() }
    }
}
