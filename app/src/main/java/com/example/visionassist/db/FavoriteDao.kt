package com.example.visionassist.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fav: FavoriteEntity): Long

    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()

    @Query("SELECT * FROM favorites WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): FavoriteEntity?
}
