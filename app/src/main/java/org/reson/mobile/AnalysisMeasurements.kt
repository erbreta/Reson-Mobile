package org.reson.mobile

import kotlin.math.*

object AnalysisMeasurements {
    fun at(track: List<AcousticPoint>, time: Double): Double? {
        val i = track.binarySearchBy(time) { it.time }
        val insertion = if (i >= 0) i else -i - 1
        val nearest = listOfNotNull(track.getOrNull(insertion), track.getOrNull(insertion - 1))
            .minByOrNull { abs(it.time - time) }
        return nearest?.takeIf { abs(it.time - time) <= 0.006 }?.value
    }

    fun mean(track: List<AcousticPoint>, start: Double, end: Double): Double? =
        track.filter { it.time in start..end }.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()

    fun median(track: List<AcousticPoint>, start: Double, end: Double): Double? {
        val values = track.filter { it.time in start..end }.mapNotNull { it.value }.sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2
    }

    fun meanPower(track: List<AcousticPoint>, start: Double, end: Double): Double? =
        track.filter { it.time in start..end }.takeIf { frames -> frames.any { it.value != null } }
            ?.let { frames -> 10 * log10(frames.map { frame -> frame.value?.let { 10.0.pow(it / 10) } ?: 0.0 }.average()) }
}
