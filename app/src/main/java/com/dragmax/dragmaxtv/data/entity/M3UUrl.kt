package com.dragmax.dragmaxtv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "m3u_urls")
data class M3UUrl(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val fieldName: String, // urlm3u1, urlm3u2, etc.
    val downloadedAt: Long = System.currentTimeMillis(),
    val content: String? = null // Contenido descargado del M3U
)

