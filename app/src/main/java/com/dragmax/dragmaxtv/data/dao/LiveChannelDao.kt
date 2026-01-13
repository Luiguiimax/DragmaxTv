package com.dragmax.dragmaxtv.data.dao

import androidx.room.*
import com.dragmax.dragmaxtv.data.entity.LiveChannel
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveChannelDao {
    @Query("SELECT * FROM live_channels ORDER BY id ASC")
    fun getAllChannels(): Flow<List<LiveChannel>>
    
    @Query("SELECT * FROM live_channels LIMIT 1")
    suspend fun getFirstChannel(): LiveChannel?
    
    @Query("SELECT * FROM live_channels WHERE m3uSourceId = :sourceId")
    suspend fun getChannelsBySource(sourceId: Long): List<LiveChannel>
    
    @Query("SELECT * FROM live_channels WHERE `group` = :groupName ORDER BY id ASC")
    suspend fun getChannelsByGroup(groupName: String): List<LiveChannel>
    
    @Query("SELECT * FROM live_channels WHERE `group` = :groupName AND name = :channelName LIMIT 1")
    suspend fun getChannelByGroupAndName(groupName: String, channelName: String): LiveChannel?
    
    @Query("SELECT * FROM live_channels WHERE `group` = :groupName ORDER BY id ASC LIMIT 1")
    suspend fun getFirstChannelByGroup(groupName: String): LiveChannel?

    @Query("SELECT * FROM live_channels WHERE name LIKE :channelName LIMIT 1")
    suspend fun getChannelByName(channelName: String): LiveChannel?

    @Query("SELECT * FROM live_channels WHERE name LIKE :channelName OR name LIKE :channelName2 LIMIT 1")
    suspend fun getChannelByNameVariations(channelName: String, channelName2: String): LiveChannel?

    @Query("SELECT * FROM live_channels ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomChannels(limit: Int): List<LiveChannel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: LiveChannel): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<LiveChannel>)
    
    @Query("DELETE FROM live_channels WHERE m3uSourceId = :sourceId")
    suspend fun deleteChannelsBySource(sourceId: Long)
    
    @Query("DELETE FROM live_channels")
    suspend fun deleteAll()
}

