package com.discgolf.distance.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CourseDao {

    // ── Courses ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("SELECT * FROM courses ORDER BY name ASC, option ASC")
    fun getAllCourses(): LiveData<List<Course>>

    @Query("SELECT DISTINCT name FROM courses ORDER BY name ASC")
    suspend fun getAllCourseNames(): List<String>

    @Query("SELECT DISTINCT option FROM courses ORDER BY option ASC")
    suspend fun getAllCourseOptions(): List<String>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourseById(id: Long): Course?

    @Query("SELECT * FROM courses WHERE name = :name ORDER BY option ASC")
    suspend fun getCoursesByName(name: String): List<Course>

    /** Find or return null for an exact (name, option) pair. */
    @Query("SELECT * FROM courses WHERE name = :name AND option = :option LIMIT 1")
    suspend fun getCourse(name: String, option: String): Course?

    /** Courses that have any point within a bounding box. */
    @Query("""
        SELECT DISTINCT c.* FROM courses c
        JOIN course_points cp ON cp.courseId = c.id
        WHERE cp.lat BETWEEN :minLat AND :maxLat
          AND cp.lng BETWEEN :minLng AND :maxLng
        ORDER BY c.name ASC
    """)
    suspend fun getCoursesNearby(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<Course>

    // ── Course Points ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: CoursePoint): Long

    @Delete
    suspend fun deletePoint(point: CoursePoint)

    @Update
    suspend fun updatePoint(point: CoursePoint)

    @Query("SELECT * FROM course_points WHERE courseId = :courseId ORDER BY featureType ASC, holeNumber ASC")
    fun getPointsForCourse(courseId: Long): LiveData<List<CoursePoint>>

    @Query("SELECT * FROM course_points WHERE courseId = :courseId AND featureType = :type ORDER BY holeNumber ASC")
    suspend fun getPointsByType(courseId: Long, type: String): List<CoursePoint>

    /** Hole numbers already assigned for a given featureType within a course – used to grey out taken holes. */
    @Query("SELECT holeNumber FROM course_points WHERE courseId = :courseId AND featureType = :type")
    suspend fun getUsedHoles(courseId: Long, type: String): List<Int>

    /** All tees within a lat/lng bounding box – used for proximity detection. */
    @Query("""
        SELECT cp.* FROM course_points cp
        WHERE cp.featureType = 'TEE'
          AND cp.lat BETWEEN :minLat AND :maxLat
          AND cp.lng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getTeesInBounds(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<CoursePoint>

    /** Basket location for a specific hole – null if not yet saved. */
    @Query("SELECT * FROM course_points WHERE courseId = :courseId AND featureType = 'BASKET' AND holeNumber = :holeNumber LIMIT 1")
    suspend fun getBasketForHole(courseId: Long, holeNumber: Int): CoursePoint?

    /** Convenience join: fetch a tee's Course in one query. */
    @Query("SELECT * FROM courses WHERE id = (SELECT courseId FROM course_points WHERE id = :pointId)")
    suspend fun getCourseForPoint(pointId: Long): Course?
}
