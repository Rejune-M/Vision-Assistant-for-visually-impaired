package com.example.visionassist.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "obstacles")
data class ObstacleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val label: String
)
