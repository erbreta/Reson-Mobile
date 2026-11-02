package org.reson.mobile

import java.io.RandomAccessFile
import kotlin.math.*

data class AnalysisSettings(val pitchFloor: Double = 75.0, val pitchCeiling: Double = 500.0,
    val formantCeiling: Double = 5500.0) {
    init {
        require(pitchFloor.isFinite() && pitchCeiling.isFinite() && pitchFloor in 50.0..300.0 &&
            pitchCeiling in 150.0..800.0 && pitchCeiling > pitchFloor)
        require(formantCeiling in listOf(5000.0, 5500.0))
    }
}

data class AudioSignal(val samples: FloatArray, val rate: Int) {
    init { require(rate > 0 && samples.all { it.isFinite() }) }
    val duration get() = samples.size.toDouble() / rate
}
data class AcousticPoint(val time: Double, val value: Double?, val confidence: Double = 0.0)
data class FormantPoint(val time: Double, val hz: List<Double?>)
data class SpectralImage(val columns: Int, val bins: Int, val step: Double,
    val firstTime: Double, val frequencyStep: Double, val power: ByteArray)

data class TimeViewport(val start: Double, val end: Double) {
    val span get() = end - start
    fun timeAt(fraction: Double) = start + fraction.coerceIn(0.0, 1.0) * span
    fun fraction(time: Double) = (time - start) / span
    fun transform(duration: Double, zoom: Double, panFraction: Double, anchor: Double): TimeViewport {
        val width = (span / zoom).coerceIn(min(0.005, duration), duration)
        val left = (timeAt(anchor) - anchor * width - panFraction * span).coerceIn(0.0, duration - width)
        return TimeViewport(left, left + width)
    }
}

/** Offline estimates; see ANALYSIS.md for methods, units and validation. */
object AudioAnalysis {
    fun load(recording: Recording, check: () -> Unit = {}): AudioSignal {
        val valid = WavFile.inspect(recording.file)
        require(valid.durationSeconds <= 600.0) { "Analysis supports recordings up to 10 minutes; original WAV retained." }
        require(valid.dataBytes * 2 < Runtime.getRuntime().maxMemory() / 3) {
            "Recording exceeds this device's analysis memory budget; analyse a shorter recording. Original WAV retained."
        }
        val samples = FloatArray((valid.dataBytes / 2).toInt())
        RandomAccessFile(valid.file, "r").use { input ->
            input.seek(44)
            val bytes = ByteArray(16384)
            var index = 0
            while (index < samples.size) {
                check()
                val count = min(bytes.size / 2, samples.size - index)
                input.readFully(bytes, 0, count * 2)
                for (i in 0 until count) samples[index++] =
                    ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            }
        }
        return AudioSignal(samples, valid.sampleRate)
    }

    /** Windowed sinc low-pass resampling; finite 64-tap support, not Praat's resampler. */
    private fun resample(audio: AudioSignal, rate: Int, check: () -> Unit): AudioSignal {
        if (rate == audio.rate) return audio
        val ratio = audio.rate.toDouble() / rate
        val cutoff = min(1.0, 1.0 / ratio) * 0.94
        val radius = ceil(32 / cutoff).toInt()
        val phases = 256
        val kernels = Array(phases) { phase ->
            DoubleArray(2 * radius + 1) { k ->
                val x = k - radius - phase.toDouble() / phases
                val z = PI * cutoff * x
                (if (abs(z) < 1e-10) cutoff else cutoff * sin(z) / z) *
                    (0.5 + 0.5 * cos(PI * x / (radius + 1)))
            }
        }
        val output = FloatArray((audio.samples.size / ratio).toInt())
        for (i in output.indices) {
            if (i % 1024 == 0) check()
            val position = i * ratio
            val centre = position.toInt()
            val kernel = kernels[((position - centre) * phases).toInt().coerceIn(0, phases - 1)]
            var sum = 0.0; var weight = 0.0
            for (k in kernel.indices) {
                val j = centre + k - radius
                if (j in audio.samples.indices) { sum += audio.samples[j] * kernel[k]; weight += kernel[k] }
            }
            output[i] = (sum / weight).toFloat()
        }
        return AudioSignal(output, rate)
    }

    fun pitch(audio: AudioSignal, settings: AnalysisSettings, check: () -> Unit = {}): List<AcousticPoint> {
        check()
        val sound = resample(audio, 4000, check)
        val n = ceil(3.0 * sound.rate / settings.pitchFloor).toInt()
        val hop = 40
        val minimum = floor(sound.rate / settings.pitchCeiling).toInt().coerceAtLeast(2)
        val maximum = ceil(sound.rate / settings.pitchFloor).toInt()
        val result = ArrayList<AcousticPoint>()
        var previous: Double? = null
        val frame = DoubleArray(n)
        val correlation = DoubleArray(maximum + 2)
        for (start in 0..(sound.samples.size - n) step hop) {
            check()
            var mean = 0.0
            for (i in 0 until n) mean += sound.samples[start + i]
            mean /= n
            var energy = 0.0
            for (i in 0 until n) { frame[i] = sound.samples[start + i] - mean; energy += frame[i] * frame[i] }
            var bestLag = 0; var bestScore = -1.0
            if (energy / n > 1e-8) {
                for (lag in minimum - 1..maximum + 1) {
                    var cross = 0.0; var a = 0.0; var b = 0.0
                    for (i in 0 until n - lag) {
                        cross += frame[i] * frame[i + lag]; a += frame[i] * frame[i]; b += frame[i + lag] * frame[i + lag]
                    }
                    correlation[lag] = if (a * b > 1e-20) cross / sqrt(a * b) else 0.0
                }
                for (lag in minimum..maximum) {
                    val r = correlation[lag]
                    if (r < 0.65 || r < correlation[lag - 1] || r < correlation[lag + 1]) continue
                    val f = sound.rate.toDouble() / lag
                    val score = r - 0.015 * log2(lag.toDouble() / minimum) -
                        (previous?.let { 0.03 * abs(log2(f / it)) } ?: 0.0)
                    if (score > bestScore) { bestScore = score; bestLag = lag }
                }
            }
            var value: Double? = null
            if (bestLag > 0) {
                val left = correlation[bestLag - 1]; val mid = correlation[bestLag]; val right = correlation[bestLag + 1]
                val denominator = left - 2 * mid + right
                val offset = if (abs(denominator) > 1e-10) (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5) else 0.0
                val f = sound.rate / (bestLag + offset)
                if (f in settings.pitchFloor..settings.pitchCeiling) value = f
            }
            result.add(AcousticPoint((start + n / 2.0) / sound.rate, value,
                if (value == null) 0.0 else correlation[bestLag]))
            previous = value
        }
        return result
    }

    /** 40 ms Hann-weighted mean-square after DC removal, hop 10 ms, ref full-scale 1. */
    fun intensity(audio: AudioSignal, check: () -> Unit = {}): List<AcousticPoint> {
        val n = (audio.rate * 0.04).roundToInt().coerceAtLeast(2)
        val hop = (audio.rate * 0.01).roundToInt().coerceAtLeast(1)
        val weights = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val sum = weights.sum()
        return (0..audio.samples.size - n step hop).map { start ->
            check()
            var mean = 0.0
            for (i in 0 until n) mean += audio.samples[start + i]
            mean /= n
            var power = 0.0
            for (i in 0 until n) { val x = audio.samples[start + i] - mean; power += x * x * weights[i] }
            AcousticPoint((start + n / 2.0) / audio.rate, if (power > 1e-20) 10 * log10(power / sum) else null)
        }
    }

    fun formants(audio: AudioSignal, settings: AnalysisSettings, check: () -> Unit = {}): List<FormantPoint> {
        val sound = resample(audio, (2 * settings.formantCeiling).toInt(), check)
        val rate = sound.rate
        val n = (rate * 0.05).roundToInt()
        val hop = (rate * 0.00625).roundToInt()
        val pre = exp(-2 * PI * 50 / rate)
        val window = DoubleArray(n) { (exp(-48.0 * ((it - (n - 1) / 2.0) / (n + 1)).pow(2)) - exp(-12.0)) / (1 - exp(-12.0)) }
        return (1..sound.samples.size - n step hop).map { start ->
            check()
            val frame = DoubleArray(n) { (sound.samples[start + it] - pre * sound.samples[start + it - 1]) * window[it] }
            val hz = if (frame.sumOf { it * it } / n < 1e-12) emptyList() else {
                val coefficients = burg(frame, 10)
                val roots = roots(coefficients, check)
                roots.filter { it.im > 0 && it.magnitude in 0.0..1.0 }.mapNotNull {
                    val frequency = atan2(it.im, it.re) * rate / (2 * PI)
                    val bandwidth = -ln(it.magnitude) * rate / PI
                    if (frequency in 50.0..settings.formantCeiling - 50) frequency to bandwidth else null
                }.sortedBy { it.first }.take(4).let { candidates ->
                    candidates.mapIndexed { i, (frequency, bandwidth) ->
                        // Retain candidate rank when rejecting a broad or duplicate pole.
                        if (bandwidth in 0.0..700.0 && (i == 0 || frequency - candidates[i - 1].first > 50.0)) frequency else null
                    }
                }
            }
            FormantPoint((start + n / 2.0) / rate, hz)
        }
    }

    private fun burg(frame: DoubleArray, order: Int): DoubleArray {
        var forward = frame.copyOf(); var backward = frame.copyOf()
        var a = doubleArrayOf(1.0)
        for (m in 1..order) {
            var numerator = 0.0; var denominator = 0.0
            for (i in m until frame.size) { numerator += forward[i] * backward[i - 1]; denominator += forward[i].pow(2) + backward[i - 1].pow(2) }
            if (denominator <= 1e-25) return doubleArrayOf(1.0)
            val reflection = (-2 * numerator / denominator).coerceIn(-0.999999, 0.999999)
            val next = DoubleArray(m + 1); next[0] = 1.0; next[m] = reflection
            for (j in 1 until m) next[j] = a[j] + reflection * a[m - j]
            a = next
            val f = forward.copyOf(); val b = backward.copyOf()
            for (i in m until frame.size) { forward[i] = f[i] + reflection * b[i - 1]; backward[i] = b[i - 1] + reflection * f[i] }
        }
        return a
    }

    private data class Complex(val re: Double, val im: Double) {
        val magnitude get() = hypot(re, im)
        operator fun plus(b: Complex) = Complex(re + b.re, im + b.im)
        operator fun minus(b: Complex) = Complex(re - b.re, im - b.im)
        operator fun times(b: Complex) = Complex(re * b.re - im * b.im, re * b.im + im * b.re)
        operator fun div(b: Complex): Complex {
            val d = b.re * b.re + b.im * b.im
            return Complex((re * b.re + im * b.im) / d, (im * b.re - re * b.im) / d)
        }
    }

    /** Durand–Kerner polynomial roots; unconverged frames are unavailable. */
    private fun roots(a: DoubleArray, check: () -> Unit): List<Complex> {
        if (a.size < 2) return emptyList()
        val roots = Array(a.size - 1) { i -> val angle = 2 * PI * (i + 0.25) / (a.size - 1); Complex(0.9 * cos(angle), 0.9 * sin(angle)) }
        repeat(150) {
            check()
            var largest = 0.0
            for (i in roots.indices) {
                var p = Complex(a[0], 0.0)
                for (j in 1 until a.size) p = p * roots[i] + Complex(a[j], 0.0)
                var denominator = Complex(1.0, 0.0)
                for (j in roots.indices) if (j != i) denominator = denominator * (roots[i] - roots[j])
                val delta = p / denominator
                if (!delta.magnitude.isFinite()) return emptyList()
                roots[i] = roots[i] - delta
                largest = max(largest, delta.magnitude)
            }
            if (largest < 1e-9) return roots.toList()
        }
        return emptyList()
    }

    fun pulses(audio: AudioSignal, pitch: List<AcousticPoint>, check: () -> Unit = {}): List<Double> {
        val result = ArrayList<Double>()
        fun sample(position: Double): Double {
            val i = position.toInt()
            if (i < 0 || i + 1 >= audio.samples.size) return 0.0
            val fraction = position - i
            return audio.samples[i] * (1 - fraction) + audio.samples[i + 1] * fraction
        }
        var index = 0
        while (index < pitch.size) {
            check()
            if (pitch[index].value == null || pitch[index].confidence < 0.85) { index++; continue }
            val first = index
            while (index + 1 < pitch.size && pitch[index + 1].value != null && pitch[index + 1].confidence >= 0.85) index++
            val last = index++
            val from = (pitch[first].time * audio.rate).roundToInt()
            val until = (pitch[last].time * audio.rate).toInt()
            val middle = (from + until) / 2
            val seedPeriod = audio.rate / pitch[(first + last) / 2].value!!
            if (until - from < 2 * seedPeriod) continue
            val seed = (max(from, middle - (seedPeriod / 2).toInt())..min(until, middle + (seedPeriod / 2).toInt()))
                .maxByOrNull { abs(audio.samples[it]) }!!.toDouble()
            result.add(seed / audio.rate)
            for (direction in listOf(-1, 1)) {
                var pulse = seed
                while (true) {
                    check()
                    val frame = pitch.binarySearchBy(pulse / audio.rate) { it.time }
                        .let { if (it < 0) -it - 1 else it }.coerceIn(first, last)
                    val period = audio.rate / pitch[frame].value!!
                    val half = (period / 2).toInt()
                    if (pulse + direction * 1.2 * period !in from.toDouble()..until.toDouble()) break
                    fun correlation(lag: Double): Double {
                        val candidate = pulse + direction * lag
                        if (min(pulse, candidate) - half < 0 || max(pulse, candidate) + half + 1 >= audio.samples.size) return 0.0
                        var cross = 0.0; var a = 0.0; var b = 0.0
                        for (k in -half..half step max(1, audio.rate / 8000)) {
                            val x = sample(pulse + k); val y = sample(candidate + k)
                            cross += x * y; a += x * x; b += y * y
                        }
                        return if (a * b > 1e-20) cross / sqrt(a * b) else 0.0
                    }
                    var best = -1; var bestCorrelation = 0.85
                    for (lag in (0.8 * period).toInt()..(1.2 * period).toInt()) {
                        val r = correlation(lag.toDouble())
                        if (r > bestCorrelation) { bestCorrelation = r; best = lag }
                    }
                    if (best < 0) break
                    val left = correlation(best - 1.0); val right = correlation(best + 1.0)
                    val denominator = left - 2 * bestCorrelation + right
                    val delta = if (abs(denominator) > 1e-12) (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5) else 0.0
                    pulse += direction * (best + delta)
                    result.add(pulse / audio.rate)
                }
            }
        }
        return result.sorted()
    }

    fun spectrogram(audio: AudioSignal, check: () -> Unit = {}, start: Double = 0.0,
        end: Double = audio.duration, stepSeconds: Double = 0.005): SpectralImage {
        val n = (0.005 * audio.rate).roundToInt().coerceAtLeast(2)
        var fftSize = 1
        while (fftSize < n * 2) fftSize *= 2
        val hop = max((audio.rate * stepSeconds).roundToInt(), ceil((end - start) * audio.rate / 20000.0).toInt()).coerceAtLeast(1)
        val first = (start * audio.rate).roundToInt().coerceIn(0, audio.samples.size)
        val last = (end * audio.rate).roundToInt().coerceIn(first, audio.samples.size)
        val columns = if (last - first < n) 0 else (last - first - n) / hop + 1
        val bins = (5500.0 * fftSize / audio.rate).toInt() + 1
        val output = ByteArray(columns * bins)
        val real = DoubleArray(fftSize); val imaginary = DoubleArray(fftSize)
        for (c in 0 until columns) {
            check(); real.fill(0.0); imaginary.fill(0.0)
            for (i in 0 until n) real[i] = audio.samples[first + c * hop + i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
            fft(real, imaginary)
            for (b in 0 until bins) {
                val db = 10 * log10((real[b].pow(2) + imaginary[b].pow(2)).coerceAtLeast(1e-20) / (n * n))
                output[c * bins + b] = ((db + 100).coerceIn(0.0, 100.0) * 2.55).roundToInt().toByte()
            }
        }
        return SpectralImage(columns, bins, hop.toDouble() / audio.rate, (first + n / 2.0) / audio.rate,
            audio.rate.toDouble() / fftSize, output)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until re.size) {
            var bit = re.size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val x = re[i]; re[i] = re[j]; re[j] = x }
        }
        var length = 2
        while (length <= re.size) {
            val angle = -2 * PI / length
            for (start in re.indices step length) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k; val b = a + length / 2
                    val tr = wr * re[b] - wi * im[b]; val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val next = wr * cos(angle) - wi * sin(angle); wi = wr * sin(angle) + wi * cos(angle); wr = next
                }
            }
            length *= 2
        }
    }
}
