package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {

    // --- Extraction History Queries ---
    @Query("SELECT * FROM extraction_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ExtractionHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ExtractionHistory): Long

    @Query("DELETE FROM extraction_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Long)

    @Query("DELETE FROM extraction_history")
    suspend fun clearHistory()

    // --- Saved Image Queries ---
    @Query("SELECT * FROM saved_images ORDER BY savedAt DESC")
    fun getAllSavedImages(): Flow<List<SavedImage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedImage(image: SavedImage)

    @Query("DELETE FROM saved_images WHERE url = :url")
    suspend fun deleteSavedImageByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_images WHERE url = :url LIMIT 1)")
    fun isImageSaved(url: String): Flow<Boolean>

    @Query("SELECT * FROM saved_images WHERE url = :url LIMIT 1")
    suspend fun getSavedImageByUrl(url: String): SavedImage?
}
