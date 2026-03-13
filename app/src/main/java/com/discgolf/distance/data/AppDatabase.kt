package com.discgolf.distance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DiscThrow::class, Disc::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun throwDao(): ThrowDao
    abstract fun discDao(): DiscDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE disc_throws ADD COLUMN discId INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS discs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        manufacturer TEXT NOT NULL,
                        speed INTEGER NOT NULL,
                        glide INTEGER NOT NULL,
                        turn INTEGER NOT NULL,
                        fade INTEGER NOT NULL,
                        colorArgb INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Store the basket bearing at the time each throw was made
                db.execSQL("ALTER TABLE disc_throws ADD COLUMN targetBearing REAL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "disc_golf_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
