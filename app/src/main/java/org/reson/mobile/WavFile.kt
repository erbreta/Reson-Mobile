package org.reson.mobile

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only a fully closed, nonempty capture is published with a .wav extension. */
class WavFile(directory: File, val sampleRate: Int) {
    private val draft: File
    private val output: RandomAccessFile
    private var dataBytes = 0L

    init {
        require(sampleRate in 16000..96000)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        draft = File.createTempFile("Reson_${timestamp}_", ".pending", directory)
        try {
            output = RandomAccessFile(draft, "rw")
            try { output.write(ByteArray(44)) }
            catch (e: Exception) { output.close(); throw e }
        } catch (e: Exception) {
            draft.delete()
            throw e
        }
    }

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        require(dataBytes + count * 2L <= 0xffffffffL - 36) { "WAV size limit reached" }
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        output.write(bytes.array())
        dataBytes += count * 2L
    }

    fun finish(): File {
        require(dataBytes > 0) { "No audio captured; recording was not saved" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        header.putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
        output.seek(0)
        output.write(header.array())
        output.fd.sync()
        output.close()
        val published = File(draft.parentFile, draft.nameWithoutExtension + ".wav")
        check(!published.exists() && draft.renameTo(published)) { "Cannot publish WAV recording" }
        return published
    }

    fun abort() {
        try { output.close() } finally { draft.delete() }
    }

    companion object {
        fun inspect(file: File): Recording {
            val bytes = ByteArray(44)
            RandomAccessFile(file, "r").use { it.readFully(bytes) }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                String(bytes, 36, 4, Charsets.US_ASCII) == "data") { "Invalid WAV header" }
            val rate = header.getInt(24)
            val size = header.getInt(40).toLong() and 0xffffffffL
            require(header.getInt(16) == 16 && header.getShort(20).toInt() == 1 &&
                header.getShort(22).toInt() == 1 && header.getShort(34).toInt() == 16 &&
                rate in 16000..96000 && header.getInt(28) == rate * 2 &&
                header.getShort(32).toInt() == 2 && size > 0 && size % 2L == 0L &&
                file.length() == size + 44 &&
                (header.getInt(4).toLong() and 0xffffffffL) == size + 36) { "Incomplete or unsupported WAV" }
            return Recording(file, rate, size)
        }
    }
}

data class Recording(val file: File, val sampleRate: Int, val dataBytes: Long, val displayName: String = file.name) {
    val durationSeconds: Double get() = dataBytes.toDouble() / (sampleRate * 2)
}

internal fun recordingName(input: String, existing: List<String>): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it == '/' || it == '\\' || it.isISOControl() }) return null
    val name = if (trimmed.endsWith(".wav", ignoreCase = true)) trimmed.dropLast(4) + ".wav" else "$trimmed.wav"
    if (name == ".wav" || name.toByteArray(Charsets.UTF_8).size > 255 || existing.any { it.equals(name, ignoreCase = true) }) return null
    return name
}

internal fun deleteManagedRecording(directory: File, file: File): Boolean = try {
    file.parentFile?.canonicalFile == directory.canonicalFile && (!file.exists() || file.delete())
} catch (_: java.io.IOException) { false } catch (_: SecurityException) { false }
