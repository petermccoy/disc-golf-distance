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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ActivityMainBinding
import com.discgolf.distance.ui.AppState
import com.discgolf.distance.ui.DiscManagerActivity
import com.discgolf.distance.ui.DiscSelectAdapter
import com.discgolf.distance.ui.MainViewModel
import com.discgolf.distance.ui.RangefinderInfo
import com.discgolf.distance.ui.TeeInfo
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
            val alt = if (loc.hasAltitude()) loc.altitude else null
            viewModel.checkNearbyTees(loc.latitude, loc.longitude, alt)
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) startLocationUpdates()
        else {
            Toast.makeText(this, "Precise GPS permission is required.", Toast.LENGTH_LONG).show()
            binding.btnStartLocation.isEnabled = false
        }
    }

    // ── Disc selection state ──────────────────────────────────────────────────

    private var pendingEndLocation: Location? = null
    private var discSelectAdapter: DiscSelectAdapter? = null
    private var discSelectionDialog: AlertDialog? = null

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
            R.id.action_list -> { startActivity(Intent(this, ThrowListActivity::class.java)); true }
            R.id.action_graph -> {
                val intent = Intent(this, ThrowGraphActivity::class.java)
                viewModel.sessionTargetBearing.value?.let {
                    intent.putExtra(ThrowGraphActivity.EXTRA_TARGET_BEARING, it)
                }
                startActivity(intent); true
            }
            R.id.action_manage_discs -> { startActivity(Intent(this, DiscManagerActivity::class.java)); true }
            R.id.action_new_session -> { confirmNewSession(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStartLocation.setOnClickListener { onStartClicked() }
        binding.btnEndLocation.setOnClickListener { onEndClicked() }

        binding.btnSetAim.setOnClickListener {
            if (currentCompassBearing.isNaN())
                Toast.makeText(this, "Compass not ready – point phone toward basket and try again.", Toast.LENGTH_SHORT).show()
            else viewModel.setTargetBearing(currentCompassBearing)
        }
        binding.btnClearAim.setOnClickListener { viewModel.clearTargetBearing() }

        // Fixed-start toggle
        binding.switchFixedStart.setOnCheckedChangeListener { _, checked ->
            viewModel.setFixedStartEnabled(checked)
        }
        binding.btnSelectTee.setOnClickListener { showTeeSelectionDialog() }

        // Rangefinder card
        binding.btnMarkBasket.setOnClickListener { onMarkBasketClicked() }

        // cardStarted actions
        binding.btnSaveAsTee.setOnClickListener {
            val snap = viewModel.getStartSnapshot() ?: return@setOnClickListener
            showSaveTeeDialog(snap.lat, snap.lng)
        }
        binding.btnCancelStart.setOnClickListener { viewModel.startNewLocation() }
    }

    private fun observeViewModel() {
        viewModel.appState.observe(this) { state -> applyState(state) }
        viewModel.statusMessage.observe(this) { binding.tvStatus.text = it }
        viewModel.lastThrow.observe(this) { it?.let { t -> showThrowResult(t) } }
        viewModel.currentAccuracy.observe(this) { updateAccuracyDisplay(it) }
        viewModel.allThrows.observe(this) { binding.tvSessionInfo.text = buildSessionInfo(it) }
        viewModel.sessionTargetBearing.observe(this) { updateAimDisplay(it) }
        viewModel.allDiscs.observe(this) { discSelectAdapter?.submitList(it) }

        // Fixed-start state
        viewModel.fixedStartEnabled.observe(this) { enabled ->
            binding.switchFixedStart.isChecked = enabled
            binding.rowTeeStatus.visibility = if (enabled) View.VISIBLE else View.GONE
            binding.tvFixedStartSubtitle.text = if (enabled) "Using fixed tee point" else "Using GPS location"
        }
        viewModel.selectedTee.observe(this) { tee ->
            if (tee != null) {
                binding.tvTeeStatus.text = tee.label
                binding.tvTeeStatus.setTextColor(getColor(R.color.accuracy_good))
                binding.tvRangefinderLabel.text = "RANGEFINDER  ·  ${tee.label}"
            } else if (viewModel.fixedStartEnabled.value == true) {
                binding.tvTeeStatus.text = "No nearby tee – tap Select"
                binding.tvTeeStatus.setTextColor(getColor(R.color.text_secondary))
            }
        }
        viewModel.nearbyTee.observe(this) { tee ->
            if (viewModel.fixedStartEnabled.value == true && viewModel.selectedTee.value == null) {
                binding.tvTeeStatus.text = if (tee != null) "Auto-detected: ${tee.label}"
                                           else "No nearby tee – tap Select"
                binding.tvTeeStatus.setTextColor(
                    if (tee != null) getColor(R.color.accuracy_good)
                    else getColor(R.color.text_secondary)
                )
            }
        }

        // Rangefinder card
        viewModel.activeBasket.observe(this) { basket ->
            val teeSelected = viewModel.selectedTee.value != null &&
                              viewModel.fixedStartEnabled.value == true
            if (teeSelected) {
                binding.cardRangefinder.visibility = View.VISIBLE
                if (basket != null) {
                    // Basket known – show live readings, hide the Mark button
                    binding.tvRangeDistance.text = "—"
                    binding.btnMarkBasket.visibility = View.GONE
                } else {
                    // No basket yet – hide distance text, show Mark button
                    binding.tvRangeDistance.text = "No basket saved"
                    binding.tvRangeDistanceM.visibility = View.GONE
                    binding.tvRangeBearing.visibility = View.GONE
                    binding.tvRangeElevation.visibility = View.GONE
                    binding.btnMarkBasket.visibility = View.VISIBLE
                }
            } else {
                binding.cardRangefinder.visibility = View.GONE
            }
        }
        viewModel.rangefinderInfo.observe(this) { info ->
            updateRangefinderCard(info)
        }
    }

    private fun updateRangefinderCard(info: RangefinderInfo?) {
        if (info == null) return
        binding.tvRangeDistance.text = "%.0f ft".format(info.distanceFt)
        binding.tvRangeDistanceM.text = "%.1f m".format(info.distanceM)
        binding.tvRangeDistanceM.visibility = View.VISIBLE
        binding.tvRangeBearing.text = "%.0f°  %s".format(info.bearing, bearingToCardinal(info.bearing.toFloat()))
        binding.tvRangeBearing.visibility = View.VISIBLE

        if (info.elevationChangeM != null) {
            val sign = if (info.elevationChangeM >= 0) "+" else ""
            val label = if (info.elevationChangeM >= 1.5) "↑ uphill"
                        else if (info.elevationChangeM <= -1.5) "↓ downhill"
                        else "≈ level"
            binding.tvRangeElevation.text = "$sign%.0f m  $label  (approx)".format(info.elevationChangeM)
            binding.tvRangeElevation.visibility = View.VISIBLE
        } else {
            binding.tvRangeElevation.visibility = View.GONE
        }
        binding.btnMarkBasket.visibility = View.GONE
    }

    private fun applyState(state: AppState) {
        when (state) {
            AppState.IDLE -> {
                binding.btnStartLocation.isEnabled = true
                binding.btnEndLocation.isEnabled = false
                binding.btnEndLocation.alpha = 0.4f
                binding.btnStartLocation.alpha = 1f
                binding.cardStarted.visibility = View.GONE
                binding.btnSaveAsTee.visibility = View.GONE
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
                    if (snap.accuracyM == 0f) {
                        binding.tvStartCoords.text = "%.6f, %.6f  (fixed)".format(snap.lat, snap.lng)
                        binding.btnSaveAsTee.visibility = View.GONE
                    } else {
                        binding.tvStartCoords.text = "%.6f, %.6f  ±%.0fm".format(snap.lat, snap.lng, snap.accuracyM)
                        if (viewModel.fixedStartEnabled.value != true)
                            binding.btnSaveAsTee.visibility = View.VISIBLE
                    }
                }
            }
            AppState.THROW_RECORDED -> { /* handled via lastThrow observer */ }
        }
    }

    // ── Aim direction UI ──────────────────────────────────────────────────────

    private fun updateLiveCompassDisplay(bearing: Float) {
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
        if (!hasLocationPermission())
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        else startLocationUpdates()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && lastKnownLocation == null) {
                lastKnownLocation = loc
                updateAccuracyDisplay(loc.accuracy)
                val alt = if (loc.hasAltitude()) loc.altitude else null
                viewModel.checkNearbyTees(loc.latitude, loc.longitude, alt)
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
        if (viewModel.fixedStartEnabled.value == true && viewModel.selectedTee.value == null) {
            AlertDialog.Builder(this)
                .setTitle("No Tee Selected")
                .setMessage("Fixed start is on but no tee is selected.\n\nSelect a tee or turn off Fixed Start.")
                .setPositiveButton("Select Tee") { _, _ -> showTeeSelectionDialog() }
                .setNegativeButton("Use GPS") { _, _ ->
                    viewModel.setFixedStartEnabled(false)
                    proceedWithGpsStart()
                }
                .show()
            return
        }
        proceedWithGpsStart()
    }

    private fun proceedWithGpsStart() {
        val loc = lastKnownLocation
        if (loc == null) { Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show(); return }
        if (viewModel.fixedStartEnabled.value == true && viewModel.selectedTee.value != null) {
            viewModel.recordStart(loc); return
        }
        if (loc.accuracy > 15f) {
            AlertDialog.Builder(this)
                .setTitle("Low GPS Accuracy")
                .setMessage("Current accuracy is ±%.0f m. For best results wait for a better fix.\n\nMark start anyway?".format(loc.accuracy))
                .setPositiveButton("Mark Start") { _, _ -> viewModel.recordStart(loc) }
                .setNegativeButton("Wait", null).show()
        } else {
            viewModel.recordStart(loc)
        }
    }

    private fun onEndClicked() {
        val loc = lastKnownLocation
        if (loc == null) { Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show(); return }
        if (loc.accuracy > 15f) {
            AlertDialog.Builder(this)
                .setTitle("Low GPS Accuracy")
                .setMessage("Current accuracy is ±%.0f m. Mark end anyway?".format(loc.accuracy))
                .setPositiveButton("Mark End") { _, _ -> showDiscSelection(loc) }
                .setNegativeButton("Wait", null).show()
        } else {
            showDiscSelection(loc)
        }
    }

    // ── Mark basket (from current GPS) ────────────────────────────────────────

    private fun onMarkBasketClicked() {
        val loc = lastKnownLocation
        if (loc == null) { Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show(); return }
        val tee = viewModel.selectedTee.value
        if (tee == null) { Toast.makeText(this, "Select a tee first.", Toast.LENGTH_SHORT).show(); return }

        AlertDialog.Builder(this)
            .setTitle("Mark Basket – Hole ${tee.point.holeNumber}")
            .setMessage("Save current GPS position as the basket for ${tee.label}?\n\nMake sure you are standing at the basket.\n\nGPS accuracy: ±%.0f m".format(loc.accuracy))
            .setPositiveButton("Save Basket") { _, _ ->
                val alt = if (loc.hasAltitude()) loc.altitude else null
                viewModel.saveBasketPoint(loc.latitude, loc.longitude, alt) { _ ->
                    runOnUiThread {
                        Toast.makeText(this, "Basket saved for ${tee.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Tee selection dialog ──────────────────────────────────────────────────

    private fun showTeeSelectionDialog() {
        val loc = lastKnownLocation
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0

        viewModel.getCoursesNearLocation(lat, lng) { nearbyCourses ->
            runOnUiThread {
                if (nearbyCourses.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No Tees Found")
                        .setMessage("No courses with saved tees were found near your current location.\n\nMark a start with GPS, then use \"Save as Tee\" to add tees.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val courseLabels = nearbyCourses.map { "${it.name} · ${it.option}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Course")
                    .setItems(courseLabels) { _, idx ->
                        val selected = nearbyCourses[idx]
                        showHolePickerForCourse(selected.id, selected.name, selected.option)
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun showHolePickerForCourse(courseId: Long, courseName: String, courseOption: String) {
        viewModel.getUsedTeeHoles(courseId) { usedHoles ->
            runOnUiThread {
                if (usedHoles.isEmpty()) {
                    Toast.makeText(this, "No tees saved for $courseName · $courseOption", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val sortedHoles = usedHoles.sorted()
                AlertDialog.Builder(this)
                    .setTitle("$courseName · $courseOption")
                    .setItems(sortedHoles.map { "Hole $it" }.toTypedArray()) { _, idx ->
                        viewModel.findTeePoint(courseId, sortedHoles[idx]) { teeInfo ->
                            runOnUiThread {
                                if (teeInfo != null) {
                                    viewModel.selectTee(teeInfo)
                                    Toast.makeText(this, "Tee: ${teeInfo.label}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Back") { _, _ -> showTeeSelectionDialog() }.show()
            }
        }
    }

    // ── Save-as-tee dialog ────────────────────────────────────────────────────

    private fun showSaveTeeDialog(lat: Double, lng: Double) {
        var capturedBearing: Double? = null
        val altitudeM = lastKnownLocation?.let { if (it.hasAltitude()) it.altitude else null }

        val dialogView = layoutInflater.inflate(R.layout.dialog_save_tee, null)
        val actvName         = dialogView.findViewById<AutoCompleteTextView>(R.id.actvCourseName)
        val actvOption       = dialogView.findViewById<AutoCompleteTextView>(R.id.actvCourseOption)
        val actvHole         = dialogView.findViewById<AutoCompleteTextView>(R.id.actvHoleNumber)
        val tvHint           = dialogView.findViewById<TextView>(R.id.tvUsedHolesHint)
        val tvBearingDisplay = dialogView.findViewById<TextView>(R.id.tvBearingDisplay)
        val btnCapture       = dialogView.findViewById<View>(R.id.btnCaptureBearing)

        btnCapture.setOnClickListener {
            if (currentCompassBearing.isNaN())
                Toast.makeText(this, "Compass not ready – point phone toward the basket.", Toast.LENGTH_SHORT).show()
            else {
                capturedBearing = currentCompassBearing.toDouble()
                tvBearingDisplay.text = "%.0f° %s".format(currentCompassBearing, bearingToCardinal(currentCompassBearing))
                tvBearingDisplay.setTextColor(getColor(R.color.accuracy_good))
            }
        }

        viewModel.getCourseNamesAndOptions { names, options ->
            runOnUiThread {
                actvName.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names))
                val optSuggestions = options.ifEmpty { listOf("Short", "Long", "Pro", "Recreational") }
                actvOption.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, optSuggestions))
                actvOption.setText(viewModel.getLastCourseOption(), false)
            }
        }

        fun refreshHoles(courseName: String, courseOption: String) {
            if (courseName.isBlank() || courseOption.isBlank()) {
                setupHoleDropdown(actvHole, tvHint, emptyList()); return
            }
            viewModel.getOrFindCourseId(courseName, courseOption) { courseId ->
                if (courseId == null) {
                    runOnUiThread { setupHoleDropdown(actvHole, tvHint, emptyList()) }
                } else {
                    viewModel.getUsedTeeHoles(courseId) { used ->
                        runOnUiThread { setupHoleDropdown(actvHole, tvHint, used) }
                    }
                }
            }
        }

        actvName.setOnItemClickListener { _, _, _, _ -> refreshHoles(actvName.text.toString(), actvOption.text.toString()) }
        actvOption.setOnItemClickListener { _, _, _, _ -> refreshHoles(actvName.text.toString(), actvOption.text.toString()) }
        actvName.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshHoles(actvName.text.toString(), actvOption.text.toString()) }
        actvOption.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshHoles(actvName.text.toString(), actvOption.text.toString()) }

        viewModel.getCoursesNearLocation(lat, lng) { nearby ->
            runOnUiThread {
                if (nearby.isNotEmpty() && actvName.text.isBlank()) {
                    actvName.setText(nearby[0].name, false)
                    actvOption.setText(nearby[0].option, false)
                    refreshHoles(nearby[0].name, nearby[0].option)
                } else {
                    setupHoleDropdown(actvHole, tvHint, emptyList())
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Save as Fixed Tee")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name   = actvName.text.toString().trim()
                val option = actvOption.text.toString().trim()
                val hole   = actvHole.text.toString().removePrefix("Hole ").trim().toIntOrNull()
                when {
                    name.isBlank()                    -> actvName.error = "Course name required"
                    option.isBlank()                  -> actvOption.error = "Layout / option required"
                    hole == null || hole < 1 || hole > 18 -> actvHole.error = "Select a hole (1–18)"
                    else -> {
                        dialog.dismiss()
                        viewModel.saveTeePoint(lat, lng, name, option, hole, capturedBearing, altitudeM) { teeInfo ->
                            runOnUiThread {
                                if (teeInfo != null) {
                                    Toast.makeText(this, "Tee saved: ${teeInfo.label}", Toast.LENGTH_SHORT).show()
                                    binding.btnSaveAsTee.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setupHoleDropdown(actv: AutoCompleteTextView, tvHint: TextView, usedHoles: List<Int>) {
        val all = (1..18).map { if (it in usedHoles) "Hole $it (taken)" else "Hole $it" }
        actv.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, all))
        if (usedHoles.isNotEmpty()) {
            tvHint.text = "Holes already saved: ${usedHoles.sorted().joinToString(", ")}"
            tvHint.visibility = View.VISIBLE
        } else {
            tvHint.visibility = View.GONE
        }
        val first = (1..18).firstOrNull { it !in usedHoles }
        if (first != null) actv.setText("Hole $first", false)
        actv.setOnItemClickListener { _, _, pos, _ ->
            if (all[pos].endsWith("(taken)")) {
                actv.setText(actv.text, false)
                Toast.makeText(actv.context, "That hole already has a tee saved.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Disc selection ────────────────────────────────────────────────────────

    private fun showDiscSelection(loc: Location) {
        pendingEndLocation = loc
        val view     = layoutInflater.inflate(R.layout.dialog_select_disc, null)
        val rv       = view.findViewById<RecyclerView>(R.id.rvSelectDiscs)
        val tvEmpty  = view.findViewById<TextView>(R.id.tvNoDiscs)
        val btnNone  = view.findViewById<Button>(R.id.btnNoDisc)
        val btnAdd   = view.findViewById<Button>(R.id.btnAddNewDisc)

        val adapter = DiscSelectAdapter { disc -> discSelectionDialog?.dismiss(); viewModel.recordEnd(loc, disc.id) }
        discSelectAdapter = adapter
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        val discs = viewModel.allDiscs.value ?: emptyList()
        adapter.submitList(discs)
        tvEmpty.visibility = if (discs.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility      = if (discs.isEmpty()) View.GONE   else View.VISIBLE

        val dialog = AlertDialog.Builder(this).setTitle("Select Disc").setView(view).create()
        discSelectionDialog = dialog
        btnNone.setOnClickListener { dialog.dismiss(); viewModel.recordEnd(loc, null) }
        btnAdd.setOnClickListener  { dialog.dismiss(); startActivity(Intent(this, DiscManagerActivity::class.java)) }
        dialog.setOnDismissListener { discSelectionDialog = null; discSelectAdapter = null; pendingEndLocation = null }
        dialog.show()
    }

    // ── Post-throw dialog ─────────────────────────────────────────────────────

    private fun showThrowResult(discThrow: DiscThrow) {
        val isPB     = viewModel.lastThrowIsPersonalBest.value == true
        val discName = discThrow.discId?.let { id ->
            viewModel.allDiscs.value?.find { it.id == id }?.name
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_throw_result, null)
        val tvTitle      = dialogView.findViewById<TextView>(R.id.tvResultTitle)
        val tvDetails    = dialogView.findViewById<TextView>(R.id.tvResultDetails)
        val rowEndBasket = dialogView.findViewById<View>(R.id.rowEndToBasket)
        val tvEndBasket  = dialogView.findViewById<TextView>(R.id.tvEndToBasket)
        val btnNext      = dialogView.findViewById<View>(R.id.btnNextDisc)
        val btnRethrow   = dialogView.findViewById<View>(R.id.btnRethrow)
        val btnNewStart  = dialogView.findViewById<View>(R.id.btnNewStart)
        val btnSaveBasket = dialogView.findViewById<View>(R.id.btnSaveEndAsBasket)
        val btnDone      = dialogView.findViewById<View>(R.id.btnDone)

        tvTitle.text = if (isPB) "Throw Recorded! ★" else "Throw Recorded!"
        tvTitle.setTextColor(if (isPB) getColor(R.color.accuracy_good) else getColor(R.color.text_primary))

        val details = buildString {
            append("%.1f ft  (%.1f m)".format(discThrow.distanceFeet, discThrow.distanceMeters))
            append("\nThrow #${discThrow.throwNumber}  ·  ${discThrow.sessionId}")
            if (discName != null) append("\nDisc: $discName")
            if (isPB) append("\n\nNew personal best!")
        }
        tvDetails.text = details

        // Disc-to-basket distance if we have a basket
        val basket = viewModel.activeBasket.value
        if (basket != null) {
            val distToBasket = DiscThrow.calculateDistance(
                discThrow.endLat, discThrow.endLng, basket.lat, basket.lng
            )
            val bearing = viewModel.computeBearing(discThrow.endLat, discThrow.endLng, basket.lat, basket.lng)
            rowEndBasket.visibility = View.VISIBLE
            tvEndBasket.text = "%.0f ft  (%.1f m)  %.0f° %s".format(
                distToBasket * 3.28084, distToBasket,
                bearing, bearingToCardinal(bearing.toFloat())
            )
            // Don't offer to save basket if one already exists
            btnSaveBasket.visibility = View.GONE
        } else if (viewModel.selectedTee.value != null) {
            // No basket yet – offer to save end position as basket
            btnSaveBasket.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnNext.setOnClickListener { dialog.dismiss(); viewModel.continueNextDisc() }
        btnRethrow.setOnClickListener { dialog.dismiss(); viewModel.useEndAsNewStart() }
        btnNewStart.setOnClickListener { dialog.dismiss(); viewModel.startNewLocation() }
        btnDone.setOnClickListener { dialog.dismiss(); viewModel.startNewLocation() }
        btnSaveBasket.setOnClickListener {
            val lastLoc = lastKnownLocation
            val alt = lastLoc?.let { if (it.hasAltitude()) it.altitude else null }
            viewModel.saveLastEndAsBasket(alt) { _ ->
                runOnUiThread {
                    val tee = viewModel.selectedTee.value
                    val msg = if (tee != null) "Basket saved for ${tee.label}" else "Basket saved"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    btnSaveBasket.visibility = View.GONE
                }
            }
        }

        dialog.show()
    }

    private fun confirmNewSession() {
        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setMessage("Start a fresh session? Current session data is kept in history.")
            .setPositiveButton("New Session") { _, _ -> viewModel.startNewSession() }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSessionInfo(throws: List<DiscThrow>): String {
        val sessionThrows = throws.filter { it.sessionId == viewModel.currentSessionId }
        return if (sessionThrows.isEmpty()) {
            "Session: ${viewModel.currentSessionId}\nNo throws yet"
        } else {
            val max = sessionThrows.maxOf { it.distanceFeet }
            val avg = sessionThrows.map { it.distanceFeet }.average()
            "Session: ${viewModel.currentSessionId}\n" +
            "Throws: ${sessionThrows.size}  |  Best: %.1f ft  |  Avg: %.1f ft".format(max, avg)
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
