package com.notenotes.data

import androidx.room.*
import com.notenotes.model.MelodyIdea
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for MelodyIdea entities.
 */
@Dao
interface MelodyDao {

    @Query("SELECT * FROM melody_ideas ORDER BY createdAt DESC")
    fun getAllIdeas(): Flow<List<MelodyIdea>>

    @Query("SELECT * FROM melody_ideas WHERE id = :id")
    suspend fun getIdeaById(id: Long): MelodyIdea?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(idea: MelodyIdea): Long

    @Update
    suspend fun update(idea: MelodyIdea)

    @Delete
    suspend fun delete(idea: MelodyIdea)

    @Query("DELETE FROM melody_ideas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM melody_ideas")
    suspend fun getCount(): Int

    @Query("SELECT * FROM melody_ideas WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchIdeas(query: String): Flow<List<MelodyIdea>>
}
