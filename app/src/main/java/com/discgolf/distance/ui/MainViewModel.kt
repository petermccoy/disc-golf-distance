package com.discgolf.distance.ui

import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.discgolf.distance.data.Course
import com.discgolf.distance.data.CoursePoint
import com.discgolf.distance.data.CourseRepository
import com.discgolf.distance.data.Disc
import com.discgolf.distance.data.DiscRepository
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.data.ThrowRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val PREFS_NAME = "disc_golf_prefs"
private const val PREF_FIXED_START = "fixed_start_enabled"
private const val PREF_LAST_COURSE_OPTION = "last_course_option"

/** Metres – tee must be within this distance to be auto-selected. */
private const val TEE_PROXIMITY_METERS = 30.0

enum class AppState {
    IDLE,               // No session active – show Start button only
    AWAITING_END,       // Start marked – show End button
    THROW_RECORDED      // End marked – show post-throw dialog options
}

data class LocationSnapshot(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
    val accuracyM: Float
)

/** Pairs a tee [CoursePoint] with its parent [Course] for display. */
data class TeeInfo(val point: CoursePoint, val course: Course) {
    val label: String get() = "${course.name} · ${course.option} · Hole ${point.holeNumber}"
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ThrowRepository(application)
    private val discRepository = DiscRepository(application)
    private val courseRepository = CourseRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val allThrows: LiveData<List<DiscThrow>> = repository.allThrows
    val allDiscs: LiveData<List<Disc>> = discRepository.allDiscs
    val allCourses: LiveData<List<Course>> = courseRepository.allCourses

    private val _appState = MutableLiveData(AppState.IDLE)
    val appState: LiveData<AppState> get() = _appState

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _lastThrow = MutableLiveData<DiscThrow?>()
    val lastThrow: LiveData<DiscThrow?> get() = _lastThrow

    private val _lastThrowIsPersonalBest = MutableLiveData(false)
    val lastThrowIsPersonalBest: LiveData<Boolean> get() = _lastThrowIsPersonalBest

    private val _currentAccuracy = MutableLiveData<Float>()
    val currentAccuracy: LiveData<Float> get() = _currentAccuracy

    // Aim direction – compass bearing toward the basket for this session
    private val _sessionTargetBearing = MutableLiveData<Float?>(null)
    val sessionTargetBearing: LiveData<Float?> get() = _sessionTargetBearing

    // ── Fixed-start / tee state ───────────────────────────────────────────────

    /** Whether the user has turned on fixed-start (tee) mode. Persisted. */
    private val _fixedStartEnabled = MutableLiveData(prefs.getBoolean(PREF_FIXED_START, false))
    val fixedStartEnabled: LiveData<Boolean> get() = _fixedStartEnabled

    /**
     * The tee nearest to the current GPS position, or null when none is within
     * [TEE_PROXIMITY_METERS]. Updated each time a location arrives.
     */
    private val _nearbyTee = MutableLiveData<TeeInfo?>(null)
    val nearbyTee: LiveData<TeeInfo?> get() = _nearbyTee

    /**
     * The tee that will be used as the start point when fixedStartEnabled is true.
     * Can be set automatically (nearby detection) or by explicit user selection.
     */
    private val _selectedTee = MutableLiveData<TeeInfo?>(null)
    val selectedTee: LiveData<TeeInfo?> get() = _selectedTee

    // Session state
    var currentSessionId: String = newSessionId()
        private set
    private var throwNumberInSession: Int = 0
    private var startSnapshot: LocationSnapshot? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateCurrentAccuracy(acc: Float) {
        _currentAccuracy.value = acc
    }

    fun setTargetBearing(bearing: Float) {
        _sessionTargetBearing.value = bearing
    }

    fun clearTargetBearing() {
        _sessionTargetBearing.value = null
    }

    fun setFixedStartEnabled(enabled: Boolean) {
        _fixedStartEnabled.value = enabled
        prefs.edit().putBoolean(PREF_FIXED_START, enabled).apply()
        if (!enabled) _selectedTee.value = null
    }

    fun selectTee(tee: TeeInfo) {
        _selectedTee.value = tee
    }

    fun clearSelectedTee() {
        _selectedTee.value = null
    }

    /** Called on every location update to check for a nearby tee. */
    fun checkNearbyTees(lat: Double, lng: Double) {
        viewModelScope.launch {
            val tee = findNearestTee(lat, lng)
            _nearbyTee.postValue(tee)

            // Auto-select nearest tee when fixed-start is on and nothing is manually chosen
            if (_fixedStartEnabled.value == true && _selectedTee.value == null && tee != null) {
                _selectedTee.postValue(tee)
            }
        }
    }

    private suspend fun findNearestTee(lat: Double, lng: Double): TeeInfo? {
        // ~30 m bounding box (≈ 0.00027 degrees lat/lng at equator, safe approximation)
        val delta = 0.0003
        val tees = courseRepository.getTeesInBounds(
            minLat = lat - delta, maxLat = lat + delta,
            minLng = lng - delta, maxLng = lng + delta
        )
        if (tees.isEmpty()) return null

        var nearest: CoursePoint? = null
        var nearestDist = Double.MAX_VALUE
        for (t in tees) {
            val d = DiscThrow.calculateDistance(lat, lng, t.lat, t.lng)
            if (d < nearestDist) {
                nearestDist = d
                nearest = t
            }
        }
        if (nearest == null || nearestDist > TEE_PROXIMITY_METERS) return null

        val course = courseRepository.getCourseById(nearest.courseId) ?: return null
        return TeeInfo(nearest, course)
    }

    fun recordStart(location: Location) {
        val snap = if (_fixedStartEnabled.value == true && _selectedTee.value != null) {
            val tee = _selectedTee.value!!.point
            LocationSnapshot(
                lat = tee.lat,
                lng = tee.lng,
                timeMs = System.currentTimeMillis(),
                accuracyM = 0f   // fixed point – perfect accuracy
            )
        } else {
            LocationSnapshot(
                lat = location.latitude,
                lng = location.longitude,
                timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                accuracyM = location.accuracy
            )
        }
        startSnapshot = snap
        _appState.value = AppState.AWAITING_END

        val accuracyNote = if (snap.accuracyM == 0f) "fixed tee point"
                           else "accuracy ±${snap.accuracyM.toInt()} m"
        _statusMessage.value = "Start marked – $accuracyNote\nMove to disc landing spot, then tap End Location."
    }

    fun recordEnd(location: Location, discId: Long? = null) {
        val start = startSnapshot ?: return
        val endTime = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()

        val distanceMeters = DiscThrow.calculateDistance(
            start.lat, start.lng,
            location.latitude, location.longitude
        )

        throwNumberInSession++
        val discThrow = DiscThrow(
            sessionId = currentSessionId,
            throwNumber = throwNumberInSession,
            startLat = start.lat,
            startLng = start.lng,
            startTimeMs = start.timeMs,
            endLat = location.latitude,
            endLng = location.longitude,
            endTimeMs = endTime,
            distanceMeters = distanceMeters,
            flightTimeMs = endTime - start.timeMs,
            discId = discId,
            targetBearing = _sessionTargetBearing.value?.toDouble()
        )

        viewModelScope.launch {
            val previousBest = repository.getMaxDistance() ?: 0.0
            val isPB = distanceMeters > previousBest
            repository.insert(discThrow)
            _lastThrowIsPersonalBest.postValue(isPB)
            _lastThrow.postValue(discThrow)
            _appState.postValue(AppState.THROW_RECORDED)
        }
    }

    /** Keep same session, keep same start point → record another disc from same spot */
    fun continueNextDisc() {
        _appState.value = AppState.AWAITING_END
        _statusMessage.value = "Same start kept – move to next disc and tap End Location."
    }

    /** Keep same session, but pick a new start point */
    fun startNewLocation() {
        startSnapshot = null
        _appState.value = AppState.IDLE
        _statusMessage.value = "Ready for next throw – tap Start Location."
    }

    /** Start a brand-new session */
    fun startNewSession() {
        currentSessionId = newSessionId()
        throwNumberInSession = 0
        startSnapshot = null
        _lastThrow.value = null
        _sessionTargetBearing.value = null
        _appState.value = AppState.IDLE
        _statusMessage.value = "New session started – tap Start Location."
    }

    fun deleteThrow(discThrow: DiscThrow) {
        viewModelScope.launch { repository.delete(discThrow) }
    }

    fun insertDisc(disc: Disc) {
        viewModelScope.launch { discRepository.insert(disc) }
    }

    fun deleteDisc(disc: Disc) {
        viewModelScope.launch { discRepository.delete(disc) }
    }

    fun updateDisc(disc: Disc) {
        viewModelScope.launch { discRepository.update(disc) }
    }

    fun getStartSnapshot(): LocationSnapshot? = startSnapshot

    // ── Course / tee saving ───────────────────────────────────────────────────

    fun getLastCourseOption(): String = prefs.getString(PREF_LAST_COURSE_OPTION, "Short") ?: "Short"

    /**
     * Saves the given GPS coordinates as a tee point.
     * Creates the Course row if it doesn't exist yet.
     * Returns the saved [TeeInfo] or null if validation failed.
     */
    fun saveTeePoint(
        lat: Double, lng: Double,
        courseName: String, courseOption: String, holeNumber: Int,
        onResult: (TeeInfo?) -> Unit
    ) {
        viewModelScope.launch {
            prefs.edit().putString(PREF_LAST_COURSE_OPTION, courseOption).apply()
            val course = courseRepository.getOrCreateCourse(courseName.trim(), courseOption.trim())
            val point = CoursePoint(
                courseId = course.id,
                featureType = CoursePoint.TYPE_TEE,
                holeNumber = holeNumber,
                lat = lat,
                lng = lng
            )
            val id = courseRepository.insertPoint(point)
            onResult(TeeInfo(point.copy(id = id), course))
        }
    }

    /**
     * Returns hole numbers 1–18 that are already saved as tees for the given course.
     */
    fun getUsedTeeHoles(courseId: Long, onResult: (List<Int>) -> Unit) {
        viewModelScope.launch {
            onResult(courseRepository.getUsedHoles(courseId, CoursePoint.TYPE_TEE))
        }
    }

    /**
     * Returns all course names and course options for autocomplete dropdowns.
     */
    fun getCourseNamesAndOptions(onResult: (names: List<String>, options: List<String>) -> Unit) {
        viewModelScope.launch {
            val names = courseRepository.getAllCourseNames()
            val options = courseRepository.getAllCourseOptions()
            onResult(names, options)
        }
    }

    /**
     * Returns courses that have at least one tee near the given location (within ~500 m).
     */
    fun getCoursesNearLocation(lat: Double, lng: Double, onResult: (List<Course>) -> Unit) {
        viewModelScope.launch {
            val delta = 0.005  // ~500 m
            val courses = courseRepository.getCoursesNearby(
                minLat = lat - delta, maxLat = lat + delta,
                minLng = lng - delta, maxLng = lng + delta
            )
            onResult(courses)
        }
    }

    /**
     * Finds the [TeeInfo] for a specific course + hole number (used by the tee selector).
     */
    fun findTeePoint(courseId: Long, holeNumber: Int, onResult: (TeeInfo?) -> Unit) {
        viewModelScope.launch {
            val course = courseRepository.getCourseById(courseId)
            if (course == null) { onResult(null); return@launch }
            val point = courseRepository.getPointsByType(courseId, CoursePoint.TYPE_TEE)
                .firstOrNull { it.holeNumber == holeNumber }
            onResult(if (point != null) TeeInfo(point, course) else null)
        }
    }

    /**
     * Looks up the database ID for an existing (name, option) course pair without creating it.
     * Returns null if the course doesn't exist yet.
     */
    fun getOrFindCourseId(name: String, option: String, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            val course = courseRepository.getCoursesByName(name)
                .firstOrNull { it.option.equals(option, ignoreCase = true) }
            onResult(course?.id)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun newSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return sdf.format(Date())
    }
}
