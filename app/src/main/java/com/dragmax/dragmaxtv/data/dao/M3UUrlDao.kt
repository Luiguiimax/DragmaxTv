package com.dragmax.dragmaxtv.data.dao

import androidx.room.*
import com.dragmax.dragmaxtv.data.entity.M3UUrl
import kotlinx.coroutines.flow.Flow

@Dao
interface M3UUrlDao {
    @Query("SELECT * FROM m3u_urls ORDER BY id ASC")
    fun getAllUrls(): Flow<List<M3UUrl>>
    
    @Query("SELECT * FROM m3u_urls WHERE fieldName = :fieldName LIMIT 1")
    suspend fun getUrlByFieldName(fieldName: String): M3UUrl?
    
    @Query("SELECT * FROM m3u_urls WHERE content IS NOT NULL ORDER BY id ASC")
    suspend fun getDownloadedUrls(): List<M3UUrl>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUrl(m3uUrl: M3UUrl): Long
    
    @Update
    suspend fun updateUrl(m3uUrl: M3UUrl)
    
    @Query("DELETE FROM m3u_urls")
    suspend fun deleteAll()
}

