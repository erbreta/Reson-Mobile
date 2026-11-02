package org.reson.mobile

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.*

class TextGridException(message: String) : IllegalArgumentException(message)
data class GridTime(val literal: String, val decimal: BigDecimal) {
    val seconds = decimal.toDouble()
}
data class GridAnnotation(val start: GridTime, val end: GridTime, val label: String) {
    val duration: String get() = (end.decimal - start.decimal).toPlainString()
    fun audioRange(duration: Double): ClosedFloatingPointRange<Double>? =
        if (start.seconds >= 0 && end.seconds <= duration && end.seconds > start.seconds) start.seconds..end.seconds else null
}
data class GridTier(val name: String, val type: String, val start: GridTime, val end: GridTime,
    val annotations: List<GridAnnotation>) {
    private fun lower(time: Double, end: Boolean): Int {
        var low = 0; var high = annotations.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val value = if (end) annotations[middle].end.seconds else annotations[middle].start.seconds
            if (value < time) low = middle + 1 else high = middle
        }
        return low
    }
    fun visible(view: TimeViewport): IntRange {
        val first = lower(view.start, true)
        var last = lower(view.end, false)
        if (annotations.getOrNull(last)?.start?.seconds == view.end) last++
        return first until last
    }
    fun hit(time: Double, tolerance: Double): Int? {
        if (type == "TextTier") {
            val i = lower(time, false)
            return listOf(i - 1, i).filter { it in annotations.indices && abs(annotations[it].start.seconds - time) <= tolerance }
                .minByOrNull { abs(annotations[it].start.seconds - time) }
        }
        var index = lower(time, true)
        if (annotations.getOrNull(index)?.end?.seconds == time && index < annotations.lastIndex) index++
        return index.takeIf { it in annotations.indices && time >= annotations[it].start.seconds && time <= annotations[it].end.seconds }
    }
}
data class TextGridDocument(val start: GridTime, val end: GridTime, val tiers: List<GridTier>) {
    fun navigate(view: TimeViewport, zoom: Double, pan: Double, anchor: Double): TimeViewport {
        val offset = start.seconds
        val relative = TimeViewport(view.start - offset, view.end - offset).transform(end.seconds - offset, zoom, pan, anchor)
        return TimeViewport(relative.start + offset, relative.end + offset)
    }
    fun audioWarning(duration: Double): String? = if (abs(start.seconds) > 0.01 || abs(end.seconds - duration) > 0.01)
        "TextGrid bounds ${start.literal}–${end.literal} s differ from WAV bounds 0–$duration s by more than 10 ms. Timestamps are not rescaled." else null
}

/** Read-only text parser. No serializer, correction, normalization or source-file write path. */
object TextGridParser {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val tolerance = BigDecimal("0.000001")
    private val number = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")

    fun parse(bytes: ByteArray, check: () -> Unit = {}): TextGridDocument {
        check()
        if (bytes.size > MAX_BYTES) throw TextGridException("TextGrid exceeds 16 MiB import limit")
        val (encoding, skip) = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw TextGridException("Invalid text encoding; use UTF-8 or BOM-marked UTF-16 LE/BE")
        }
        if ('\u0000' in text) throw TextGridException("Binary TextGrids and UTF-16 without a BOM are unsupported")
        return Reader(text, check).parse()
    }

    private data class Token(val text: String, val quoted: Boolean, val line: Int)
    private class Reader(val source: String, val check: () -> Unit) {
        var position = 0; var line = 1
        var cached: Token? = null
        var total = 0
        fun fail(message: String): Nothing = throw TextGridException("$message (line ${cached?.line ?: line})")
        fun advance(): Char = source[position++].also { if (it == '\n') line++ }
        fun peek(): Token? {
            if (cached != null) return cached
            while (position < source.length) {
                check()
                if (source[position].isWhitespace()) { advance(); continue }
                if (source[position] == '!') { while (position < source.length && advance() != '\n') Unit; continue }
                val startLine = line
                val first = advance()
                if (first == '"') {
                    val value = StringBuilder()
                    var closed = false
                    while (position < source.length) {
                        if (position % 1024 == 0) check()
                        val c = advance()
                        if (c == '"') {
                            if (position < source.length && source[position] == '"') { advance(); value.append('"') }
                            else { closed = true; break }
                        } else value.append(c)
                    }
                    if (!closed) fail("Unterminated quoted string")
                    cached = Token(value.toString(), true, startLine)
                } else if (first in "=[]:") cached = Token(first.toString(), false, startLine)
                else {
                    val start = position - 1
                    while (position < source.length && !source[position].isWhitespace() && source[position] !in "=[]:!\"") advance()
                    cached = Token(source.substring(start, position), false, startLine)
                }
                return cached
            }
            return null
        }
        fun next(): Token = (peek() ?: fail("Unexpected end of TextGrid")).also { cached = null }
        fun expect(value: String) {
            val token = next()
            if (token.quoted || token.text != value) fail("Expected '$value', found '${token.text.take(40)}'")
        }
        fun string(): String = next().let {
            if (!it.quoted) fail("Expected quoted text")
            if (it.text.length > 65536) fail("Quoted string exceeds 65,536-character viewer limit")
            it.text
        }
        fun time(): GridTime {
            val token = next()
            if (token.quoted || token.text.length > 128 || !number.matches(token.text)) fail("Invalid timestamp '${token.text.take(40)}'")
            val decimal = try { BigDecimal(token.text) } catch (_: NumberFormatException) { fail("Invalid decimal timestamp") }
            if (abs(decimal.scale().toLong()) > 1000 || !decimal.toDouble().isFinite()) fail("Timestamp outside supported numeric range")
            return GridTime(token.text, decimal)
        }
        fun count(maximum: Int): Int {
            val token = next()
            val count = if (token.quoted) null else token.text.toIntOrNull()
            if (count == null || count !in 0..maximum) fail("Count must be an integer from 0 to $maximum")
            return count
        }
        fun field(name: String) { expect(name); expect("=") }
        fun record(name: String, index: Int?) {
            expect(name); expect("[")
            if (index != null && count(100000) != index) fail("Unexpected $name record index; expected $index")
            expect("]"); expect(":")
        }
        fun parse(): TextGridDocument {
            check()
            val header = if (peek()?.text == "File") { expect("File"); expect("type"); expect("="); string() } else string()
            if (header !in listOf("ooTextFile", "ooTextFile short")) fail("Only Praat long/short text TextGrids are supported")
            if (peek()?.text == "Object") { expect("Object"); expect("class"); expect("=") }
            if (string() != "TextGrid") fail("Object class must be TextGrid")
            val long = peek()?.text == "xmin"
            if (long) field("xmin")
            val start = time()
            if (long) field("xmax")
            val end = time()
            if (end.decimal <= start.decimal || end.seconds <= start.seconds || !(end.seconds - start.seconds).isFinite())
                fail("Document bounds must define a positive, finite display timeline")
            if (long) { expect("tiers?"); if (peek()?.text == "=") expect("=") }
            expect("<exists>")
            if (long) field("size")
            val count = count(256)
            if (long) record("item", null)
            val tiers = (1..count).map { index ->
                check()
                if (long) { record("item", index); field("class") }
                val type = string()
                if (type !in listOf("IntervalTier", "TextTier")) fail("Unsupported tier class '$type'")
                if (long) field("name")
                val name = string()
                if (long) field("xmin")
                val left = time()
                if (long) field("xmax")
                val right = time()
                if (right.decimal <= left.decimal || left.decimal < start.decimal - tolerance || right.decimal > end.decimal + tolerance)
                    fail("Tier '$name' has invalid bounds outside its document")
                val kind = if (type == "IntervalTier") "intervals" else "points"
                if (long) { expect(kind); expect(":"); field("size") }
                val size = count(100000 - total)
                total += size
                val items = (1..size).map { item ->
                    check()
                    if (long) { record(kind, item); field(if (type == "IntervalTier") "xmin" else "number") }
                    val a = time()
                    val b = if (type == "IntervalTier") { if (long) field("xmax"); time() } else a
                    if (long) field(if (type == "IntervalTier") "text" else "mark")
                    GridAnnotation(a, b, string())
                }
                if (type == "IntervalTier") {
                    if (items.isEmpty()) fail("IntervalTier '$name' must contain a partition")
                    if ((items.first().start.decimal - left.decimal).abs() > tolerance ||
                        (items.last().end.decimal - right.decimal).abs() > tolerance) fail("Tier '$name' intervals do not cover its bounds")
                }
                items.forEachIndexed { i, item ->
                    if (item.start.decimal < left.decimal - tolerance || item.end.decimal > right.decimal + tolerance)
                        fail("Annotation ${i + 1} in '$name' is outside tier bounds")
                    if (type == "IntervalTier") {
                        if (item.end.decimal <= item.start.decimal) fail("Interval ${i + 1} in '$name' has non-positive duration")
                        if (i > 0) {
                            val previous = items[i - 1]
                            if (item.start.decimal <= previous.start.decimal || item.end.decimal <= previous.end.decimal)
                                fail("Intervals in '$name' must be strictly chronological")
                            if ((previous.end.decimal - item.start.decimal).abs() > tolerance) fail("Gap/overlap before interval ${i + 1} in '$name'")
                        }
                    } else if (i > 0 && items[i - 1].start.decimal >= item.start.decimal) fail("Points in '$name' must be strictly ordered without duplicates")
                }
                GridTier(name, type, left, right, items)
            }
            if (peek() != null) fail("Unexpected trailing data")
            return TextGridDocument(start, end, tiers)
        }
    }
}
