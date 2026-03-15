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
import com.discgolf.distance.data.CoursePoint
import com.discgolf.distance.data.DiscThrow
import com.discgolf.distance.databinding.ActivityMainBinding
import com.discgolf.distance.ui.AppState
import com.discgolf.distance.ui.DiscManagerActivity
import com.discgolf.distance.ui.DiscSelectAdapter
import com.discgolf.distance.ui.MainViewModel
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
            viewModel.checkNearbyTees(loc.latitude, loc.longitude)
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
            R.id.action_manage_discs -> {
                startActivity(Intent(this, DiscManagerActivity::class.java))
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

        // Fixed-start toggle
        binding.switchFixedStart.setOnCheckedChangeListener { _, checked ->
            viewModel.setFixedStartEnabled(checked)
        }

        // Manual tee selector
        binding.btnSelectTee.setOnClickListener {
            showTeeSelectionDialog()
        }

        // Save current GPS start as a fixed tee
        binding.btnSaveAsTee.setOnClickListener {
            val snap = viewModel.getStartSnapshot() ?: return@setOnClickListener
            showSaveTeeDialog(snap.lat, snap.lng)
        }

        // Abort an accidentally-started throw
        binding.btnCancelStart.setOnClickListener {
            viewModel.startNewLocation()
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
        viewModel.allDiscs.observe(this) { discs ->
            discSelectAdapter?.submitList(discs)
        }

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
            } else if (viewModel.fixedStartEnabled.value == true) {
                binding.tvTeeStatus.text = "No nearby tee – tap Select"
                binding.tvTeeStatus.setTextColor(getColor(R.color.text_secondary))
            }
        }

        viewModel.nearbyTee.observe(this) { tee ->
            if (viewModel.fixedStartEnabled.value == true && viewModel.selectedTee.value == null) {
                if (tee != null) {
                    binding.tvTeeStatus.text = "Auto-detected: ${tee.label}"
                    binding.tvTeeStatus.setTextColor(getColor(R.color.accuracy_good))
                } else {
                    binding.tvTeeStatus.text = "No nearby tee – tap Select"
                    binding.tvTeeStatus.setTextColor(getColor(R.color.text_secondary))
                }
            }
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
                        // Fixed tee point
                        binding.tvStartCoords.text = "%.6f, %.6f  (fixed tee)".format(snap.lat, snap.lng)
                        binding.btnSaveAsTee.visibility = View.GONE
                    } else {
                        binding.tvStartCoords.text = "%.6f, %.6f  ±%.0fm".format(snap.lat, snap.lng, snap.accuracyM)
                        // Offer save-as-tee only when using live GPS
                        if (viewModel.fixedStartEnabled.value != true) {
                            binding.btnSaveAsTee.visibility = View.VISIBLE
                        }
                    }
                }
            }
            AppState.THROW_RECORDED -> {
                // Dialog is shown via lastThrow observer
            }
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
                viewModel.checkNearbyTees(loc.latitude, loc.longitude)
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
        // If fixed-start is enabled but no tee is selected, block and prompt
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
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        // Skip accuracy warning when using a fixed tee point
        if (viewModel.fixedStartEnabled.value == true && viewModel.selectedTee.value != null) {
            viewModel.recordStart(loc)
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
                .setPositiveButton("Mark End") { _, _ -> showDiscSelection(loc) }
                .setNegativeButton("Wait", null)
                .show()
        } else {
            showDiscSelection(loc)
        }
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
                        .setMessage("No courses with saved tees were found near your current location.\n\nMark a start with GPS, then use \"Save as Fixed Tee\" to add tees.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }

                // Build a flat list of all tees for nearby courses
                // We show: "Course · Option · Hole N"
                val teeInfoList = mutableListOf<TeeInfo>()
                var pending = nearbyCourses.size
                for (course in nearbyCourses) {
                    viewModel.getUsedTeeHoles(course.id) { _ ->
                        // We actually need the points themselves; re-use ViewModel helper
                    }
                }

                // Simpler: collect courses and let user pick; then pick hole
                val courseLabels = nearbyCourses.map { "${it.name} · ${it.option}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Course")
                    .setItems(courseLabels) { _, idx ->
                        val selectedCourse = nearbyCourses[idx]
                        showHolePickerForCourse(selectedCourse.id, selectedCourse.name, selectedCourse.option)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
                val labels = sortedHoles.map { "Hole $it" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("$courseName · $courseOption")
                    .setItems(labels) { _, idx ->
                        val holeNumber = sortedHoles[idx]
                        // Find the actual CoursePoint from the ViewModel's course data
                        // We trigger a fresh nearby tee check which will auto-select, but
                        // the user explicitly chose here so we call selectTee directly
                        fetchAndSelectTee(courseId, courseName, courseOption, holeNumber)
                    }
                    .setNegativeButton("Back") { _, _ -> showTeeSelectionDialog() }
                    .show()
            }
        }
    }

    private fun fetchAndSelectTee(courseId: Long, courseName: String, courseOption: String, holeNumber: Int) {
        // The ViewModel has nearbyTee/selectedTee state. We need to find the specific
        // CoursePoint. We'll do a location-based search narrowed by courseId + holeNumber
        // via the repository (accessed through ViewModel helpers).
        // Simplest: trigger the ViewModel to select this tee by constructing a TeeInfo
        // from the data we already have. We need the lat/lng though – use a broad bounding box.
        val loc = lastKnownLocation
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0

        // Use a 5 km box to find the tee (we already know it's in this course)
        viewModel.getCoursesNearLocation(lat, lng) { _ ->
            // We need access to CourseRepository.getPointsByType, which isn't directly
            // exposed through ViewModel. Instead, expose via a dedicated function.
            viewModel.findTeePoint(courseId, holeNumber) { teeInfo ->
                runOnUiThread {
                    if (teeInfo != null) {
                        viewModel.selectTee(teeInfo)
                        Toast.makeText(this, "Tee selected: ${teeInfo.label}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not load tee point.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── Save-as-tee dialog ────────────────────────────────────────────────────

    private fun showSaveTeeDialog(lat: Double, lng: Double) {
        var capturedBearing: Double? = null

        val dialogView = layoutInflater.inflate(R.layout.dialog_save_tee, null)
        val actvName         = dialogView.findViewById<AutoCompleteTextView>(R.id.actvCourseName)
        val actvOption       = dialogView.findViewById<AutoCompleteTextView>(R.id.actvCourseOption)
        val actvHole         = dialogView.findViewById<AutoCompleteTextView>(R.id.actvHoleNumber)
        val tvHint           = dialogView.findViewById<TextView>(R.id.tvUsedHolesHint)
        val tvBearingDisplay = dialogView.findViewById<TextView>(R.id.tvBearingDisplay)
        val btnCapture       = dialogView.findViewById<android.view.View>(R.id.btnCaptureBearing)

        btnCapture.setOnClickListener {
            if (currentCompassBearing.isNaN()) {
                Toast.makeText(this, "Compass not ready – point phone toward the basket.", Toast.LENGTH_SHORT).show()
            } else {
                capturedBearing = currentCompassBearing.toDouble()
                tvBearingDisplay.text = "%.0f° %s".format(currentCompassBearing, bearingToCardinal(currentCompassBearing))
                tvBearingDisplay.setTextColor(getColor(R.color.accuracy_good))
            }
        }

        // Pre-fill dropdowns from existing data
        viewModel.getCourseNamesAndOptions { names, options ->
            runOnUiThread {
                val nameAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
                actvName.setAdapter(nameAdapter)

                val optionSuggestions = options.ifEmpty { listOf("Short", "Long", "Pro", "Recreational") }
                val optAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, optionSuggestions)
                actvOption.setAdapter(optAdapter)
                actvOption.setText(viewModel.getLastCourseOption(), false)
            }
        }

        // Default hole list (all 1–18 available until we know the course)
        fun refreshHoles(courseName: String, courseOption: String) {
            if (courseName.isBlank() || courseOption.isBlank()) {
                setupHoleDropdown(actvHole, tvHint, emptyList(), null)
                return
            }
            viewModel.getOrFindCourseId(courseName, courseOption) { courseId ->
                if (courseId == null) {
                    runOnUiThread { setupHoleDropdown(actvHole, tvHint, emptyList(), null) }
                    return@getOrFindCourseId
                }
                viewModel.getUsedTeeHoles(courseId) { used ->
                    runOnUiThread { setupHoleDropdown(actvHole, tvHint, used, courseId) }
                }
            }
        }

        // Refresh available holes whenever course/option changes
        actvName.setOnItemClickListener { _, _, _, _ ->
            refreshHoles(actvName.text.toString(), actvOption.text.toString())
        }
        actvOption.setOnItemClickListener { _, _, _, _ ->
            refreshHoles(actvName.text.toString(), actvOption.text.toString())
        }
        // Also on focus-loss for typed (non-dropdown) entries
        actvName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) refreshHoles(actvName.text.toString(), actvOption.text.toString())
        }
        actvOption.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) refreshHoles(actvName.text.toString(), actvOption.text.toString())
        }

        // Pre-populate nearest course if within ~500 m
        viewModel.getCoursesNearLocation(lat, lng) { nearby ->
            runOnUiThread {
                if (nearby.isNotEmpty() && actvName.text.isBlank()) {
                    actvName.setText(nearby[0].name, false)
                    actvOption.setText(nearby[0].option, false)
                    refreshHoles(nearby[0].name, nearby[0].option)
                } else {
                    setupHoleDropdown(actvHole, tvHint, emptyList(), null)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Save as Fixed Tee")
            .setView(dialogView)
            .setPositiveButton("Save", null)   // Override below to validate
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name    = actvName.text.toString().trim()
                val option  = actvOption.text.toString().trim()
                val holeStr = actvHole.text.toString().trim()
                // dropdown text is "Hole 7" – extract the integer
                val hole = holeStr.removePrefix("Hole ").trim().toIntOrNull()

                when {
                    name.isBlank() -> {
                        actvName.error = "Course name required"
                    }
                    option.isBlank() -> {
                        actvOption.error = "Layout / option required"
                    }
                    hole == null || hole < 1 || hole > 18 -> {
                        actvHole.error = "Select a hole (1–18)"
                    }
                    else -> {
                        dialog.dismiss()
                        viewModel.saveTeePoint(lat, lng, name, option, hole, capturedBearing) { teeInfo ->
                            runOnUiThread {
                                if (teeInfo != null) {
                                    Toast.makeText(
                                        this,
                                        "Tee saved: ${teeInfo.label}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    // Hide the save button since the point is now saved
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

    /** Populate the hole-number AutoCompleteTextView, greying out already-used holes. */
    private fun setupHoleDropdown(
        actv: AutoCompleteTextView,
        tvHint: TextView,
        usedHoles: List<Int>,
        courseId: Long?
    ) {
        val available = (1..18).filter { it !in usedHoles }
        val all = (1..18).map { hole ->
            if (hole in usedHoles) "Hole $hole (taken)" else "Hole $hole"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, all)
        actv.setAdapter(adapter)

        if (usedHoles.isNotEmpty()) {
            tvHint.text = "Holes already saved: ${usedHoles.sorted().joinToString(", ")}"
            tvHint.visibility = View.VISIBLE
        } else {
            tvHint.visibility = View.GONE
        }

        // Pre-select first available hole
        val first = available.firstOrNull()
        if (first != null) actv.setText("Hole $first", false)

        // Disable tapping on "taken" entries
        actv.setOnItemClickListener { _, _, position, _ ->
            val selected = all[position]
            if (selected.endsWith("(taken)")) {
                actv.setText(actv.text, false)  // revert to whatever was set
                Toast.makeText(actv.context, "That hole already has a tee saved.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Disc selection ────────────────────────────────────────────────────────

    private fun showDiscSelection(loc: Location) {
        pendingEndLocation = loc

        val view = layoutInflater.inflate(R.layout.dialog_select_disc, null)
        val rv       = view.findViewById<RecyclerView>(R.id.rvSelectDiscs)
        val tvEmpty  = view.findViewById<TextView>(R.id.tvNoDiscs)
        val btnNone  = view.findViewById<Button>(R.id.btnNoDisc)
        val btnAdd   = view.findViewById<Button>(R.id.btnAddNewDisc)

        val adapter = DiscSelectAdapter { disc ->
            discSelectionDialog?.dismiss()
            viewModel.recordEnd(loc, disc.id)
        }
        discSelectAdapter = adapter
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val discs = viewModel.allDiscs.value ?: emptyList()
        adapter.submitList(discs)
        tvEmpty.visibility = if (discs.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility      = if (discs.isEmpty()) View.GONE   else View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Disc")
            .setView(view)
            .create()

        discSelectionDialog = dialog

        btnNone.setOnClickListener {
            dialog.dismiss()
            viewModel.recordEnd(loc, null)
        }

        btnAdd.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, DiscManagerActivity::class.java))
        }

        dialog.setOnDismissListener {
            discSelectionDialog = null
            discSelectAdapter = null
            pendingEndLocation = null
        }

        dialog.show()
    }

    // ── Post-throw dialog ─────────────────────────────────────────────────────

    private fun showThrowResult(discThrow: DiscThrow) {
        val distFt = discThrow.distanceFeet
        val distM  = discThrow.distanceMeters
        val isPB = viewModel.lastThrowIsPersonalBest.value == true
        val pbLine = if (isPB) "New personal best!\n\n" else ""
        val discLine = discThrow.discId?.let { id ->
            viewModel.allDiscs.value?.find { it.id == id }?.let { "\nDisc: ${it.name}" } ?: ""
        } ?: ""
        val msg = pbLine + "Distance: %.1f ft  (%.1f m)\nThrow #%d in session %s%s".format(
            distFt, distM, discThrow.throwNumber, discThrow.sessionId, discLine
        )

        AlertDialog.Builder(this)
            .setTitle(if (isPB) "Throw Recorded! ★" else "Throw Recorded!")
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
