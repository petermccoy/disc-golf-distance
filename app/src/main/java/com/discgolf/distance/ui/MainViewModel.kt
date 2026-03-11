package com.discgolf.distance.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.data.ThrowRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ThrowRepository(application)

    val allThrows: LiveData<List<DiscThrow>> = repository.allThrows

    private val _appState = MutableLiveData(AppState.IDLE)
    val appState: LiveData<AppState> get() = _appState

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> get() = _statusMessage

    private val _lastThrow = MutableLiveData<DiscThrow?>()
    val lastThrow: LiveData<DiscThrow?> get() = _lastThrow

    private val _currentAccuracy = MutableLiveData<Float>()
    val currentAccuracy: LiveData<Float> get() = _currentAccuracy

    // Aim direction – compass bearing toward the basket for this session
    private val _sessionTargetBearing = MutableLiveData<Float?>(null)
    val sessionTargetBearing: LiveData<Float?> get() = _sessionTargetBearing

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

    fun recordStart(location: Location) {
        startSnapshot = LocationSnapshot(
            lat = location.latitude,
            lng = location.longitude,
            timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            accuracyM = location.accuracy
        )
        _appState.value = AppState.AWAITING_END
        _statusMessage.value = "Start marked – accuracy ±${location.accuracy.toInt()} m\nMove to disc landing spot, then tap End Location."
    }

    fun recordEnd(location: Location) {
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
            flightTimeMs = endTime - start.timeMs
        )

        viewModelScope.launch {
            repository.insert(discThrow)
            _lastThrow.postValue(discThrow)
            _appState.postValue(AppState.THROW_RECORDED)
        }
    }

    /** Keep same session, keep same start point → record another disc from same spot */
    fun continueNextDisc() {
        // startSnapshot stays the same
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

    fun getStartSnapshot(): LocationSnapshot? = startSnapshot

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun newSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return sdf.format(Date())
    }
}
