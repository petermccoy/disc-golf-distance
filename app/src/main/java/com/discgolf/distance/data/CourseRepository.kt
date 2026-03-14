package com.discgolf.distance.data

import android.app.Application
import androidx.lifecycle.LiveData

class CourseRepository(application: Application) {

    private val dao = AppDatabase.getDatabase(application).courseDao()

    val allCourses: LiveData<List<Course>> = dao.getAllCourses()

    // ── Courses ───────────────────────────────────────────────────────────────

    suspend fun insertCourse(course: Course): Long = dao.insertCourse(course)
    suspend fun deleteCourse(course: Course) = dao.deleteCourse(course)
    suspend fun getAllCourseNames(): List<String> = dao.getAllCourseNames()
    suspend fun getAllCourseOptions(): List<String> = dao.getAllCourseOptions()
    suspend fun getCourseById(id: Long): Course? = dao.getCourseById(id)
    suspend fun getCoursesByName(name: String): List<Course> = dao.getCoursesByName(name)

    /**
     * Returns the existing course for (name, option) or creates it if new.
     */
    suspend fun getOrCreateCourse(name: String, option: String): Course {
        val existing = dao.getCourse(name, option)
        if (existing != null) return existing
        val id = dao.insertCourse(Course(name = name, option = option))
        return Course(id = id, name = name, option = option)
    }

    suspend fun getCoursesNearby(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<Course> = dao.getCoursesNearby(minLat, maxLat, minLng, maxLng)

    // ── Course Points ─────────────────────────────────────────────────────────

    suspend fun insertPoint(point: CoursePoint): Long = dao.insertPoint(point)
    suspend fun deletePoint(point: CoursePoint) = dao.deletePoint(point)
    suspend fun updatePoint(point: CoursePoint) = dao.updatePoint(point)
    fun getPointsForCourse(courseId: Long): LiveData<List<CoursePoint>> = dao.getPointsForCourse(courseId)
    suspend fun getUsedHoles(courseId: Long, featureType: String): List<Int> = dao.getUsedHoles(courseId, featureType)
    suspend fun getPointsByType(courseId: Long, featureType: String): List<CoursePoint> = dao.getPointsByType(courseId, featureType)
    suspend fun getCourseForPoint(pointId: Long): Course? = dao.getCourseForPoint(pointId)

    suspend fun getTeesInBounds(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<CoursePoint> = dao.getTeesInBounds(minLat, maxLat, minLng, maxLng)
}
