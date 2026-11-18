package dev.orcaslicer.android

import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to lines of an exported G-code file, for the preview's line window. Line numbers are
 * 1-based, matching libvgcode's PathVertex.gcode_id. The index of line starts is built once per file.
 */
class GcodeLines(private val file: File) {
    private val starts: LongArray by lazy {
        val out = ArrayList<Long>(1 shl 16).apply { add(0L) }
        file.inputStream().buffered(1 shl 16).use { input ->
            var pos = 0L
            while (true) {
                val b = input.read()
                if (b < 0) break
                pos++
                if (b == '\n'.code) out.add(pos)
            }
        }
        out.toLongArray()
    }

    val count: Int get() = starts.size

    /** Lines [first, last] (1-based, clamped) as (number, text) pairs. */
    fun window(center: Int, radius: Int): List<Pair<Int, String>> {
        if (center <= 0) return emptyList()
        val first = (center - radius).coerceAtLeast(1)
        val last = (center + radius).coerceAtMost(count)
        RandomAccessFile(file, "r").use { raf ->
            return (first..last).map { n ->
                val start = starts[n - 1]
                val end = if (n < count) starts[n] else raf.length()
                val bytes = ByteArray((end - start).toInt().coerceIn(0, 512))
                raf.seek(start); raf.readFully(bytes)
                n to String(bytes).trimEnd('\n', '\r')
            }
        }
    }
}
