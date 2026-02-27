package com.notenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.notenotes.model.MelodyIdea

/**
 * Room database for NoteNotes app.
 * Stores metadata about recorded melody ideas.
 * Audio/MIDI/XML files are stored on the file system.
 */
@Database(entities = [MelodyIdea::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun melodyDao(): MelodyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notenotes_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
