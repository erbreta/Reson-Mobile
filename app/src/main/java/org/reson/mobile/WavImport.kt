package org.reson.mobile

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/** Stream an external PCM16 WAV into the existing canonical mono library format. */
object WavImport {
    fun read(source: InputStream, directory: File, check: () -> Unit = {}): File {
        val input = DataInputStream(source)
        fun uint() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun ushort() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
        fun tag() = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        val scratch = ByteArray(8192)
        fun skip(size: Long) {
            var remaining = size
            while (remaining > 0) {
                check()
                val count = minOf(remaining, scratch.size.toLong()).toInt()
                input.readFully(scratch, 0, count); remaining -= count
            }
        }
        var writer: WavFile? = null
        try {
            check()
            require(tag() == "RIFF") { "Choose a RIFF WAV file" }
            val end = uint() + 8
            require(end in 44..(512L * 1024 * 1024)) { "WAV exceeds the 512 MiB import limit or has an invalid size" }
            require(tag() == "WAVE") { "Not a WAVE file" }
            var position = 12L
            var rate = 0; var channels = 0
            var dataSeen = false
            while (position < end) {
                check()
                require(end - position >= 8) { "Incomplete WAV chunk header" }
                val kind = tag(); val size = uint(); position += 8
                val padded = size + size % 2
                require(padded <= end - position) { "WAV chunk exceeds declared file bounds" }
                when (kind) {
                    "fmt " -> {
                        require(rate == 0 && !dataSeen && size >= 16) { "Invalid or duplicate WAV format chunk" }
                        val format = ushort(); channels = ushort(); rate = uint().toInt()
                        val byteRate = uint(); val alignment = ushort(); val bits = ushort()
                        require(format == 1 && channels in 1..2 && rate in 16000..96000 && bits == 16 &&
                            alignment == channels * 2 && byteRate == rate.toLong() * channels * 2) {
                            "Supported import: uncompressed PCM16, mono/stereo, 16,000–96,000 Hz. Float/24-bit/extensible WAV is unsupported."
                        }
                        skip(size - 16)
                    }
                    "data" -> {
                        require(rate > 0 && !dataSeen && size > 0 && size % (channels * 2) == 0L) { "Missing format, duplicate/empty data, or partial WAV frame" }
                        val frames = size / (channels * 2)
                        require(frames <= rate * 600L && frames * 4 < Runtime.getRuntime().maxMemory() / 3) {
                            "WAV exceeds 10 minutes or this device's analysis memory budget. Original retained."
                        }
                        val active = WavFile(directory, rate)
                        writer = active
                        val samples = ShortArray(scratch.size / (channels * 2))
                        var remaining = frames
                        while (remaining > 0) {
                            check()
                            val count = minOf(remaining, samples.size.toLong()).toInt()
                            input.readFully(scratch, 0, count * channels * 2)
                            for (i in 0 until count) {
                                var sum = 0
                                for (channel in 0 until channels) {
                                    val offset = (i * channels + channel) * 2
                                    sum += ((scratch[offset].toInt() and 255) or (scratch[offset + 1].toInt() shl 8)).toShort().toInt()
                                }
                                samples[i] = (sum / channels).toShort()
                            }
                            active.append(samples, count); remaining -= count
                        }
                        dataSeen = true
                    }
                    else -> skip(size)
                }
                if (size % 2L != 0L) input.readByte()
                position += padded
            }
            require(dataSeen && input.read() == -1) { "Missing WAV data or unexpected trailing bytes" }
            check()
            return checkNotNull(writer).finish()
        } catch (e: Exception) {
            writer?.abort()
            if (e is EOFException) throw IllegalArgumentException("Truncated WAV; no import saved", e)
            throw e
        }
    }
}
