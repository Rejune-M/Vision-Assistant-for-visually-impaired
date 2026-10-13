package com.example.visionassist.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ObstacleDao {

    @Insert
    suspend fun insert(o: ObstacleEntity)

    @Query("SELECT * FROM obstacles ORDER BY id DESC")
    suspend fun getAll(): List<ObstacleEntity>

    @Query("SELECT * FROM obstacles ORDER BY id DESC LIMIT 1")
    suspend fun getLast(): ObstacleEntity?

    @Query("DELETE FROM obstacles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM obstacles")
    suspend fun deleteAll()
}
