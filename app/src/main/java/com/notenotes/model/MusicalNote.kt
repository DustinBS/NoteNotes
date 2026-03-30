package com.notenotes.model

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.notenotes.util.GuitarUtils
import java.lang.reflect.Type

@JsonAdapter(MusicalNoteAdapter::class)
data class MusicalNote(
    val pitches: List<Int>,      // 0-127 MIDI pitches. Single notes have size=1. Chords > 1.
    val durationTicks: Int,      // in divisions (e.g., quarter = 1 division)
    val type: String,            // "whole", "half", "quarter", "eighth", "16th"
    val dotted: Boolean = false,
    val isRest: Boolean = false,
    val tiedToNext: Boolean = false,
    val velocity: Int = 80,      // 0-127, default mezzo-forte
    val tabPositions: List<Pair<Int, Int>> = emptyList(), // parallel to pitches: (guitarString, guitarFret) per note
    val chordName: String? = null, // e.g., "Am", "G7" — null for single notes
    // Guitar tablature — hand-crafted ground truth (only set for manually added notes)
    val isManual: Boolean = false,  // true if manually annotated (guaranteed correct)
    val timePositionMs: Float? = null // precise time position in audio (ms), ground truth for manual notes
) {
    /** True if this note is part of a chord (multiple simultaneous pitches). */
    val isChord: Boolean get() = pitches.size > 1

    /** All MIDI pitches in this note/chord, sorted ascending. */
    val allPitches: List<Int> get() = pitches.sorted()

    /** True if this note has guitar tablature information. */
    val hasTab: Boolean get() = tabPositions.isNotEmpty()

    /** True if this chord has multiple notes assigned to the same guitar string. */
    val hasDuplicateStrings: Boolean get() {
        if (!isChord) return false
        val allStrings = tabPositions.map { it.first }
        return allStrings.size != allStrings.toSet().size
    }

    /** Null-safe accessor for tabPositions (Gson can set it to null if not using adapter). */
    val safeTabPositions: List<Pair<Int, Int>> get() = tabPositions

    /**
     * Canonical view of `tabPositions` as human 1-based string numbers (1..6).
     * Assumes stored `tabPositions` use human numbering; out-of-range values are coerced.
     */
    val safeTabPositionsAsHuman: List<Pair<Int, Int>>
        get() {
            val tps = safeTabPositions
            return tps.map { (s, f) ->
                val human = s.coerceIn(1, GuitarUtils.STRINGS.size)
                Pair(human, f)
            }
        }

    // Note: `safeTabPositionsAsIndex` removed — callers should convert from
    // `safeTabPositionsAsHuman` via `GuitarUtils.humanToIndex(...)` when a
    // 0-based index is required for UI rendering.

    /**
     * Sanitize this note after Gson deserialization.
     */
    fun sanitized(): MusicalNote {
        val p = pitches
        val tps = tabPositions

        // Ensure tabPositions has entries for all pitches
        val fixedTps = if (tps.size < p.size) {
            p.mapIndexed { i, pitch ->
                // Prefer explicit canonical human string numbers as fallback
                tps.getOrNull(i) ?: (if (!isRest) GuitarUtils.fromMidi(pitch) ?: Pair(GuitarUtils.STRINGS.size, 0) else Pair(GuitarUtils.STRINGS.size, 0))
            }
        } else {
            tps
        }

        return copy(
            pitches = p,
            tabPositions = fixedTps
        )
    }

    companion object {
        /** Sanitize a list of notes after Gson deserialization. */
        fun sanitizeList(notes: List<MusicalNote>): List<MusicalNote> =
            notes.map { it.sanitized() }
    }
}

class MusicalNoteAdapter : JsonDeserializer<MusicalNote>, JsonSerializer<MusicalNote> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): MusicalNote {
        val obj = json.asJsonObject
        
        val pitches = mutableListOf<Int>()
        if (obj.has("midiPitch")) {
            pitches.add(obj.get("midiPitch").asInt)
        }
        if (obj.has("chordPitches") && obj.get("chordPitches").isJsonArray) {
            obj.get("chordPitches").asJsonArray.forEach { pitches.add(it.asInt) }
        }
        if (obj.has("pitches") && obj.get("pitches").isJsonArray) {
            obj.get("pitches").asJsonArray.forEach { pitches.add(it.asInt) }
        }

        val tabPositions = mutableListOf<Pair<Int, Int>>()
        if (obj.has("guitarString") && !obj.get("guitarString").isJsonNull && obj.has("guitarFret") && !obj.get("guitarFret").isJsonNull) {
            val rawString = obj.get("guitarString").asInt
            val fret = obj.get("guitarFret").asInt
            // Skip empty sentinel (0,0) if present; otherwise accept canonical human 1..N values.
            if (!(rawString == 0 && fret == 0)) {
                val human = rawString.coerceIn(1, GuitarUtils.STRINGS.size)
                tabPositions.add(Pair(human, fret))
            }
        }
        if (obj.has("chordStringFrets") && obj.get("chordStringFrets").isJsonArray) {
            val csfArray = obj.get("chordStringFrets").asJsonArray
            csfArray.forEach {
                try {
                    val pairObj = it.asJsonObject
                    if (pairObj.has("first") && pairObj.has("second")) {
                        val raw = pairObj.get("first").asInt
                        val fret = pairObj.get("second").asInt
                        val human = raw.coerceIn(1, GuitarUtils.STRINGS.size)
                        tabPositions.add(Pair(human, fret))
                    }
                } catch (e: Exception) {}
            }
        }
        if (obj.has("tabPositions") && obj.get("tabPositions").isJsonArray) {
            val tpArray = obj.get("tabPositions").asJsonArray
            tpArray.forEach { 
                try {
                    val pairObj = it.asJsonObject
                    if (pairObj.has("first") && pairObj.has("second")) {
                        val raw = pairObj.get("first").asInt
                        val fret = pairObj.get("second").asInt
                        val human = raw.coerceIn(1, GuitarUtils.STRINGS.size)
                        tabPositions.add(Pair(human, fret))
                    }
                } catch (e: Exception) {}
            }
        }

        val durationTicks = if (obj.has("durationTicks")) obj.get("durationTicks").asInt else 1
        val type = if (obj.has("type") && !obj.get("type").isJsonNull) obj.get("type").asString else "quarter"
        val dotted = if (obj.has("dotted")) obj.get("dotted").asBoolean else false
        val isRest = if (obj.has("isRest")) obj.get("isRest").asBoolean else false
        val tiedToNext = if (obj.has("tiedToNext")) obj.get("tiedToNext").asBoolean else false
        val velocity = if (obj.has("velocity")) obj.get("velocity").asInt else 80
        val chordName = if (obj.has("chordName") && !obj.get("chordName").isJsonNull) obj.get("chordName").asString else null
        val isManual = if (obj.has("isManual")) obj.get("isManual").asBoolean else false
        val timePositionMs = if (obj.has("timePositionMs") && !obj.get("timePositionMs").isJsonNull) obj.get("timePositionMs").asFloat else null

        return MusicalNote(
            pitches = pitches,
            durationTicks = durationTicks,
            type = type,
            dotted = dotted,
            isRest = isRest,
            tiedToNext = tiedToNext,
            velocity = velocity,
            tabPositions = tabPositions,
            chordName = chordName,
            isManual = isManual,
            timePositionMs = timePositionMs
        )
    }

    override fun serialize(src: MusicalNote, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        
        val pitchesArray = JsonArray()
        src.pitches.forEach { pitchesArray.add(it) }
        obj.add("pitches", pitchesArray)

        obj.addProperty("durationTicks", src.durationTicks)
        obj.addProperty("type", src.type)
        obj.addProperty("dotted", src.dotted)
        obj.addProperty("isRest", src.isRest)
        obj.addProperty("tiedToNext", src.tiedToNext)
        obj.addProperty("velocity", src.velocity)

        val tpArray = JsonArray()
        src.tabPositions.forEach {
            val p = JsonObject()
            p.addProperty("first", it.first)
            p.addProperty("second", it.second)
            tpArray.add(p)
        }
        obj.add("tabPositions", tpArray)

        if (src.chordName != null) obj.addProperty("chordName", src.chordName)
        obj.addProperty("isManual", src.isManual)
        if (src.timePositionMs != null) obj.addProperty("timePositionMs", src.timePositionMs)

        return obj
    }
}

