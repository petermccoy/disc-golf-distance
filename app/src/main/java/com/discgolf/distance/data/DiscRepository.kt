package com.discgolf.distance.data

import android.content.Context
import androidx.lifecycle.LiveData

class DiscRepository(context: Context) {

    private val dao = AppDatabase.getDatabase(context).discDao()

    val allDiscs: LiveData<List<Disc>> = dao.getAllDiscs()

    suspend fun insert(disc: Disc): Long = dao.insert(disc)

    suspend fun delete(disc: Disc) = dao.delete(disc)

    suspend fun update(disc: Disc) = dao.update(disc)
}
