package com.discgolf.distance.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named geographic point on a disc golf course.
 *
 * [featureType] drives future extensibility:
 *   "TEE"    – throwing pad / start position
 *   "BASKET" – target basket location
 *   "MANDO"  – mandatory obstacle
 *   "OB"     – out-of-bounds marker
 *   (more can be added without schema changes)
 *
 * [holeNumber] is 1–18 (or 0 for course-wide features like a parking lot).
 */
@Entity(
    tableName = "course_points",
    foreignKeys = [ForeignKey(
        entity = Course::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("courseId")]
)
data class CoursePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val featureType: String,   // TEE, BASKET, MANDO, OB, etc.
    val holeNumber: Int,       // 1–18  (0 = course-level feature)
    val lat: Double,
    val lng: Double,
    val notes: String = "",
    /** Compass bearing (degrees) from this tee toward the basket; null = not set. */
    val bearing: Double? = null
) {
    companion object {
        const val TYPE_TEE    = "TEE"
        const val TYPE_BASKET = "BASKET"
        const val TYPE_MANDO  = "MANDO"
        const val TYPE_OB     = "OB"
    }
}
