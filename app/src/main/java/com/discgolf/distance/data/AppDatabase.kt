package com.discgolf.distance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DiscThrow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun throwDao(): ThrowDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "disc_golf_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
