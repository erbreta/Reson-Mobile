package org.reson.mobile

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in exporter of the real Kotlin engine for Praat reference comparisons. No app fixtures. */
class AcousticReferenceTest {
    @Test fun exportRealEngineMeasurements() {
        val path = System.getenv("RESON_ANALYSIS_FIXTURES")
        assumeTrue("Set RESON_ANALYSIS_FIXTURES to prepared WAV directory", path != null)
        val directory = File(path!!)
        check(directory.isDirectory)
        for (file in directory.listFiles()!!.filter { it.extension == "wav" }.sorted()) {
            val started = System.nanoTime()
            val audio = AudioAnalysis.load(WavFile.inspect(file))
            File(directory, file.nameWithoutExtension + ".kotlin.csv").bufferedWriter().use { writer ->
                writer.appendLine("kind,time,v1,v2,v3,v4,confidence")
                val spectrum = AudioAnalysis.spectrogram(audio)
                check(spectrum.columns <= 20000 && spectrum.power.size == spectrum.columns * spectrum.bins)
                val pitch = AudioAnalysis.pitch(audio, AnalysisSettings())
                pitch.forEach { writer.appendLine("pitch,${it.time},${it.value ?: ""},,,,${it.confidence}") }
                AudioAnalysis.intensity(audio).forEach { writer.appendLine("intensity,${it.time},${it.value ?: ""},,,,") }
                for (ceiling in listOf(5500.0, 5000.0)) AudioAnalysis.formants(audio, AnalysisSettings(formantCeiling = ceiling)).forEach {
                    writer.appendLine("formant${ceiling.toInt()},${it.time},${(0..3).joinToString(",") { index -> it.hz.getOrNull(index)?.toString() ?: "" }},")
                }
                AudioAnalysis.pulses(audio, pitch).forEach { writer.appendLine("pulse,$it,,,,,") }
            }
            println("${file.name}: ${audio.duration} seconds, all numerical layers ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
}
