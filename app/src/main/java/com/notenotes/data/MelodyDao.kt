package com.notenotes.data

import androidx.room.*
import com.notenotes.model.MelodyIdea
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for MelodyIdea entities.
 */
@Dao
interface MelodyDao {

    /** Active (non-deleted) ideas, most recent first. */
    @Query("SELECT * FROM melody_ideas WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllIdeas(): Flow<List<MelodyIdea>>

    /** Soft-deleted ideas (trash), most recently deleted first. */
    @Query("SELECT * FROM melody_ideas WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrashIdeas(): Flow<List<MelodyIdea>>

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

    /** Soft-delete: set deletedAt timestamp. */
    @Query("UPDATE melody_ideas SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    /** Restore from trash: clear deletedAt. */
    @Query("UPDATE melody_ideas SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    /** Permanently delete all items in trash older than the given epoch millis. */
    @Query("DELETE FROM melody_ideas WHERE deletedAt IS NOT NULL AND deletedAt < :olderThan")
    suspend fun purgeOldTrash(olderThan: Long)

    /** Permanently delete all trash items. */
    @Query("DELETE FROM melody_ideas WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("SELECT COUNT(*) FROM melody_ideas")
    suspend fun getCount(): Int

    @Query("SELECT * FROM melody_ideas WHERE deletedAt IS NULL AND title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchIdeas(query: String): Flow<List<MelodyIdea>>

    /** Update group for multiple ideas at once. */
    @Query("UPDATE melody_ideas SET groupId = :groupId, groupName = :groupName WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: String?, groupName: String?)

    /** Remove ideas from their group. */
    @Query("UPDATE melody_ideas SET groupId = NULL, groupName = NULL WHERE id IN (:ids)")
    suspend fun ungroup(ids: List<Long>)

    /** Rename an idea. */
    @Query("UPDATE melody_ideas SET title = :newTitle WHERE id = :id")
    suspend fun rename(id: Long, newTitle: String)
}
