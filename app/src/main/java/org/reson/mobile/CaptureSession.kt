package org.reson.mobile

import java.io.Closeable
import java.io.File
import kotlin.math.abs

internal interface CaptureInput : Closeable {
    val sampleRate: Int
    val description: String
    fun read(samples: ShortArray): Int
    fun stop()
}

/** Owns the blocking capture loop; stop() can interrupt it from the UI thread. */
internal class CaptureSession(
    private val directory: File,
    private val openInput: () -> CaptureInput,
) {
    private val lock = Any()
    @Volatile private var stopping = false
    private var input: CaptureInput? = null

    fun stop() = synchronized(lock) {
        if (!stopping) {
            stopping = true
            input?.stop()
        }
    }

    fun run(onLevel: (Double, Float, String) -> Unit): File? {
        var writer: WavFile? = null
        try {
            val source = synchronized(lock) {
                if (stopping) return null
                openInput().also { input = it }
            }
            writer = WavFile(directory, source.sampleRate)
            val samples = ShortArray(source.sampleRate / 10)
            var frames = 0L
            while (!stopping) {
                val count = source.read(samples)
                if (count <= 0) {
                    check(stopping) { "Microphone read failed ($count); recording discarded" }
                    break
                }
                writer.append(samples, count)
                frames += count
                var peak = 0
                for (i in 0 until count) peak = maxOf(peak, abs(samples[i].toInt()))
                onLevel(frames.toDouble() / source.sampleRate, peak / 32768f, source.description)
            }
            synchronized(lock) {
                try { input?.close() } finally { input = null }
            }
            return writer.finish()
        } catch (e: Exception) {
            writer?.abort()
            throw e
        } finally {
            synchronized(lock) {
                try { input?.close() } finally { input = null }
            }
        }
    }
}
