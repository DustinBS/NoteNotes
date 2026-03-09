package com.notenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notenotes.model.MelodyIdea

/** Migration from v1 → v2: add soft-delete and grouping columns. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE melody_ideas ADD COLUMN deletedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE melody_ideas ADD COLUMN groupId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE melody_ideas ADD COLUMN groupName TEXT DEFAULT NULL")
    }
}

/** Migration from v2 → v3: add lastOpenedAt column for recently-opened sort. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE melody_ideas ADD COLUMN lastOpenedAt INTEGER DEFAULT NULL")
    }
}

/**
 * Room database for NoteNotes app.
 * Stores metadata about recorded melody ideas.
 * Audio/MIDI/XML files are stored on the file system.
 */
@Database(entities = [MelodyIdea::class], version = 3, exportSchema = false)
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
