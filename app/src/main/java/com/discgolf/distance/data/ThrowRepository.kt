package com.discgolf.distance.data

import android.content.Context
import androidx.lifecycle.LiveData

class ThrowRepository(context: Context) {

    private val throwDao = AppDatabase.getDatabase(context).throwDao()

    val allThrows: LiveData<List<DiscThrow>> = throwDao.getAllThrows()

    suspend fun insert(discThrow: DiscThrow): Long = throwDao.insert(discThrow)

    suspend fun delete(discThrow: DiscThrow) = throwDao.delete(discThrow)

    suspend fun deleteById(id: Long) = throwDao.deleteById(id)

    suspend fun getAllThrowsSync(): List<DiscThrow> = throwDao.getAllThrowsSync()

    suspend fun countInSession(sessionId: String): Int = throwDao.countInSession(sessionId)

    suspend fun getMaxDistance(): Double? = throwDao.getMaxDistance()
}
