package com.discgolf.distance.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discs")
data class Disc(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val manufacturer: String,

    val speed: Int,   // 1–14
    val glide: Int,   // 1–7
    val turn: Int,    // −5 to +1
    val fade: Int,    // 0–5

    val colorArgb: Int  // packed ARGB
) {
    /** e.g. "9 / 5 / -3 / 1" */
    val flightNumbers: String
        get() = "%d / %d / %+d / %d".format(speed, glide, turn, fade)
}
