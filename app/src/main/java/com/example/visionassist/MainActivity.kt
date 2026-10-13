package com.example.visionassist

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.visionassist.databinding.ActivityMainBinding
import com.example.visionassist.db.AppDatabase
import com.example.visionassist.db.ObstacleEntity
import com.example.visionassist.net.NominatimApi
import com.example.visionassist.net.OsrmApi
import com.example.visionassist.vision.VisionObstacleDetector
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var vibrator: Vibrator

    private lateinit var fused: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private lateinit var db: AppDatabase
    private val warnedObstacleIds = HashSet<Long>()

    // hotkeys
    private var lastVolUpMs = 0L
    private var lastVolDownMs = 0L
    private val DOUBLE_PRESS_MS = 650L

    // navigation
    private var navActive = false
    private var destLat: Double? = null
    private var destLon: Double? = null
    private var maneuvers: List<OsrmApi.Maneuver> = emptyList()
    private var nextManIndex = 0
    private var lastBucketFeet = -1
    private var lastTurnSpeakAtMs = 0L
    private var routePoints: List<Pair<Double, Double>> = emptyList()
    private var lastRerouteAtMs = 0L

    // SOS
    private val prefs by lazy { getSharedPreferences("visionassist_prefs", MODE_PRIVATE) }
    private val PREF_SOS_NUMBER = "sos_number" // "91xxxxxxxxxx"

    // Camera
    private var cameraOn = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var visionDetector: VisionObstacleDetector? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val favoritesLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val name = res.data!!.getStringExtra("fav_name") ?: return@registerForActivityResult
                val lat = res.data!!.getDoubleExtra("fav_lat", 0.0)
                val lon = res.data!!.getDoubleExtra("fav_lon", 0.0)

                // Start nav using coordinates
                startNavigationByLatLon(name, lat, lon)
            }
        }
    private fun startNavigationByLatLon(placeName: String, lat: Double, lon: Double) {
        val myLat = lastLat ?: run { speak("Location not ready"); return }
        val myLon = lastLon ?: run { speak("Location not ready"); return }

        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { com.example.visionassist.net.OsrmApi.routeWalk(myLat, myLon, lat, lon) }
            if (route == null) {
                speak("Route not available"); vibrateShort(); return@launch
            }

            destLat = lat
            destLon = lon
            navActive = true
            maneuvers = route.maneuvers
            nextManIndex = 0
            lastBucketFeet = -1
            lastTurnSpeakAtMs = 0L
            routePoints = route.pointsLatLon
            jsDrawRoute(routePoints)

            val totalFeet = metersToFeet(route.totalDistanceMeters)
            speak("Navigating to $placeName. Total distance $totalFeet feet")
            vibrateShort()
        }
    }
    private fun openFavorites() {
        val i = Intent(this, FavoritesActivity::class.java)
        favoritesLauncher.launch(i)
    }

    // Speech launcher
    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isBlank()) {
                speak("Command not recognized"); vibrateShort()
            } else {
                handleVoiceCommand(spoken.lowercase(Locale.getDefault()))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ prevents crash if binding IDs mismatch (still better to match XML)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Toast.makeText(this, "Layout binding crash: check activity_main.xml IDs", Toast.LENGTH_LONG).show()
            Log.e("VisionAssist", "Binding crash", e)
            finish()
            return
        }

        db = AppDatabase.getInstance(this)

        tts = TextToSpeech(this, this)
        vibrator = getSystemService(Vibrator::class.java)

        fused = LocationServices.getFusedLocationProviderClient(this)

        setupWebView()

        binding.btnVoice.setOnClickListener { oneStepVoice() }
        binding.btnSaveObstacle.setOnClickListener { saveObstacle("manual") }
        binding.btnSos.setOnClickListener { sendSOS() }
        binding.btnCamera.setOnClickListener { if (cameraOn) stopCamera() else startCamera() }

        requestPermissionsIfNeeded()
        ensureSosContactOnFirstLaunch()
    }

    override fun onStart() {
        super.onStart()
        startLiveLocationIfPermitted()
    }

    override fun onStop() {
        stopLiveLocation()
        stopCamera()
        super.onStop()
    }

    override fun onDestroy() {
        stopLiveLocation()
        stopCamera()
        cameraExecutor.shutdown()
        if (ttsReady) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    // ---------------- WebView ----------------
    private fun setupWebView() {
        val w = binding.mapWebView
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        val s: WebSettings = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        w.loadUrl("file:///android_asset/map.html")
    }

    private fun jsSetUser(lat: Double, lon: Double) {
        binding.mapWebView.evaluateJavascript("setUserLocation($lat,$lon);", null)
    }

    private fun jsDrawRoute(points: List<Pair<Double, Double>>) {
        val arr = points.joinToString(prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
        binding.mapWebView.evaluateJavascript("drawRoute($arr);", null)
    }

    private fun jsClearRoute() {
        binding.mapWebView.evaluateJavascript("clearRoute();", null)
    }

    private fun jsRenderObstacles(list: List<ObstacleEntity>) {
        binding.mapWebView.evaluateJavascript("clearObstacles();", null)
        list.forEach {
            val safeLabel = it.label.replace("'", "")
            binding.mapWebView.evaluateJavascript(
                "addObstacle(${it.id},${it.lat},${it.lon},'$safeLabel');",
                null
            )
        }
    }

    // ---------------- Permissions ----------------
    private fun requestPermissionsIfNeeded() {
        val need = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.CAMERA)

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 900)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLiveLocationIfPermitted()
    }

    private fun startLiveLocationIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        startLiveLocation()
    }

    // ---------------- Live location ----------------
    @Suppress("MissingPermission")
    private fun startLiveLocation() {
        stopLiveLocation()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1200L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLat = loc.latitude
                lastLon = loc.longitude

                jsSetUser(loc.latitude, loc.longitude)

                lifecycleScope.launch { refreshObstacles() }
                lifecycleScope.launch { warnIfNearObstacle(loc.latitude, loc.longitude) }

                if (navActive) {
                    lifecycleScope.launch { navigationTick(loc.latitude, loc.longitude) }
                }
            }
        }

        fused.requestLocationUpdates(request, locationCallback as LocationCallback, mainLooper)
        lifecycleScope.launch { refreshObstacles() }
    }

    private fun stopLiveLocation() {
        val cb = locationCallback ?: return
        fused.removeLocationUpdates(cb)
        locationCallback = null
    }

    // ---------------- Hotkeys ----------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val delta = now - lastVolUpMs
                lastVolUpMs = now
                if (delta in 1..DOUBLE_PRESS_MS) oneStepVoice()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val delta = now - lastVolDownMs
                lastVolDownMs = now
                if (delta in 1..DOUBLE_PRESS_MS) sendSOS()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Voice ----------------
    private fun oneStepVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Microphone permission required")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 901)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech not available")
            Toast.makeText(this, "Speech recognizer not available", Toast.LENGTH_LONG).show()
            return
        }

        speak("Say command")
        vibrateShort()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say command")
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            speak("Speech service not found")
            Toast.makeText(this, "Install/enable Google app for voice input", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleVoiceCommand(cmd: String) {
        when {
            cmd.startsWith("navigate to ") || cmd.startsWith("go to ") || cmd.startsWith("direction to ") -> {
                val place = cmd.substringAfter("to ").trim()
                if (place.isBlank()) { speak("Command not recognized"); vibrateShort(); return }
                startNavigation(place)
            }
            cmd == "stop navigation" || cmd == "stop" -> stopNavigation()
            cmd == "sos" || cmd == "help" -> sendSOS()
            cmd.startsWith("save obstacle") -> saveObstacle(cmd.removePrefix("save obstacle").trim().ifBlank { "obstacle" })
            cmd.startsWith("save ") -> saveObstacle(cmd.removePrefix("save ").trim().ifBlank { "obstacle" })
            cmd.contains("delete all") -> deleteAllObstacles()
            cmd.contains("delete last") -> deleteLastObstacle()
            cmd.contains("delete nearest") -> deleteNearestObstacle()
            cmd.contains("camera on") -> startCamera()
            cmd.contains("camera off") -> stopCamera()
            cmd.contains("change sos") || cmd.contains("change contact") -> showSosSetupDialog(force = true)
            else -> { speak("Command not recognized"); vibrateShort() }
        }
    }

    // ---------------- DB Obstacles ----------------
    private fun saveObstacle(label: String) {
        val lat = lastLat ?: run { speak("Location not ready"); return }
        val lon = lastLon ?: run { speak("Location not ready"); return }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.obstacleDao().insert(ObstacleEntity(lat = lat, lon = lon, label = label))
            }
            refreshObstacles()
            speak("Obstacle saved")
            vibrateShort()
        }
    }

    private fun deleteAllObstacles() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.obstacleDao().deleteAll() }
            warnedObstacleIds.clear()
            refreshObstacles()
            speak("All obstacles deleted")
            vibrateShort()
        }
    }

    private fun deleteLastObstacle() {
        lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) { db.obstacleDao().getLast() }
            if (last == null) { speak("No obstacles"); vibrateShort(); return@launch }
            withContext(Dispatchers.IO) { db.obstacleDao().deleteById(last.id) }
            warnedObstacleIds.remove(last.id)
            refreshObstacles()
            speak("Last obstacle deleted")
            vibrateShort()
        }
    }

    private fun deleteNearestObstacle() {
        val lat = lastLat ?: return
        val lon = lastLon ?: return

        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { db.obstacleDao().getAll() }
            if (all.isEmpty()) { speak("No obstacles"); vibrateShort(); return@launch }

            var best: ObstacleEntity? = null
            var bestD = Double.MAX_VALUE
            for (o in all) {
                val d = haversineMeters(lat, lon, o.lat, o.lon)
                if (d < bestD) { bestD = d; best = o }
            }

            val nearest = best ?: return@launch
            withContext(Dispatchers.IO) { db.obstacleDao().deleteById(nearest.id) }
            warnedObstacleIds.remove(nearest.id)
            refreshObstacles()
            speak("Nearest obstacle deleted")
            vibrateShort()
        }
    }

    private suspend fun refreshObstacles() {
        val list = withContext(Dispatchers.IO) { db.obstacleDao().getAll() }
        jsRenderObstacles(list)
    }

    private suspend fun warnIfNearObstacle(lat: Double, lon: Double) {
        val obstacles = withContext(Dispatchers.IO) { db.obstacleDao().getAll() }
        val thresholdMeters = 18.0
        for (o in obstacles) {
            val d = haversineMeters(lat, lon, o.lat, o.lon)
            if (d <= thresholdMeters && !warnedObstacleIds.contains(o.id)) {
                warnedObstacleIds.add(o.id)
                speak("Obstacle ahead")
                vibrateLong()
                break
            }
            if (d > 60.0 && warnedObstacleIds.contains(o.id)) {
                warnedObstacleIds.remove(o.id)
            }
        }
    }

    // ---------------- Navigation ----------------
    private fun startNavigation(place: String) {
        val lat = lastLat
        val lon = lastLon
        if (lat == null || lon == null) { speak("Location not ready"); vibrateShort(); return }

        lifecycleScope.launch {
            val geo = withContext(Dispatchers.IO) { NominatimApi.geocode(place) }
            if (geo == null) { speak("Place not found"); vibrateShort(); return@launch }

            destLat = geo.first
            destLon = geo.second

            val route = withContext(Dispatchers.IO) { OsrmApi.routeWalk(lat, lon, geo.first, geo.second) }
            if (route == null) { speak("Route not found"); vibrateShort(); return@launch }

            navActive = true
            maneuvers = route.maneuvers
            nextManIndex = 0
            lastBucketFeet = -1
            lastTurnSpeakAtMs = 0L
            routePoints = route.pointsLatLon

            jsDrawRoute(routePoints)

            val totalFeet = metersToFeet(route.totalDistanceMeters)
            speak("Total distance $totalFeet feet")
            vibrateShort()
        }
    }

    private fun stopNavigation() {
        navActive = false
        destLat = null
        destLon = null
        maneuvers = emptyList()
        nextManIndex = 0
        lastBucketFeet = -1
        routePoints = emptyList()
        jsClearRoute()
        speak("Navigation stopped")
        vibrateShort()
    }

    private suspend fun navigationTick(lat: Double, lon: Double) {
        val dLat = destLat ?: return
        val dLon = destLon ?: return

        val remainMeters = haversineMeters(lat, lon, dLat, dLon)
        val remainFeet = metersToFeet(remainMeters)

        speakEvery10Feet(remainFeet)

        if (remainMeters < 8.0) {
            speak("Arrived")
            vibrateLong()
            stopNavigation()
            return
        }

        offRouteCheckAndReroute(lat, lon)

        if (maneuvers.isEmpty() || nextManIndex >= maneuvers.size) return

        while (nextManIndex < maneuvers.size) {
            val m = maneuvers[nextManIndex]
            val d = haversineMeters(lat, lon, m.lat, m.lon)
            if (d < 6.0 && m.type.lowercase() == "depart") nextManIndex++ else break
        }
        if (nextManIndex >= maneuvers.size) return

        val m = maneuvers[nextManIndex]
        val distToTurnM = haversineMeters(lat, lon, m.lat, m.lon)
        val distToTurnFt = metersToFeet(distToTurnM)
        val now = System.currentTimeMillis()

        if (distToTurnM in 36.0..60.0 && now - lastTurnSpeakAtMs > 5000) {
            lastTurnSpeakAtMs = now
            speak("In $distToTurnFt feet, ${prettyInstruction(m)}")
            vibrateShort()
        }

        if (distToTurnM < 18.0 && now - lastTurnSpeakAtMs > 2000) {
            lastTurnSpeakAtMs = now
            speak("Now, ${prettyInstruction(m)}")
            vibrateLong()
            nextManIndex++
        }
    }

    private fun offRouteCheckAndReroute(lat: Double, lon: Double) {
        if (routePoints.size < 2) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteAtMs < 8000) return

        val distToRouteM = distanceToPolylineMeters(lat, lon, routePoints)
        if (distToRouteM > 35.0) {
            lastRerouteAtMs = now
            val dLat = destLat ?: return
            val dLon = destLon ?: return
            lifecycleScope.launch {
                val route = withContext(Dispatchers.IO) { OsrmApi.routeWalk(lat, lon, dLat, dLon) }
                if (route != null) {
                    maneuvers = route.maneuvers
                    nextManIndex = 0
                    routePoints = route.pointsLatLon
                    jsDrawRoute(routePoints)
                    speak("Rerouting")
                    vibrateShort()
                }
            }
        }
    }

    private fun speakEvery10Feet(currentFeet: Int) {
        if (currentFeet <= 0) return
        if (lastBucketFeet == -1) {
            lastBucketFeet = (currentFeet / 10) * 10
            return
        }
        val bucket = (currentFeet / 10) * 10
        if (bucket < lastBucketFeet && bucket > 0) {
            lastBucketFeet = bucket
            speak("$bucket feet")
            vibrateShort()
        }
    }

    private fun prettyInstruction(m: OsrmApi.Maneuver): String {
        val type = m.type.lowercase()
        val mod = m.modifier?.lowercase()
        val road = m.name?.trim().orEmpty()

        val base = when (type) {
            "depart" -> "start walking"
            "arrive" -> "arrive"
            "turn" -> when (mod) {
                "left" -> "turn left"
                "right" -> "turn right"
                "slight left" -> "turn slight left"
                "slight right" -> "turn slight right"
                "sharp left" -> "turn sharp left"
                "sharp right" -> "turn sharp right"
                "uturn" -> "make a U turn"
                else -> "turn"
            }
            "continue" -> "continue straight"
            "roundabout" -> "enter roundabout"
            "merge" -> "merge"
            else -> type.replace('_', ' ')
        }

        return if (road.isNotBlank() && type != "arrive") "$base onto $road" else base
    }

    // ---------------- SOS ----------------
    private fun ensureSosContactOnFirstLaunch() {
        val saved = prefs.getString(PREF_SOS_NUMBER, null)
        if (saved.isNullOrBlank()) showSosSetupDialog(force = true)
    }

    private fun showSosSetupDialog(force: Boolean) {
        val input = EditText(this).apply {
            hint = "Enter WhatsApp number like 9198xxxxxxx"
            setText(prefs.getString(PREF_SOS_NUMBER, "") ?: "")
        }

        AlertDialog.Builder(this)
            .setTitle("SOS Contact")
            .setMessage("Enter WhatsApp number (countrycode+number, no +). Example: 919876543210")
            .setView(input)
            .setCancelable(!force)
            .setPositiveButton("Save") { _, _ ->
                val num = input.text.toString().trim()
                if (num.length < 10 || !num.all { it.isDigit() }) {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    if (force) showSosSetupDialog(true)
                } else {
                    prefs.edit().putString(PREF_SOS_NUMBER, num).apply()
                    speak("SOS contact saved")
                    vibrateShort()
                }
            }
            .setNegativeButton(if (force) "Exit" else "Cancel") { _, _ ->
                if (force) finish()
            }
            .show()
    }

    private fun sendSOS() {
        val sosNum = prefs.getString(PREF_SOS_NUMBER, null)
        if (sosNum.isNullOrBlank()) {
            showSosSetupDialog(force = true)
            return
        }

        val lat = lastLat
        val lon = lastLon

        val msg = if (lat != null && lon != null) {
            "🚨 SOS! I need help.\nMy location: https://maps.google.com/?q=$lat,$lon"
        } else {
            "🚨 SOS! I need help.\nLocation unavailable."
        }

        val uri = Uri.parse("https://wa.me/$sosNum?text=" + Uri.encode(msg))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
            vibrateLong()
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Camera ----------------
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Camera permission required")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 902)
            return
        }

        binding.cameraPreview.visibility = View.VISIBLE
        cameraOn = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val detector = VisionObstacleDetector(this) { _ ->
                speak("Obstacle ahead")
                vibrateLong()
            }.also {
                // ✅ You can tune “distance” behavior here
                // iQOO 7: try 0.22f (warn closer), 0.15f (warn earlier)
                it.dangerBoxAreaRatio = 0.20f
            }

            visionDetector = detector

            val analysis = detector.analysisUseCase()

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraOn = false
        binding.cameraPreview.visibility = View.GONE
        cameraProvider?.unbindAll()
    }

    // ---------------- TTS + Vibrate ----------------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale("en", "IN")
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VA_TTS")
    }

    private fun vibrateShort() {
        try {
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(60)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateLong() {
        try {
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(140)
            }
        } catch (_: Exception) {}
    }

    // ---------------- Math helpers ----------------
    private fun metersToFeet(meters: Double): Int = (meters * 3.28084).roundToInt()

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    private fun distanceToPolylineMeters(lat: Double, lon: Double, points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = distancePointToSegmentMeters(lat, lon, a.first, a.second, b.first, b.second)
            if (d < best) best = d
        }
        return best
    }

    private fun distancePointToSegmentMeters(
        lat: Double, lon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val x = (lon - lon1) * cos(Math.toRadians((lat + lat1) / 2))
        val y = (lat - lat1)

        val x2 = (lon2 - lon1) * cos(Math.toRadians((lat2 + lat1) / 2))
        val y2 = (lat2 - lat1)

        val dot = x * x2 + y * y2
        val len2 = x2 * x2 + y2 * y2
        val t = if (len2 == 0.0) 0.0 else (dot / len2).coerceIn(0.0, 1.0)

        val projX = x2 * t
        val projY = y2 * t

        val dx = x - projX
        val dy = y - projY

        val metersPerDegLat = 111320.0
        val metersPerDegLon = 111320.0 * cos(Math.toRadians(lat))
        val mx = dx * metersPerDegLon
        val my = dy * metersPerDegLat

        return sqrt(mx * mx + my * my)
    }
}
