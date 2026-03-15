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
import kotlin.math.*

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
    val accuracyM: Float,
    val altitudeM: Double? = null
)

/** Pairs a tee [CoursePoint] with its parent [Course] for display. */
data class TeeInfo(val point: CoursePoint, val course: Course) {
    val label: String get() = "${course.name} · ${course.option} · Hole ${point.holeNumber}"
}

/** Live data shown in the rangefinder card. */
data class RangefinderInfo(
    val distanceFt: Double,
    val distanceM: Double,
    val bearing: Double,
    /** Positive = basket is uphill; null = altitude unavailable for one or both points. */
    val elevationChangeM: Double?
)

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

    private val _fixedStartEnabled = MutableLiveData(prefs.getBoolean(PREF_FIXED_START, false))
    val fixedStartEnabled: LiveData<Boolean> get() = _fixedStartEnabled

    private val _nearbyTee = MutableLiveData<TeeInfo?>(null)
    val nearbyTee: LiveData<TeeInfo?> get() = _nearbyTee

    private val _selectedTee = MutableLiveData<TeeInfo?>(null)
    val selectedTee: LiveData<TeeInfo?> get() = _selectedTee

    // ── Basket / rangefinder state ────────────────────────────────────────────

    /** Basket saved for the currently selected tee's hole; null if none exists yet. */
    private val _activeBasket = MutableLiveData<CoursePoint?>(null)
    val activeBasket: LiveData<CoursePoint?> get() = _activeBasket

    /** Live range/bearing/elevation to the active basket; null when no basket is known. */
    private val _rangefinderInfo = MutableLiveData<RangefinderInfo?>(null)
    val rangefinderInfo: LiveData<RangefinderInfo?> get() = _rangefinderInfo

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
        if (!enabled) {
            _selectedTee.value = null
            _activeBasket.value = null
            _rangefinderInfo.value = null
        }
    }

    fun selectTee(tee: TeeInfo) {
        _selectedTee.value = tee
        updateActiveBasket(tee.point.courseId, tee.point.holeNumber)
    }

    fun clearSelectedTee() {
        _selectedTee.value = null
        _activeBasket.value = null
        _rangefinderInfo.value = null
    }

    /** Called on every location update – checks proximity and refreshes rangefinder. */
    fun checkNearbyTees(lat: Double, lng: Double, altitudeM: Double?) {
        viewModelScope.launch {
            val tee = findNearestTee(lat, lng)
            _nearbyTee.postValue(tee)

            // Auto-select nearest tee when nothing is manually chosen
            if (_fixedStartEnabled.value == true && _selectedTee.value == null && tee != null) {
                _selectedTee.postValue(tee)
                updateActiveBasket(tee.point.courseId, tee.point.holeNumber)
            }

            // Refresh rangefinder with current position
            refreshRangefinder(lat, lng, altitudeM)
        }
    }

    /** Recomputes the live rangefinder display from the given position to the active basket. */
    fun updateRangefinder(lat: Double, lng: Double, altitudeM: Double?) {
        viewModelScope.launch { refreshRangefinder(lat, lng, altitudeM) }
    }

    private suspend fun refreshRangefinder(lat: Double, lng: Double, altitudeM: Double?) {
        val basket = _activeBasket.value ?: run {
            _rangefinderInfo.postValue(null)
            return
        }
        val distM = DiscThrow.calculateDistance(lat, lng, basket.lat, basket.lng)
        val bearing = computeBearing(lat, lng, basket.lat, basket.lng)
        val elevChange = if (altitudeM != null && basket.altitudeM != null)
            basket.altitudeM - altitudeM else null

        _rangefinderInfo.postValue(
            RangefinderInfo(
                distanceFt = distM * 3.28084,
                distanceM = distM,
                bearing = bearing,
                elevationChangeM = elevChange
            )
        )
    }

    private fun updateActiveBasket(courseId: Long, holeNumber: Int) {
        viewModelScope.launch {
            val basket = courseRepository.getBasketForHole(courseId, holeNumber)
            _activeBasket.postValue(basket)
        }
    }

    private suspend fun findNearestTee(lat: Double, lng: Double): TeeInfo? {
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
            if (d < nearestDist) { nearestDist = d; nearest = t }
        }
        if (nearest == null || nearestDist > TEE_PROXIMITY_METERS) return null
        val course = courseRepository.getCourseById(nearest.courseId) ?: return null
        return TeeInfo(nearest, course)
    }

    fun recordStart(location: Location) {
        val snap = if (_fixedStartEnabled.value == true && _selectedTee.value != null) {
            val tee = _selectedTee.value!!.point
            LocationSnapshot(
                lat = tee.lat, lng = tee.lng,
                timeMs = System.currentTimeMillis(),
                accuracyM = 0f,
                altitudeM = tee.altitudeM
            )
        } else {
            LocationSnapshot(
                lat = location.latitude, lng = location.longitude,
                timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                accuracyM = location.accuracy,
                altitudeM = if (location.hasAltitude()) location.altitude else null
            )
        }
        startSnapshot = snap

        // Auto-aim: basket position takes priority over stored tee bearing
        val basket = _activeBasket.value
        if (basket != null) {
            setTargetBearing(computeBearing(snap.lat, snap.lng, basket.lat, basket.lng).toFloat())
        } else if (_fixedStartEnabled.value == true) {
            _selectedTee.value?.point?.bearing?.let { setTargetBearing(it.toFloat()) }
        }

        _appState.value = AppState.AWAITING_END
        val accuracyNote = if (snap.accuracyM == 0f) "fixed tee point"
                           else "accuracy ±${snap.accuracyM.toInt()} m"
        _statusMessage.value = "Start marked – $accuracyNote\nMove to disc landing spot, then tap End Location."
    }

    fun recordEnd(location: Location, discId: Long? = null) {
        val start = startSnapshot ?: return
        val endTime = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()

        val distanceMeters = DiscThrow.calculateDistance(
            start.lat, start.lng, location.latitude, location.longitude
        )
        throwNumberInSession++
        val discThrow = DiscThrow(
            sessionId = currentSessionId,
            throwNumber = throwNumberInSession,
            startLat = start.lat, startLng = start.lng, startTimeMs = start.timeMs,
            endLat = location.latitude, endLng = location.longitude, endTimeMs = endTime,
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

    fun continueNextDisc() {
        _appState.value = AppState.AWAITING_END
        _statusMessage.value = "Same start kept – move to next disc and tap End Location."
    }

    fun startNewLocation() {
        startSnapshot = null
        _appState.value = AppState.IDLE
        _statusMessage.value = "Ready for next throw – tap Start Location."
    }

    /**
     * Sets the end position of the last throw as the new start point.
     * Used for layup / re-throw scenarios – rangefinder immediately updates
     * from the disc's resting position to the basket.
     */
    fun useEndAsNewStart() {
        val last = _lastThrow.value ?: return
        val snap = LocationSnapshot(
            lat = last.endLat, lng = last.endLng,
            timeMs = System.currentTimeMillis(),
            accuracyM = 0f  // was a recorded GPS point – treat as precise
        )
        startSnapshot = snap

        // Re-aim to basket from the new (disc) position
        val basket = _activeBasket.value
        if (basket != null) {
            setTargetBearing(computeBearing(snap.lat, snap.lng, basket.lat, basket.lng).toFloat())
        }

        _appState.value = AppState.AWAITING_END
        _statusMessage.value = "Start set to disc position – tap End Location for next throw."
    }

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

    fun insertDisc(disc: Disc) { viewModelScope.launch { discRepository.insert(disc) } }
    fun deleteDisc(disc: Disc) { viewModelScope.launch { discRepository.delete(disc) } }
    fun updateDisc(disc: Disc) { viewModelScope.launch { discRepository.update(disc) } }

    fun getStartSnapshot(): LocationSnapshot? = startSnapshot

    // ── Basket saving ─────────────────────────────────────────────────────────

    /**
     * Saves [lat]/[lng]/[altitudeM] as the basket for the currently selected tee's hole.
     * No-op if no tee is selected.
     */
    fun saveBasketPoint(lat: Double, lng: Double, altitudeM: Double?, onResult: (Boolean) -> Unit) {
        val tee = _selectedTee.value?.point ?: return
        viewModelScope.launch {
            val point = CoursePoint(
                courseId = tee.courseId,
                featureType = CoursePoint.TYPE_BASKET,
                holeNumber = tee.holeNumber,
                lat = lat, lng = lng, altitudeM = altitudeM
            )
            val id = courseRepository.insertPoint(point)
            val saved = point.copy(id = id)
            _activeBasket.postValue(saved)
            onResult(true)
        }
    }

    /**
     * Saves the end position of the last recorded throw as the basket for the current hole.
     */
    fun saveLastEndAsBasket(altitudeM: Double?, onResult: (Boolean) -> Unit) {
        val last = _lastThrow.value ?: run { onResult(false); return }
        saveBasketPoint(last.endLat, last.endLng, altitudeM, onResult)
    }

    // ── Course / tee saving ───────────────────────────────────────────────────

    fun getLastCourseOption(): String = prefs.getString(PREF_LAST_COURSE_OPTION, "Short") ?: "Short"

    fun saveTeePoint(
        lat: Double, lng: Double,
        courseName: String, courseOption: String, holeNumber: Int,
        bearing: Double? = null, altitudeM: Double? = null,
        onResult: (TeeInfo?) -> Unit
    ) {
        viewModelScope.launch {
            prefs.edit().putString(PREF_LAST_COURSE_OPTION, courseOption).apply()
            val course = courseRepository.getOrCreateCourse(courseName.trim(), courseOption.trim())
            val point = CoursePoint(
                courseId = course.id,
                featureType = CoursePoint.TYPE_TEE,
                holeNumber = holeNumber,
                lat = lat, lng = lng,
                bearing = bearing, altitudeM = altitudeM
            )
            val id = courseRepository.insertPoint(point)
            onResult(TeeInfo(point.copy(id = id), course))
        }
    }

    fun getUsedTeeHoles(courseId: Long, onResult: (List<Int>) -> Unit) {
        viewModelScope.launch {
            onResult(courseRepository.getUsedHoles(courseId, CoursePoint.TYPE_TEE))
        }
    }

    fun getCourseNamesAndOptions(onResult: (names: List<String>, options: List<String>) -> Unit) {
        viewModelScope.launch {
            onResult(courseRepository.getAllCourseNames(), courseRepository.getAllCourseOptions())
        }
    }

    fun getCoursesNearLocation(lat: Double, lng: Double, onResult: (List<Course>) -> Unit) {
        viewModelScope.launch {
            val delta = 0.005
            onResult(courseRepository.getCoursesNearby(
                minLat = lat - delta, maxLat = lat + delta,
                minLng = lng - delta, maxLng = lng + delta
            ))
        }
    }

    fun findTeePoint(courseId: Long, holeNumber: Int, onResult: (TeeInfo?) -> Unit) {
        viewModelScope.launch {
            val course = courseRepository.getCourseById(courseId)
            if (course == null) { onResult(null); return@launch }
            val point = courseRepository.getPointsByType(courseId, CoursePoint.TYPE_TEE)
                .firstOrNull { it.holeNumber == holeNumber }
            onResult(if (point != null) TeeInfo(point, course) else null)
        }
    }

    fun getOrFindCourseId(name: String, option: String, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            val course = courseRepository.getCoursesByName(name)
                .firstOrNull { it.option.equals(option, ignoreCase = true) }
            onResult(course?.id)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compass bearing in degrees (0–360) from point 1 to point 2.
     */
    fun computeBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng = Math.toRadians(lng2 - lng1)
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val y = sin(dLng) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun newSessionId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
