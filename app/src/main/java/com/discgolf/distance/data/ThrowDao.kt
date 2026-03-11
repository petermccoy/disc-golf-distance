package com.discgolf.distance.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ThrowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(discThrow: DiscThrow): Long

    @Delete
    suspend fun delete(discThrow: DiscThrow)

    @Query("DELETE FROM disc_throws WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM disc_throws WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT * FROM disc_throws ORDER BY startTimeMs DESC")
    fun getAllThrows(): LiveData<List<DiscThrow>>

    @Query("SELECT * FROM disc_throws ORDER BY startTimeMs DESC")
    suspend fun getAllThrowsSync(): List<DiscThrow>

    @Query("SELECT * FROM disc_throws WHERE sessionId = :sessionId ORDER BY throwNumber ASC")
    fun getThrowsBySession(sessionId: String): LiveData<List<DiscThrow>>

    @Query("SELECT * FROM disc_throws WHERE sessionId = :sessionId ORDER BY throwNumber ASC")
    suspend fun getThrowsBySessionSync(sessionId: String): List<DiscThrow>

    @Query("SELECT COUNT(*) FROM disc_throws WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    @Query("SELECT MAX(distanceMeters) FROM disc_throws")
    suspend fun getMaxDistance(): Double?
}
