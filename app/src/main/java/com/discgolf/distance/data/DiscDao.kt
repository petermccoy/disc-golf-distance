package com.discgolf.distance.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DiscDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(disc: Disc): Long

    @Delete
    suspend fun delete(disc: Disc)

    @Query("DELETE FROM discs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM discs ORDER BY name ASC")
    fun getAllDiscs(): LiveData<List<Disc>>

    @Update
    suspend fun update(disc: Disc)

    @Query("SELECT * FROM discs ORDER BY name ASC")
    suspend fun getAllDiscsSync(): List<Disc>
}
