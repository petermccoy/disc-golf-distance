package com.discgolf.distance.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.*

@Entity(tableName = "disc_throws")
data class DiscThrow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: String,          // Groups throws in the same series
    val throwNumber: Int,           // Throw number within a session

    val startLat: Double,
    val startLng: Double,
    val startTimeMs: Long,

    val endLat: Double,
    val endLng: Double,
    val endTimeMs: Long,

    val distanceMeters: Double,     // Calculated Haversine distance
    val flightTimeMs: Long,         // endTimeMs - startTimeMs

    val discId: Long? = null        // FK to discs.id; null = no disc selected
) {
    companion object {
        /**
         * Haversine formula – returns distance in meters between two GPS coords.
         */
        fun calculateDistance(
            lat1: Double, lng1: Double,
            lat2: Double, lng2: Double
        ): Double {
            val R = 6_371_000.0  // Earth radius in metres
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dPhi = Math.toRadians(lat2 - lat1)
            val dLambda = Math.toRadians(lng2 - lng1)

            val a = sin(dPhi / 2).pow(2) +
                    cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return R * c
        }

        /**
         * Returns bearing in degrees (0=N, 90=E, 180=S, 270=W).
         */
        fun calculateBearing(
            lat1: Double, lng1: Double,
            lat2: Double, lng2: Double
        ): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dLambda = Math.toRadians(lng2 - lng1)
            val y = sin(dLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }

    val distanceFeet: Double get() = distanceMeters * 3.28084
    val distanceYards: Double get() = distanceMeters * 1.09361
}
