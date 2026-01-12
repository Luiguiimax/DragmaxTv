package com.dragmax.dragmaxtv.data.dao

import androidx.room.*
import com.dragmax.dragmaxtv.data.entity.ListUpdateTimestamp

@Dao
interface ListUpdateTimestampDao {
    @Query("SELECT * FROM list_update_timestamp WHERE id = 1 LIMIT 1")
    suspend fun getTimestamp(): ListUpdateTimestamp?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTimestamp(timestamp: ListUpdateTimestamp)
    
    @Query("DELETE FROM list_update_timestamp")
    suspend fun deleteAll()
}

