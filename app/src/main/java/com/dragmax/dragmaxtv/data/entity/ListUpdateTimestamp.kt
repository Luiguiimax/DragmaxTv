package com.dragmax.dragmaxtv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "list_update_timestamp")
data class ListUpdateTimestamp(
    @PrimaryKey
    val id: Int = 1, // Solo un registro
    val fechaList: Long, // Timestamp de Firebase
    val lastUpdated: Long = System.currentTimeMillis()
)

