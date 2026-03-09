package com.notenotes.export

import android.util.Log

/**
 * Sanitize legacy MusicXML for alphaTab compatibility.
 *
 * alphaTab crashes with "cannot read properties of undefined (reading 'push')"
 * when a note's <type> doesn't match its <duration>.  The old MusicXmlGenerator
 * used threshold-based type assignment (ticksToType) that produced mismatches
 * like duration=14 / type="half" (half = 8 divisions).
 *
 * This sanitizer:
 *  1. Fixes <type> and <dot/> for notes whose durations ARE standard values.
 *  2. Decomposes non-standard durations into tied standard-duration sub-notes.
 */
object MusicXmlSanitizer {

    private const val TAG = "MusicXmlSanitizer"

    private data class StdDur(val ticks: Int, val typeName: String, val dotted: Boolean)

    /** Build the list of valid standard note durations for the given divisions. */
    private fun standardDurations(div: Int): List<StdDur> = listOfNotNull(
        StdDur(div * 4, "whole", false),
        StdDur(div * 3, "half", true),            // dotted half
        StdDur(div * 2, "half", false),
        if (div * 3 % 2 == 0) StdDur(div * 3 / 2, "quarter", true) else null,  // dotted quarter
        StdDur(div, "quarter", false),
        if (div * 3 % 4 == 0) StdDur(div * 3 / 4, "eighth", true) else null,   // dotted eighth
        if (div % 2 == 0) StdDur(div / 2, "eighth", false) else null,
        if (div % 4 == 0) StdDur(div / 4, "16th", false) else null
    ).filter { it.ticks > 0 }.sortedByDescending { it.ticks }

    /** Greedy decomposition of [ticks] into a list of standard durations. */
    private fun decompose(ticks: Int, stds: List<StdDur>): List<StdDur> {
        val result = mutableListOf<StdDur>()
        var rem = ticks
        while (rem > 0) {
            val sd = stds.firstOrNull { it.ticks <= rem }
            if (sd != null) { result.add(sd); rem -= sd.ticks }
            else { result.add(StdDur(rem, "16th", false)); break }
        }
        return result
    }

    // ── Public API ──────────────────────────────────────────────

    /** Sanitize the MusicXML string.  Returns the original if no fixes are needed. */
    fun sanitize(xml: String): String {
        val divMatch = Regex("""<divisions>(\d+)</divisions>""").find(xml) ?: return xml
        val div = divMatch.groupValues[1].toInt()
        val stds = standardDurations(div)
        val stdSet = stds.map { it.ticks }.toSet()

        // Quick scan – if every duration is already standard, just fix type/dot
        val hasBad = Regex("""<duration>(\d+)</duration>""").findAll(xml)
            .any { it.groupValues[1].toInt() !in stdSet }

        if (!hasBad) {
            Log.d(TAG, "All durations standard – fixing types only")
            return fixTypes(xml, stds)
        }
        Log.d(TAG, "Non-standard durations found – decomposing")
        return rebuildMeasures(xml, stds, stdSet)
    }

    // ── Fix types only (fast path) ──────────────────────────────

    private fun fixTypes(xml: String, stds: List<StdDur>): String {
        return Regex("""<note>(.*?)</note>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            val c = m.groupValues[1]
            val dur = Regex("""<duration>(\d+)</duration>""").find(c)?.groupValues?.get(1)?.toInt()
            val std = dur?.let { d -> stds.find { it.ticks == d } }
            if (std != null) {
                var f = c.replace(Regex("""<type>\w+</type>"""), "<type>${std.typeName}</type>")
                if (std.dotted && !f.contains("<dot/>"))
                    f = f.replace("</type>", "</type>\n        <dot/>")
                else if (!std.dotted)
                    f = f.replace(Regex("""\n\s*<dot/>"""), "")
                "<note>$f</note>"
            } else m.value
        }
    }

    // ── Full rebuild (slow path) ────────────────────────────────

    private fun rebuildMeasures(xml: String, stds: List<StdDur>, stdSet: Set<Int>): String {
        val out = StringBuilder()
        val mRe = Regex("""(<measure\b[^>]*>)(.*?)(</measure>)""", RegexOption.DOT_MATCHES_ALL)
        var last = 0
        for (m in mRe.findAll(xml)) {
            out.append(xml, last, m.range.first)
            out.append(m.groupValues[1])                   // <measure …>
            out.append(processMeasure(m.groupValues[2], stds, stdSet))
            out.append(m.groupValues[3])                   // </measure>
            last = m.range.last + 1
        }
        out.append(xml.substring(last))
        return out.toString()
    }

    private data class NI(val text: String, val isChord: Boolean, val dur: Int,
                          val s: Int, val e: Int)

    private fun processMeasure(content: String, stds: List<StdDur>, stdSet: Set<Int>): String {
        val noteRe = Regex("""<note>.*?</note>""", RegexOption.DOT_MATCHES_ALL)
        val notes = noteRe.findAll(content).map { m ->
            NI(m.value, m.value.contains("<chord/>"),
                Regex("""<duration>(\d+)</duration>""").find(m.value)
                    ?.groupValues?.get(1)?.toInt() ?: 0,
                m.range.first, m.range.last + 1)
        }.toList()

        if (notes.isEmpty()) return content

        // Group into chord groups (root + subsequent <chord/> notes)
        val groups = mutableListOf<MutableList<NI>>()
        for (n in notes) {
            if (n.isChord && groups.isNotEmpty()) groups.last().add(n)
            else groups.add(mutableListOf(n))
        }

        val out = StringBuilder()
        var last = 0
        for (g in groups) {
            out.append(content, last, g.first().s)
            last = g.last().e

            val dur = g[0].dur
            if (dur in stdSet) {
                val std = stds.first { it.ticks == dur }
                for (ni in g) out.append(fixNoteType(ni.text, std))
            } else {
                out.append(decomposeGroup(g, decompose(dur, stds)))
            }
        }
        out.append(content.substring(last))
        return out.toString()
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun fixNoteType(xml: String, std: StdDur): String {
        var f = xml.replace(Regex("""<type>\w+</type>"""), "<type>${std.typeName}</type>")
        if (std.dotted && !f.contains("<dot/>"))
            f = f.replace("</type>", "</type>\n        <dot/>")
        else if (!std.dotted)
            f = f.replace(Regex("""\n\s*<dot/>"""), "")
        return f
    }

    /**
     * Decompose a chord group into sub-groups of standard-duration tied notes.
     * For rests, consecutive rests are emitted without ties.
     */
    private fun decomposeGroup(group: List<NI>, subs: List<StdDur>): String {
        val sb = StringBuilder()
        val isRest = group[0].text.contains("<rest/>")

        for ((si, sd) in subs.withIndex()) {
            val first = si == 0
            val last  = si == subs.size - 1

            for (ni in group) {
                val origStart = ni.text.contains("""<tie type="start"/>""")
                val origStop  = ni.text.contains("""<tie type="stop"/>""")

                // Merge original ties with decomposition ties
                val tieStart = if (isRest) (last && origStart)
                               else if (last) origStart else true
                val tieStop  = if (isRest) (first && origStop)
                               else if (first) origStop else true

                sb.append(buildNote(ni.text, sd.ticks, sd.typeName, sd.dotted,
                    tieStart, tieStop, keepTech = true))
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** Rebuild a <note> element with corrected duration/type/ties. */
    private fun buildNote(
        original: String, dur: Int, typeName: String, dotted: Boolean,
        tieStart: Boolean, tieStop: Boolean, keepTech: Boolean
    ): String {
        val isChord = original.contains("<chord/>")
        val isRest  = original.contains("<rest/>")
        val step    = Regex("""<step>(\w+)</step>""").find(original)?.groupValues?.get(1)
        val alter   = Regex("""<alter>(-?\d+)</alter>""").find(original)?.groupValues?.get(1)
        val octave  = Regex("""<octave>(\d+)</octave>""").find(original)?.groupValues?.get(1)
        val voice   = Regex("""<voice>(\d+)</voice>""").find(original)?.groupValues?.get(1) ?: "1"
        val str     = if (keepTech) Regex("""<string>(\d+)</string>""").find(original)?.groupValues?.get(1) else null
        val fret    = if (keepTech) Regex("""<fret>(\d+)</fret>""").find(original)?.groupValues?.get(1) else null

        val sb = StringBuilder()
        sb.append("      <note>\n")
        if (isChord) sb.append("        <chord/>\n")

        if (isRest) {
            sb.append("        <rest/>\n")
        } else if (step != null) {
            sb.append("        <pitch>\n")
            sb.append("          <step>$step</step>\n")
            if (alter != null) sb.append("          <alter>$alter</alter>\n")
            sb.append("          <octave>$octave</octave>\n")
            sb.append("        </pitch>\n")
        }

        sb.append("        <duration>$dur</duration>\n")
        if (tieStart) sb.append("        <tie type=\"start\"/>\n")
        if (tieStop)  sb.append("        <tie type=\"stop\"/>\n")
        sb.append("        <voice>$voice</voice>\n")
        sb.append("        <type>$typeName</type>\n")
        if (dotted) sb.append("        <dot/>\n")

        val hasTie  = tieStart || tieStop
        val hasTech = str != null && !isRest
        if (hasTie || hasTech) {
            sb.append("        <notations>\n")
            if (tieStart) sb.append("          <tied type=\"start\"/>\n")
            if (tieStop)  sb.append("          <tied type=\"stop\"/>\n")
            if (hasTech) {
                sb.append("          <technical>\n")
                sb.append("            <string>$str</string>\n")
                sb.append("            <fret>$fret</fret>\n")
                sb.append("          </technical>\n")
            }
            sb.append("        </notations>\n")
        }

        sb.append("      </note>")
        return sb.toString()
    }
}
