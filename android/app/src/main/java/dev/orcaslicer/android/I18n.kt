package dev.orcaslicer.android

import java.io.File

/**
 * The desktop's translations (localization/i18n/<lang>/OrcaSlicer_<lang>.po, bundled in the
 * resources): setting labels, tooltips, options, page and group titles, and the app's words that
 * are the desktop's own (Prepare, Preview, Printer, Filament, Process, Slice plate...).
 */
object I18n {
    private var map: Map<String, String> = emptyMap()
    var language = "en"; private set

    /** Languages with a catalog in the resources ("en" is the source text). */
    fun languages(resources: File): List<String> =
        listOf("en") + (File(resources, "i18n").list()?.filter { File(resources, "i18n/$it/OrcaSlicer_$it.po").exists() }?.sorted().orEmpty())

    fun load(resources: File, lang: String) {
        language = lang
        map = if (lang == "en") emptyMap() else runCatching { parse(File(resources, "i18n/$lang/OrcaSlicer_$lang.po")) }.getOrDefault(emptyMap())
    }

    /** The translation of a desktop string, or the string itself. */
    fun tr(s: String): String = if (map.isEmpty() || s.isEmpty()) s else map[s] ?: s

    // Minimal gettext .po reader: msgctxt / msgid / msgstr (first plural form), multi-line strings, C escapes.
    private fun parse(file: File): Map<String, String> {
        val out = HashMap<String, String>(16384)
        var ctxt = false; var id = StringBuilder(); var str = StringBuilder(); var target: StringBuilder? = null
        fun flush() {
            if (!ctxt && id.isNotEmpty() && str.isNotEmpty()) out.putIfAbsent(id.toString(), str.toString())
            ctxt = false; id = StringBuilder(); str = StringBuilder(); target = null
        }
        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("msgctxt") -> { flush(); ctxt = true; target = null }
                line.startsWith("msgid_plural") -> target = null
                line.startsWith("msgid ") -> { if (id.isNotEmpty() || str.isNotEmpty()) flush(); target = id; target!!.append(unquote(line.substring(6))) }
                line.startsWith("msgstr[0]") -> { target = str; str.append(unquote(line.substring(9).trim())) }
                line.startsWith("msgstr[") -> target = null
                line.startsWith("msgstr ") -> { target = str; str.append(unquote(line.substring(7))) }
                line.startsWith("\"") -> target?.append(unquote(line))
                line.isEmpty() -> flush()
            }
        }
        flush()
        return out
    }

    private fun unquote(s: String): String {
        val t = s.trim().removeSurrounding("\"")
        if ('\\' !in t) return t
        val b = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); '"' -> b.append('"'); '\\' -> b.append('\\'); else -> { b.append(c); b.append(n) } }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}

/** Shorthand: the desktop's translation of [s]. */
fun tr(s: String) = I18n.tr(s)
