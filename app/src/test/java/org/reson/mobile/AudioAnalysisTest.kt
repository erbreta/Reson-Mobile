package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.concurrent.CancellationException

class AudioAnalysisTest {
    private fun tone(hz: Double, amplitude: Double = 0.5, seconds: Double = 0.4) =
        AudioSignal(FloatArray((16000 * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / 16000)).toFloat() }, 16000)

    @Test fun pitchFindsKnownFrequenciesWithoutOctaveErrors() {
        for (hz in listOf(80.0, 120.0, 220.0, 440.0)) {
            val values = AudioAnalysis.pitch(tone(hz), AnalysisSettings()).mapNotNull { it.value }
            assertTrue("Missing $hz Hz", values.size > 20)
            assertEquals(hz, values.average(), 1.5)
        }
    }

    @Test fun silenceNoiseAndShortSignalsDoNotInventPitchOrPulses() {
        val random = java.util.Random(14)
        for (signal in listOf(AudioSignal(FloatArray(8000), 16000),
            AudioSignal(FloatArray(8000) { (random.nextGaussian() * 0.1).toFloat() }, 16000), tone(120.0, seconds = 0.001))) {
            val pitch = AudioAnalysis.pitch(signal, AnalysisSettings())
            assertTrue(pitch.none { it.value != null })
            assertTrue(AudioAnalysis.pulses(signal, pitch).isEmpty())
        }
    }

    @Test fun intensityUsesPowerDbfsAndOmitsDigitalSilence() {
        val loud = AudioAnalysis.intensity(tone(200.0)).mapNotNull { it.value }.average()
        val quiet = AudioAnalysis.intensity(tone(200.0, 0.25)).mapNotNull { it.value }.average()
        assertEquals(-9.0309, loud, 0.03)
        assertEquals(6.0206, loud - quiet, 0.03)
        assertTrue(AudioAnalysis.intensity(AudioSignal(FloatArray(8000), 16000)).all { it.value == null })
    }

    @Test fun pulsesFollowCyclesAndStayInsideVoicedFrames() {
        val signal = tone(125.0)
        val pitch = AudioAnalysis.pitch(signal, AnalysisSettings())
        val pulses = AudioAnalysis.pulses(signal, pitch)
        assertTrue(pulses.size > 30)
        pulses.zipWithNext().forEach { (a, b) -> assertEquals(0.008, b - a, 0.00015) }
        assertTrue(pulses.first() >= pitch.first().time)
        assertTrue(pulses.last() <= pitch.last().time)
    }

    @Test fun viewportKeepsTimeCoordinatesAndBounds() {
        val view = TimeViewport(2.0, 4.0).transform(10.0, 2.0, 0.0, 0.25)
        assertEquals(2.5, view.timeAt(0.25), 1e-9)
        assertEquals(1.0, view.span, 1e-9)
        assertEquals(0.25, view.fraction(2.5), 1e-9)
        val edge = view.transform(10.0, 1.0, -100.0, 0.5)
        assertEquals(10.0, edge.end, 1e-9)
    }

    @Test fun analysisHonoursCancellationAndRejectsBadSettings() {
        assertThrows(CancellationException::class.java) {
            AudioAnalysis.pitch(tone(120.0), AnalysisSettings()) { throw CancellationException() }
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisSettings(pitchFloor = 500.0, pitchCeiling = 75.0) }
    }

    @Test fun measurementsNeverBridgeMissingFramesOrAverageMissingPitchAsZero() {
        val track = listOf(AcousticPoint(0.02, 100.0), AcousticPoint(0.03, null), AcousticPoint(0.04, 200.0))
        assertNull(AnalysisMeasurements.at(track, 0.03))
        assertNull(AnalysisMeasurements.at(track, 0.5))
        assertEquals(150.0, AnalysisMeasurements.mean(track, 0.0, 0.1)!!, 0.0)
        val power = listOf(AcousticPoint(0.02, -10.0), AcousticPoint(0.03, -20.0))
        assertEquals(-12.5964, AnalysisMeasurements.meanPower(power, 0.0, 0.1)!!, 0.0001)
        assertEquals(-13.0103, AnalysisMeasurements.meanPower(listOf(AcousticPoint(0.02, -10.0),
            AcousticPoint(0.03, null)), 0.0, 0.1)!!, 0.0001)
    }

    @Test fun burgRecoversKnownResonancesForTheFiveFormantModel() {
        val random = java.util.Random(7)
        var values = DoubleArray(16000) { random.nextGaussian() }
        for ((frequency, bandwidth) in listOf(500.0 to 70.0, 1500.0 to 90.0, 2500.0 to 120.0, 3500.0 to 150.0, 4500.0 to 200.0)) {
            val r = exp(-PI * bandwidth / 16000)
            val a = 2 * r * cos(2 * PI * frequency / 16000)
            val filtered = DoubleArray(values.size)
            for (i in values.indices) filtered[i] = values[i] + (if (i > 0) a * filtered[i - 1] else 0.0) -
                (if (i > 1) r * r * filtered[i - 2] else 0.0)
            values = filtered
        }
        val peak = values.maxOf { abs(it) }
        val frames = AudioAnalysis.formants(AudioSignal(FloatArray(values.size) { (values[it] / peak * 0.5).toFloat() }, 16000), AnalysisSettings())
            .filter { it.time in 0.2..0.8 && it.hz.size == 4 }
        assertTrue("Stable four-formant frames", frames.size > 60)
        for ((i, expected) in listOf(500.0, 1500.0, 2500.0, 3500.0).withIndex()) {
            val frequencies = frames.mapNotNull { it.hz[i] }.sorted()
            assertTrue("Valid F${i + 1} candidates", frequencies.size > 60)
            assertEquals(expected, frequencies[frequencies.size / 2], 100.0)
        }
    }

    @Test fun pulseTimingDoesNotAccumulateIntegerSampleRounding() {
        val signal = tone(440.0, seconds = 0.8)
        val pulses = AudioAnalysis.pulses(signal, AudioAnalysis.pitch(signal, AnalysisSettings()))
        assertTrue(pulses.size > 300)
        assertEquals((pulses.size - 1) / 440.0, pulses.last() - pulses.first(), 1.0 / 16000)
    }

    @Test fun rejectingABroadLpcCandidateLeavesItsRankUnavailable() {
        var values = DoubleArray(24000) { if (it % 400 == 0) 1.0 else 0.0 }
        for ((frequency, bandwidth) in listOf(500.0 to 70.0, 1500.0 to 90.0, 2500.0 to 120.0, 3500.0 to 150.0)) {
            val radius = exp(-PI * bandwidth / 48000)
            val coefficient = 2 * radius * cos(2 * PI * frequency / 48000)
            val filtered = DoubleArray(values.size)
            for (i in values.indices) filtered[i] = values[i] + (if (i > 0) coefficient * filtered[i - 1] else 0.0) -
                (if (i > 1) radius * radius * filtered[i - 2] else 0.0)
            values = filtered
        }
        val peak = values.maxOf { abs(it) }
        val audio = AudioSignal(FloatArray(values.size) { ((values[it] / peak * 16000).roundToInt() / 32768.0).toFloat() }, 48000)
        val frames = AudioAnalysis.formants(audio, AnalysisSettings()).filter { it.time in 0.15..0.35 }
        assertTrue(frames.count { it.hz.size == 4 && it.hz[2] == null } > frames.size * 0.8)
    }

    @Test fun spectrogramMapsARealToneToFrequencyAndSampleTime() {
        val image = AudioAnalysis.spectrogram(tone(1000.0))
        assertEquals(0.0025, image.firstTime, 1e-9)
        val peak = (0 until image.bins).maxBy { image.power[image.bins * 20 + it].toInt() and 255 }
        assertEquals(1000.0, peak * image.frequencyStep, image.frequencyStep)
    }
    @Test fun closeZoomSpectrogramUsesRealAlignedFrames() {
        val signal = tone(1000.0, seconds = 1.0)
        val full = AudioAnalysis.spectrogram(signal)
        val detail = AudioAnalysis.spectrogram(signal, start = 0.2, end = 0.4, stepSeconds = 0.001)
        assertEquals(0.001, detail.step, 1e-9)
        assertEquals(0.2025, detail.firstTime, 1e-9)
        assertEquals(full.frequencyStep, detail.frequencyStep, 0.0)
        assertTrue(detail.columns in 190..201)
        val fullColumn = 40
        assertArrayEquals(full.power.copyOfRange(fullColumn * full.bins, (fullColumn + 1) * full.bins),
            detail.power.copyOfRange(0, detail.bins))
    }

    @Test fun intervalStatisticsIgnoreMissingFramesAndComputeOddAndEvenMedians() {
        val frames = listOf(AcousticPoint(0.01, 100.0), AcousticPoint(0.02, null),
            AcousticPoint(0.03, 300.0), AcousticPoint(0.04, 200.0), AcousticPoint(0.05, 400.0))
        assertEquals(200.0, AnalysisMeasurements.median(frames, 0.0, 0.04)!!, 0.0)
        assertEquals(250.0, AnalysisMeasurements.median(frames, 0.0, 0.05)!!, 0.0)
        assertNull(AnalysisMeasurements.median(frames, 0.015, 0.025))
        assertNull(AnalysisMeasurements.median(emptyList(), 0.0, 1.0))
    }

}
