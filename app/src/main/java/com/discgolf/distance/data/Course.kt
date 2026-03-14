package com.discgolf.distance.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A disc golf course + layout combination.
 * e.g. name="Hawk Hollow", option="Long" or "Short" or "Pro"
 *
 * Each distinct (name, option) pair is its own row so courses can share
 * a name but have different hole configurations.
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val option: String  // "Short", "Long", "Pro", "Recreational", etc.
)
