package com.notenotes.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "melody_ideas")
data class MelodyIdea(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,              // epoch millis
    val audioFilePath: String,        // path to WAV voice memo
    val midiFilePath: String? = null,
    val musicXmlFilePath: String? = null,
    val instrument: String = "piano",
    val tempoBpm: Int = 120,
    val keySignature: String? = null,  // e.g. "G major"
    val timeSignature: String? = null, // e.g. "3/4"
    val notes: String? = null,         // JSON-serialized note list
    val deletedAt: Long? = null,       // epoch millis when soft-deleted (null = active)
    val groupId: String? = null,       // UUID grouping key (null = ungrouped)
    val groupName: String? = null,     // user-visible group name
    val lastOpenedAt: Long? = null,    // epoch millis when last opened for preview
    val lastExportPath: String? = null // file path/name of user's last manual export
)
