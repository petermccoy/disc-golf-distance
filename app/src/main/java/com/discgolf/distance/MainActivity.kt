package com.discgolf.distance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ActivityMainBinding
import com.discgolf.distance.ui.AppState
import com.discgolf.distance.ui.MainViewModel
import com.discgolf.distance.ui.ThrowGraphActivity
import com.discgolf.distance.ui.ThrowListActivity
import com.google.android.gms.location.*
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null

    // ── Compass sensor ────────────────────────────────────────────────────────

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var currentCompassBearing = Float.NaN

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotMatrix, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            currentCompassBearing = (azimuth + 360f) % 360f
            updateLiveCompassDisplay(currentCompassBearing)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2_000L
    ).apply {
        setMinUpdateIntervalMillis(1_000L)
        setWaitForAccurateLocation(true)
    }.build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastKnownLocation = loc
            viewModel.updateCurrentAccuracy(loc.accuracy)
            updateAccuracyDisplay(loc.accuracy)
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Precise GPS permission is required.", Toast.LENGTH_LONG).show()
            binding.btnStartLocation.isEnabled = false
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupButtons()
        observeViewModel()
        requestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocationUpdates()
        rotationVectorSensor?.let {
            sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(compassListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_list -> {
                startActivity(Intent(this, ThrowListActivity::class.java))
                true
            }
            R.id.action_graph -> {
                val intent = Intent(this, ThrowGraphActivity::class.java)
                viewModel.sessionTargetBearing.value?.let { bearing ->
                    intent.putExtra(ThrowGraphActivity.EXTRA_TARGET_BEARING, bearing)
                }
                startActivity(intent)
                true
            }
            R.id.action_new_session -> {
                confirmNewSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStartLocation.setOnClickListener { onStartClicked() }
        binding.btnEndLocation.setOnClickListener { onEndClicked() }

        binding.btnSetAim.setOnClickListener {
            if (currentCompassBearing.isNaN()) {
                Toast.makeText(this, "Compass not ready – point phone toward basket and try again.", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.setTargetBearing(currentCompassBearing)
            }
        }
        binding.btnClearAim.setOnClickListener {
            viewModel.clearTargetBearing()
        }
    }

    private fun observeViewModel() {
        viewModel.appState.observe(this) { state -> applyState(state) }
        viewModel.statusMessage.observe(this) { msg ->
            binding.tvStatus.text = msg
        }
        viewModel.lastThrow.observe(this) { throw_ ->
            throw_ ?: return@observe
            showThrowResult(throw_)
        }
        viewModel.currentAccuracy.observe(this) { acc ->
            updateAccuracyDisplay(acc)
        }
        viewModel.allThrows.observe(this) { throws ->
            binding.tvSessionInfo.text = buildSessionInfo(throws)
        }
        viewModel.sessionTargetBearing.observe(this) { bearing ->
            updateAimDisplay(bearing)
        }
    }

    private fun applyState(state: AppState) {
        when (state) {
            AppState.IDLE -> {
                binding.btnStartLocation.isEnabled = true
                binding.btnEndLocation.isEnabled = false
                binding.btnEndLocation.alpha = 0.4f
                binding.btnStartLocation.alpha = 1f
                binding.cardStarted.visibility = View.GONE
            }
            AppState.AWAITING_END -> {
                binding.btnStartLocation.isEnabled = false
                binding.btnEndLocation.isEnabled = true
                binding.btnEndLocation.alpha = 1f
                binding.btnStartLocation.alpha = 0.4f
                binding.cardStarted.visibility = View.VISIBLE
                binding.tvStartTime.text = "Started: ${formatTime(System.currentTimeMillis())}"
                val snap = viewModel.getStartSnapshot()
                if (snap != null) {
                    binding.tvStartCoords.text = "%.6f, %.6f  ±%.0fm".format(
                        snap.lat, snap.lng, snap.accuracyM
                    )
                }
            }
            AppState.THROW_RECORDED -> {
                // Dialog is shown via lastThrow observer
            }
        }
    }

    // ── Aim direction UI ──────────────────────────────────────────────────────

    private fun updateLiveCompassDisplay(bearing: Float) {
        // Only show live reading if aim is not yet set
        if (viewModel.sessionTargetBearing.value == null) {
            binding.tvLiveCompass.text = "%.0f°  %s — point at basket, tap Set Aim".format(
                bearing, bearingToCardinal(bearing)
            )
        }
    }

    private fun updateAimDisplay(bearing: Float?) {
        if (bearing == null) {
            binding.tvLiveCompass.visibility = View.VISIBLE
            binding.btnSetAim.visibility = View.VISIBLE
            binding.rowAimSet.visibility = View.GONE
        } else {
            binding.tvLiveCompass.visibility = View.GONE
            binding.btnSetAim.visibility = View.GONE
            binding.rowAimSet.visibility = View.VISIBLE
            binding.tvAimSet.text = "Aimed: %.0f° %s".format(bearing, bearingToCardinal(bearing))
        }
    }

    private fun bearingToCardinal(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((bearing + 22.5f) / 45f).toInt() % 8]
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private fun requestLocationPermissions() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(arrayOf(fine, coarse))
        } else {
            startLocationUpdates()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && lastKnownLocation == null) {
                lastKnownLocation = loc
                updateAccuracyDisplay(loc.accuracy)
            }
        }
    }

    private fun updateAccuracyDisplay(accuracy: Float) {
        val color = when {
            accuracy <= 3f  -> getColor(R.color.accuracy_good)
            accuracy <= 10f -> getColor(R.color.accuracy_ok)
            else            -> getColor(R.color.accuracy_poor)
        }
        binding.tvGpsAccuracy.text = "GPS ±%.1f m".format(accuracy)
        binding.tvGpsAccuracy.setTextColor(color)
        binding.ivGpsIndicator.setColorFilter(color)
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    private fun onStartClicked() {
        val loc = lastKnownLocation
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        if (loc.accuracy > 15f) {
            AlertDialog.Builder(this)
                .setTitle("Low GPS Accuracy")
                .setMessage("Current accuracy is ±%.0f m. For best results wait for a better fix.\n\nMark start anyway?".format(loc.accuracy))
                .setPositiveButton("Mark Start") { _, _ -> viewModel.recordStart(loc) }
                .setNegativeButton("Wait", null)
                .show()
        } else {
            viewModel.recordStart(loc)
        }
    }

    private fun onEndClicked() {
        val loc = lastKnownLocation
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        if (loc.accuracy > 15f) {
            AlertDialog.Builder(this)
                .setTitle("Low GPS Accuracy")
                .setMessage("Current accuracy is ±%.0f m. Mark end anyway?".format(loc.accuracy))
                .setPositiveButton("Mark End") { _, _ -> viewModel.recordEnd(loc) }
                .setNegativeButton("Wait", null)
                .show()
        } else {
            viewModel.recordEnd(loc)
        }
    }

    // ── Post-throw dialog ─────────────────────────────────────────────────────

    private fun showThrowResult(discThrow: DiscThrow) {
        val distFt = discThrow.distanceFeet
        val distM  = discThrow.distanceMeters
        val msg = "Distance: %.1f ft  (%.1f m)\nThrow #%d in session %s".format(
            distFt, distM, discThrow.throwNumber, discThrow.sessionId
        )

        AlertDialog.Builder(this)
            .setTitle("Throw Recorded!")
            .setMessage(msg)
            .setPositiveButton("Next Disc (same start)") { _, _ ->
                viewModel.continueNextDisc()
            }
            .setNeutralButton("New Start Location") { _, _ ->
                viewModel.startNewLocation()
            }
            .setNegativeButton("Done / Exit") { _, _ ->
                viewModel.startNewLocation()
            }
            .setCancelable(false)
            .show()
    }

    private fun confirmNewSession() {
        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setMessage("Start a fresh session? Current session data is kept in history.")
            .setPositiveButton("New Session") { _, _ -> viewModel.startNewSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSessionInfo(throws: List<DiscThrow>): String {
        val sessionThrows = throws.filter { it.sessionId == viewModel.currentSessionId }
        return if (sessionThrows.isEmpty()) {
            "Session: ${viewModel.currentSessionId}\nNo throws yet"
        } else {
            val maxDist = sessionThrows.maxOf { it.distanceFeet }
            val avgDist = sessionThrows.map { it.distanceFeet }.average()
            "Session: ${viewModel.currentSessionId}\n" +
            "Throws: ${sessionThrows.size}  |  Best: %.1f ft  |  Avg: %.1f ft".format(maxDist, avgDist)
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
