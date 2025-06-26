package com.example.sentrytestapp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ItemDao {
    @Insert
    suspend fun insert(item: Item)

    @Query("SELECT * FROM Item")
    suspend fun getAll(): List<Item>

    @Query("DELETE FROM Item")
    suspend fun deleteAll()

    @Query("SELECT * FROM Item WHERE name LIKE '%' || :query || '%'")
    suspend fun searchByName(query: String): List<Item>

    @Query("SELECT COUNT(*) FROM Item")
    suspend fun count(): Int
}
