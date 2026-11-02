package org.reson.mobile

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CancellationException

class TextGridTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/textgrid/$name.TextGrid")!!.use { it.readBytes() }
    private fun simple(start: String = "0", end: String = "1", intervals: String = "0 1 \"a\"", count: Int = 1) =
        "\"ooTextFile\" \"TextGrid\" $start $end <exists> 1 \"IntervalTier\" \"phones\" $start $end $count $intervals"

    @Test fun desktopFixturesPreserveAllTimestampsLabelsAndTierTypes() {
        for (name in listOf("long", "short", "utf8_bom", "utf16le", "utf16be", "praat_long", "praat_short")) {
            val input = fixture(name)
            val copy = input.copyOf()
            val grid = TextGridParser.parse(input)
            val lines = javaClass.getResourceAsStream("/textgrid/$name.expected.tsv")!!.bufferedReader().use { it.readLines() }
            var tier = -1; var annotation = 0
            fun label(encoded: String) = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            for (line in lines) {
                val fields = line.split('\t')
                when (fields[0]) {
                    "document" -> { assertEquals(fields[1].toDouble(), grid.start.seconds, 0.0); assertEquals(fields[2].toDouble(), grid.end.seconds, 0.0) }
                    "tier" -> {
                        tier++; annotation = 0
                        assertEquals(fields[1], grid.tiers[tier].type)
                        assertEquals(fields[2].toDouble(), grid.tiers[tier].start.seconds, 0.0)
                        assertEquals(fields[3].toDouble(), grid.tiers[tier].end.seconds, 0.0)
                        assertEquals(label(fields[4]), grid.tiers[tier].name)
                    }
                    "annotation" -> {
                        val item = grid.tiers[tier].annotations[annotation++]
                        assertEquals(fields[1].toDouble(), item.start.seconds, 0.0)
                        assertEquals(fields[2].toDouble(), item.end.seconds, 0.0)
                        assertEquals(label(fields[3]), item.label)
                    }
                }
            }
            assertEquals(4, grid.tiers.size)
            assertArrayEquals(copy, input)
        }
    }

    @Test fun supportsPraatShortHeaderCommentsAndExactDecimalLiterals() {
        val text = simple(end = "1.000000000000000001", intervals = "0 0.000000001 \"\" 0.000000001 1.000000000000000001 \"x\"", count = 2)
            .replace("ooTextFile", "ooTextFile short") + " ! comment\n"
        val grid = TextGridParser.parse(text.toByteArray())
        assertEquals("1.000000000000000001", grid.end.literal)
        assertEquals("0.000000001", grid.tiers[0].annotations[0].end.literal)
    }

    @Test fun rejectsMalformedOrderingBoundsCountsQuotesAndEncoding() {
        for (bad in listOf("", "binary data", simple(intervals = "0 0.4 \"a\" 0.5 1 \"b\"", count = 2),
            simple(intervals = "0 0 \"a\""), simple().replace("1 <exists>", "NaN <exists>"),
            simple(count = -1), simple() + " trailing", simple(intervals = "0 1 \"unterminated"),
            simple().replace("\"phones\" 0 1", "\"phones\" -1 1"),
            simple(start = "-1e308", end = "1e308", intervals = "-1e308 1e308 \"overflow span\""))) {
            assertThrows("Should reject: $bad", TextGridException::class.java) { TextGridParser.parse(bad.toByteArray()) }
        }
        assertThrows(TextGridException::class.java) { TextGridParser.parse(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x01)) }
    }

    @Test fun visibleRangesAndHitTestsKeepSharedTimeCoordinates() {
        val grid = TextGridParser.parse(fixture("long"))
        val tier = grid.tiers[0]
        assertEquals(listOf(1), tier.visible(TimeViewport(1.1, 1.2)).toList())
        assertEquals(1, tier.hit(1.0, 0.01))
        val points = grid.tiers[2]
        assertEquals(0, points.hit(0.81, 0.02))
        assertNull(points.hit(1.0, 0.02))
        val zoom = grid.navigate(TimeViewport(0.5, 2.5), 2.0, 0.0, 0.25)
        assertEquals(1.0, zoom.timeAt(0.25), 1e-12)
        assertEquals(0.25, zoom.fraction(1.0), 1e-12)
        assertNotNull(grid.audioWarning(3.0))
        assertNull(grid.tiers[0].annotations[0].audioRange(0.75))
    }

    @Test fun parserChecksCancellation() {
        assertThrows(CancellationException::class.java) {
            TextGridParser.parse(fixture("long")) { throw CancellationException() }
        }
    }

    @Test fun tinyIntervalsStillRequireStrictChronologicalOrdering() {
        assertThrows(TextGridException::class.java) {
            TextGridParser.parse(simple(intervals = "0 0.00000001 \"a\" 0 0.00000002 \"b\" 0.00000002 1 \"c\"", count = 3).toByteArray())
        }
    }

    @Test fun denseIntervalsUseVisibleRangesAndShortZoomsStayBounded() {
        val intervals = (0 until 10000).joinToString(" ") { i ->
            "${java.math.BigDecimal(i).movePointLeft(4)} ${java.math.BigDecimal(i + 1).movePointLeft(4)} \"$i\""
        }
        val grid = TextGridParser.parse(simple(intervals = intervals, count = 10000).toByteArray())
        assertEquals(listOf(1234), grid.tiers[0].visible(TimeViewport(0.12341, 0.12349)).toList())
        assertEquals(9999, grid.tiers[0].hit(1.0, 0.0))
        val zoom = annotationView(grid.tiers[0].annotations.last(), TimeViewport(0.0, 1.0))
        assertEquals(1.0, zoom.end, 1e-12)
        assertEquals(0.005, zoom.span, 1e-12)
    }

    @Test fun duplicatePointsUnsupportedTiersAndOversizedLabelsAreRejected() {
        val point = "\"ooTextFile\" \"TextGrid\" 0 1 <exists> 1 \"TextTier\" \"events\" 0 1 2 0.5 \"a\" 0.5 \"b\""
        for (bad in listOf(point, point.replace("TextTier", "UnknownTier"),
            simple(intervals = "0 1 \"${"x".repeat(65537)}\""))) {
            assertThrows(TextGridException::class.java) { TextGridParser.parse(bad.toByteArray()) }
        }
    }
}
