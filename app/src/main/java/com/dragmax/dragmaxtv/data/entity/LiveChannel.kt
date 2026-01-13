package com.dragmax.dragmaxtv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "live_channels")
data class LiveChannel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val m3uSourceId: Long, // ID de la fuente M3U
    val addedAt: Long = System.currentTimeMillis()
)






