package com.dragmax.dragmaxtv.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.dragmax.dragmaxtv.data.dao.LiveChannelDao
import com.dragmax.dragmaxtv.data.dao.ListUpdateTimestampDao
import com.dragmax.dragmaxtv.data.dao.M3UUrlDao
import com.dragmax.dragmaxtv.data.entity.LiveChannel
import com.dragmax.dragmaxtv.data.entity.ListUpdateTimestamp
import com.dragmax.dragmaxtv.data.entity.M3UUrl

@Database(
    entities = [M3UUrl::class, LiveChannel::class, ListUpdateTimestamp::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun m3uUrlDao(): M3UUrlDao
    abstract fun liveChannelDao(): LiveChannelDao
    abstract fun listUpdateTimestampDao(): ListUpdateTimestampDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dragmax_tv_database"
                )
                .fallbackToDestructiveMigration() // Para desarrollo, permite migración automática
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

