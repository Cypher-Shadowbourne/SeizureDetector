package com.pbess.qrphotostudioultra.seizuredetector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.compose.ui.res.stringResource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pbess.qrphotostudioultra.seizuredetector.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            SeizureDetectorTheme {
                MainScreen(sensorManager = sensorManager)
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    sensorManager: SensorManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vibrationController = remember(context.applicationContext) {
        PhoneVibrationController(context.applicationContext)
    }
    val statePublisher = remember(context.applicationContext) {
        PhoneStatePublisher(context.applicationContext)
    }

    // ── Monitoring session toggle (owned by UI) ───────────────────────────
    var isMonitoring by remember { mutableStateOf(false) }

    // ── Phone sensor display values (display only — detection is watch-side) ─
    var accelX by remember { mutableFloatStateOf(0f) }
    var accelY by remember { mutableFloatStateOf(0f) }
    var accelZ by remember { mutableFloatStateOf(0f) }
    var magnitude by remember { mutableFloatStateOf(0f) }
    var gyroMagnitude by remember { mutableFloatStateOf(0f) }
    var heartRate by remember { mutableFloatStateOf(0f) }
    var pressure by remember { mutableFloatStateOf(0f) }
    var magMagnitude by remember { mutableFloatStateOf(0f) }
    var showMonitor by remember { mutableStateOf(false) }

    // ── Watch sensor display ──────────────────────────────────────────────
    var wearMagnitude by remember { mutableFloatStateOf(0f) }
    var wearGyroMagnitude by remember { mutableFloatStateOf(0f) }
    var wearHeartRate by remember { mutableFloatStateOf(0f) }
    var wearPressure by remember { mutableFloatStateOf(0f) }
    var wearMagMagnitude by remember { mutableFloatStateOf(0f) }

    // ── Alert state — sourced from PhoneAlertService, not computed here ───
    var alertService by remember { mutableStateOf<PhoneAlertService?>(null) }
    var serviceState by remember { mutableStateOf(PhoneAlertService.ServiceState()) }

    val isAlertPending = serviceState.isAlertActive
    val alertCountdown = serviceState.countdown

    // ── Contact management ────────────────────────────────────────────────
    val sharedPrefs = remember { context.getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE) }
    var contactList by remember {
        mutableStateOf(sharedPrefs.getStringSet("numbers", emptySet())?.toSet() ?: emptySet())
    }
    var newNumber by remember { mutableStateOf("") }

    fun saveContacts(newSet: Set<String>) {
        contactList = newSet
        sharedPrefs.edit { putStringSet("numbers", newSet) }
    }

    val dataClient = remember { Wearable.getDataClient(context) }
    val eventRepository = remember(context.applicationContext) {
        runCatching {
            EventRepository(EventDatabase.getDatabase(context.applicationContext).eventDao())
        }
            .onFailure { android.util.Log.e("EventFlow", "Failed to init EventRepository in MainActivity", it) }
            .getOrNull()
    }
    val eventHistory by (eventRepository?.observeRecentEvents(50) ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    // ── Bind to PhoneAlertService to observe alert state ─────────────────
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                alertService = (service as? PhoneAlertService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                alertService = null
            }
        }
        context.bindService(
            Intent(context, PhoneAlertService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose { context.unbindService(connection) }
    }

    // Collect service StateFlow into Compose state
    LaunchedEffect(alertService) {
        alertService?.state?.collectLatest { state ->
            serviceState = state
        }
    }

    // Keep UI monitoring state aligned with service-owned monitoring state.
    LaunchedEffect(serviceState.isMonitoring) {
        if (isMonitoring != serviceState.isMonitoring) {
            isMonitoring = serviceState.isMonitoring
        }
    }

    // ── Sync monitoring + alert state to watch ────────────────────────────
    LaunchedEffect(isMonitoring, isAlertPending, alertCountdown) {
        statePublisher.sendMonitoringState(
            isMonitoring = isMonitoring,
            isAlertPending = isAlertPending,
            alertCountdown = alertCountdown
        )
    }

    // ── Receive watch sensor display data ─────────────────────────────────
    DisposableEffect(Unit) {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == "/wear_sensor_data"
                ) {
                    try {
                        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                        wearMagnitude = map.getFloat("magnitude")
                        wearGyroMagnitude = map.getFloat("gyroMagnitude")
                        wearHeartRate = map.getFloat("heartRate")
                        wearPressure = map.getFloat("pressure")
                        wearMagMagnitude = map.getFloat("magnetometerMagnitude")
                    } catch (e: Exception) {
                        android.util.Log.e("WearSync", "Error parsing watch sensor data", e)
                    }
                }
            }
        }
        dataClient.addListener(listener)
        onDispose { dataClient.removeListener(listener) }
    }

    // ── Phone display sensors ─────────────────────────────────────────────
    // These update the Live Monitor panel only. No detection logic here.
    DisposableEffect(isMonitoring) {
        var accelListener: SensorEventListener? = null
        var gyroListener: SensorEventListener? = null
        var heartListener: SensorEventListener? = null
        var pressureListener: SensorEventListener? = null
        var magListener: SensorEventListener? = null

        if (isMonitoring) {
            val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val heartSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            accelSensor?.let { sensor ->
                accelListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.size >= 3) {
                            accelX = event.values[0]; accelY = event.values[1]; accelZ = event.values[2]
                            val raw = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
                            magnitude = if (sensor.type == Sensor.TYPE_ACCELEROMETER)
                                (raw - SensorManager.GRAVITY_EARTH).coerceAtLeast(0f) else raw
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            gyroSensor?.let { sensor ->
                gyroListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.size >= 3) {
                            gyroMagnitude = sqrt(
                                event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                            )
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            if (context.checkSelfPermission(android.Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                heartSensor?.let { sensor ->
                    heartListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            if (event.values.isNotEmpty()) heartRate = event.values[0]
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                    }
                    sensorManager.registerListener(heartListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }

            pressureSensor?.let { sensor ->
                pressureListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isNotEmpty()) pressure = event.values[0]
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            magSensor?.let { sensor ->
                magListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.size >= 3) {
                            magMagnitude = sqrt(
                                event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                            )
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(magListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        onDispose {
            accelListener?.let { sensorManager.unregisterListener(it) }
            gyroListener?.let { sensorManager.unregisterListener(it) }
            heartListener?.let { sensorManager.unregisterListener(it) }
            pressureListener?.let { sensorManager.unregisterListener(it) }
            magListener?.let { sensorManager.unregisterListener(it) }
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[android.Manifest.permission.SEND_SMS] ?: false
        val locationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (smsGranted) {
            isMonitoring = true
            PhoneAlertService.setMonitoring(context, true)
            if (!locationGranted) {
                Toast.makeText(context, "Location denied — alerts will not include position.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "SMS permission required to start monitoring.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AccentPrimary.copy(alpha = 0.15f), Color.Transparent),
                    center = center.copy(x = size.width * 0.8f, y = size.height * 0.2f),
                    radius = size.minDimension * 0.6f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AccentTertiary.copy(alpha = 0.1f), Color.Transparent),
                    center = center.copy(x = size.width * 0.2f, y = size.height * 0.8f),
                    radius = size.minDimension * 0.8f
                )
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SEIZURE\nDETECTOR",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 32.dp)
            )

            // ── Status card ───────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusText = when {
                        isAlertPending -> "⚠️ ALERT PENDING"
                        isMonitoring -> "ACTIVE MONITORING"
                        else -> "SYSTEM IDLE"
                    }
                    val statusColor = when {
                        isAlertPending -> ErrorColor
                        isMonitoring -> SuccessColor
                        else -> Color.Gray
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor, fontWeight = FontWeight.Bold)
                    }

                    // Battery optimisation prompt
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            Spacer(modifier = Modifier.height(16.dp))
                            GlassCard(borderColor = AccentPrimary.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.battery_optimization_title), style = MaterialTheme.typography.labelMedium, color = AccentPrimary, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.battery_optimization_desc), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
                                    SexyButton(
                                        text = stringResource(R.string.battery_optimization_button),
                                        onClick = {
                                            try {
                                                context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = android.net.Uri.parse("package:${context.packageName}")
                                                })
                                            } catch (e: Exception) {
                                                android.util.Log.e("BatteryOpt", "Failed", e)
                                            }
                                        },
                                        brush = Brush.horizontalGradient(listOf(AccentPrimary, AccentSecondary)),
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isMonitoring || isAlertPending) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                GraduatedBar(magnitude, 20f, "PHONE ACCEL", AccentPrimary, Modifier.weight(1f))
                                GraduatedBar(magMagnitude, 100f, "PHONE MAG", AccentTertiary, Modifier.weight(1f))
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                GraduatedBar(wearMagnitude, 20f, "WATCH ACCEL", AccentSecondary, Modifier.weight(1f))
                                GraduatedBar(wearMagMagnitude, 100f, "WATCH MAG", AccentTertiary, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Ready to detect seizure activity", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Alert card ────────────────────────────────────────────────
            AnimatedVisibility(visible = isAlertPending, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), borderColor = ErrorColor) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("EMERGENCY ALERT", style = MaterialTheme.typography.titleLarge, color = ErrorColor)
                        Text("Sending SMS in $alertCountdown seconds", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        SexyButton(
                            text = "CANCEL ALERT",
                            onClick = {
                                PhoneAlertService.cancelAlert(context)
                                Toast.makeText(context, "Alert Cancelled", Toast.LENGTH_SHORT).show()
                            },
                            brush = AlertGradient
                        )
                    }
                }
            }

            // ── Contacts ──────────────────────────────────────────────────
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Emergency Contacts", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    if (!isMonitoring) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newNumber,
                                onValueChange = { if (it.length <= 15) newNumber = it },
                                placeholder = { Text("Add Phone Number", color = Color.Gray) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = AccentPrimary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val trimmed = newNumber.trim()
                                    if (trimmed.isNotBlank()) { saveContacts(contactList + trimmed); newNumber = "" }
                                },
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(AccentPrimary)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    contactList.forEach { number ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(number, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            if (!isMonitoring) {
                                IconButton(onClick = { saveContacts(contactList - number) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorColor, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    if (contactList.isEmpty()) {
                        Text("No contacts added yet.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showMonitor = !showMonitor }) {
                    Text(if (showMonitor) "Hide Monitor" else "Show Monitor", color = AccentPrimary)
                }
            }

            if (showMonitor) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Live Monitor", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Handheld", fontWeight = FontWeight.Bold, color = AccentPrimary)
                                Spacer(modifier = Modifier.height(8.dp))
                                GraduatedBar(magnitude, 20f, "Accel", AccentPrimary)
                                Spacer(modifier = Modifier.height(8.dp))
                                GraduatedBar(magMagnitude, 100f, "Mag", AccentTertiary)
                                Spacer(modifier = Modifier.height(8.dp))
                                MonitorRow("Gyro", "%.2f".format(gyroMagnitude.toDouble()))
                                MonitorRow("Heart", "%.0f".format(heartRate.toDouble()))
                                MonitorRow("Pres", "%.1f".format(pressure.toDouble()))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Watch", fontWeight = FontWeight.Bold, color = AccentSecondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                GraduatedBar(wearMagnitude, 20f, "Accel", AccentSecondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                GraduatedBar(wearMagMagnitude, 100f, "Mag", AccentTertiary)
                                Spacer(modifier = Modifier.height(8.dp))
                                MonitorRow("Gyro", "%.2f".format(wearGyroMagnitude.toDouble()))
                                MonitorRow("Heart", "%.0f".format(wearHeartRate.toDouble()))
                                MonitorRow("Pres", "%.1f".format(wearPressure.toDouble()))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Start / Stop ──────────────────────────────────────────────
            SexyButton(
                text = if (isMonitoring) "STOP MONITORING" else "START MONITORING",
                onClick = {
                    if (!isMonitoring) {
                        if (contactList.isEmpty()) {
                            Toast.makeText(context, "Please add at least one contact.", Toast.LENGTH_SHORT).show()
                            return@SexyButton
                        }
                        val permissions = mutableListOf(
                            android.Manifest.permission.SEND_SMS,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) {
                            permissions.add(android.Manifest.permission.BODY_SENSORS)
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    } else {
                        isMonitoring = false
                        PhoneAlertService.cancelAlert(context)
                        PhoneAlertService.setMonitoring(context, false)
                    }
                },
                brush = if (isMonitoring) AlertGradient else PrimaryGradient
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    vibrationController.testVibration()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Text("TEST VIBRATION", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Event History ──────────────────────────────────────────────
            Text(
                "RECENT EVENTS",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (eventHistory.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No events recorded yet", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                eventHistory.forEach { event ->
                    EventHistoryCard(
                        event = event,
                        onSaveReview = { eventId, reviewStatus, eventCategory, userNotes, injuryOccurred, medicationTaken, emergencyServicesContacted, recoveryDurationMinutes ->
                            val safeRecoveryDuration = recoveryDurationMinutes?.takeIf { it >= 0 }
                            scope.launch {
                                eventRepository?.markReviewed(
                                    eventId = eventId,
                                    reviewStatus = reviewStatus,
                                    eventCategory = eventCategory,
                                    userNotes = userNotes,
                                    injuryOccurred = injuryOccurred,
                                    medicationTaken = medicationTaken,
                                    emergencyServicesContacted = emergencyServicesContacted,
                                    recoveryDurationMinutes = safeRecoveryDuration
                                )
                            }
                            if (recoveryDurationMinutes != null && safeRecoveryDuration == null) {
                                Toast.makeText(context, "Recovery duration must be empty or >= 0", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Review saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClearReview = { eventId ->
                            scope.launch { eventRepository?.clearReview(eventId) }
                            Toast.makeText(context, "Review cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ── Shared UI components ──────────────────────────────────────────────────────

@Composable
fun GraduatedBar(value: Float, max: Float, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
            Text("%.1f".format(value), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (value / max).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun MonitorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, borderColor: Color = Color.White.copy(alpha = 0.1f), content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, borderColor, RoundedCornerShape(24.dp)),
        color = Color.White.copy(alpha = 0.05f),
        content = content
    )
}

@Composable
fun SexyButton(text: String, onClick: () -> Unit, brush: Brush, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(brush),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues()
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun EventHistoryCard(
    event: EventEntity,
    onSaveReview: (
        eventId: String,
        reviewStatus: String,
        eventCategory: String?,
        userNotes: String?,
        injuryOccurred: Boolean?,
        medicationTaken: Boolean?,
        emergencyServicesContacted: Boolean?,
        recoveryDurationMinutes: Int?
    ) -> Unit,
    onClearReview: (eventId: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var reviewStatus by remember(event.eventId, event.reviewStatus) {
        mutableStateOf(event.reviewStatus ?: "NOT_REVIEWED")
    }
    var eventCategory by remember(event.eventId, event.eventCategory) {
        mutableStateOf(event.eventCategory)
    }
    var injuryOccurred by remember(event.eventId, event.injuryOccurred) {
        mutableStateOf(event.injuryOccurred)
    }
    var medicationTaken by remember(event.eventId, event.medicationTaken) {
        mutableStateOf(event.medicationTaken)
    }
    var emergencyServicesContacted by remember(event.eventId, event.emergencyServicesContacted) {
        mutableStateOf(event.emergencyServicesContacted)
    }
    var recoveryDurationInput by remember(event.eventId, event.recoveryDurationMinutes) {
        mutableStateOf(event.recoveryDurationMinutes?.toString() ?: "")
    }
    var userNotes by remember(event.eventId, event.userNotes) {
        mutableStateOf(event.userNotes ?: "")
    }
    val timeStr = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.detectedAt))

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
        borderColor = when (event.alertState) {
            AlertState.SMS_SENT -> Color.Green.copy(alpha = 0.3f)
            AlertState.CANCELLED_BY_USER -> Color.Gray.copy(alpha = 0.3f)
            AlertState.SMS_FAILED -> Color.Red.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.1f)
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Event: ${event.alertState.toUiLabel()}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))

                DetailRow("ID", event.eventId)
                DetailRow("Source", event.detectedBy)
                if (event.resolvedAt != null) {
                    val resolvedStr = java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(event.resolvedAt))
                    DetailRow("Resolved", resolvedStr)
                }
                if (event.alertState == AlertState.CANCELLED_BY_USER && event.cancelSource != null) {
                    DetailRow("Cancelled by", event.cancelSource.toUiCancelSourceLabel())
                }
                if (!event.reviewStatus.isNullOrBlank()) {
                    DetailRow("Review", event.reviewStatus.toUiReviewStatusLabel())
                }
                if (!event.eventCategory.isNullOrBlank()) {
                    DetailRow("Category", event.eventCategory.toUiEventCategoryLabel())
                }
                if (event.smsRecipientCount > 0) {
                    DetailRow("SMS Recipients", event.smsRecipientCount.toString())
                }
                if (event.smsSendResult != null && event.smsSendResult != "SUCCESS") {
                    DetailRow("Error", event.smsSendResult)
                }
                if (event.alertState == AlertState.SMS_FAILED && event.failureCategory != null) {
                    DetailRow("Failure", event.failureCategory)
                }
                if (event.locationIncluded && event.latitude != null && event.longitude != null) {
                    DetailRow("Location", "Available")
                    Text(
                        text = "View on Map",
                        color = AccentSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                // Implicit intent could be added here
                            }
                    )
                }
                if (event.reviewedAt != null) {
                    val reviewedStr = java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(event.reviewedAt))
                    DetailRow("Reviewed", reviewedStr)
                }
                if (event.recoveryDurationMinutes != null) {
                    DetailRow("Recovery", "${event.recoveryDurationMinutes} min")
                }
                if (!event.userNotes.isNullOrBlank()) {
                    DetailRow("Notes", event.userNotes)
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Post-event review",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Review status", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                ReviewStatusOptions(
                    selected = reviewStatus,
                    onSelected = { selected ->
                        reviewStatus = selected
                        if (selected == "UNSURE" && eventCategory == null) {
                            eventCategory = "UNKNOWN"
                        }
                        if (selected == "FALSE_ALARM" && eventCategory == "UNKNOWN") {
                            eventCategory = "OTHER"
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Event category", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                EventCategoryOptions(
                    selected = eventCategory,
                    onSelected = { eventCategory = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                ReviewCheckboxRow("Injury occurred", injuryOccurred == true) { injuryOccurred = it }
                ReviewCheckboxRow("Medication taken", medicationTaken == true) { medicationTaken = it }
                ReviewCheckboxRow("Emergency services contacted", emergencyServicesContacted == true) { emergencyServicesContacted = it }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = recoveryDurationInput,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.all { it.isDigit() }) {
                            recoveryDurationInput = value
                        }
                    },
                    label = { Text("Recovery duration (minutes)") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = AccentPrimary,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Optional") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = AccentPrimary,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val parsedRecovery = recoveryDurationInput.toIntOrNull()
                            val normalizedCategory = when {
                                reviewStatus == "UNSURE" && eventCategory == null -> "UNKNOWN"
                                reviewStatus == "FALSE_ALARM" && eventCategory == "UNKNOWN" -> "OTHER"
                                else -> eventCategory
                            }
                            onSaveReview(
                                event.eventId,
                                reviewStatus,
                                normalizedCategory,
                                userNotes.takeIf { it.isNotBlank() },
                                injuryOccurred,
                                medicationTaken,
                                emergencyServicesContacted,
                                parsedRecovery
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save review")
                    }
                    OutlinedButton(
                        onClick = {
                            reviewStatus = "NOT_REVIEWED"
                            eventCategory = null
                            userNotes = ""
                            injuryOccurred = null
                            medicationTaken = null
                            emergencyServicesContacted = null
                            recoveryDurationInput = ""
                            onClearReview(event.eventId)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear review")
                    }
                }

                Text(
                    text = "This is a recorded event summary. No medical diagnosis is implied.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(top = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ReviewStatusOptions(selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReviewOptionRow(
            label = "Reviewed as real event",
            selected = selected == "REAL_EVENT",
            onClick = { onSelected("REAL_EVENT") }
        )
        ReviewOptionRow(
            label = "Marked false alarm",
            selected = selected == "FALSE_ALARM",
            onClick = { onSelected("FALSE_ALARM") }
        )
        ReviewOptionRow(
            label = "Unsure",
            selected = selected == "UNSURE",
            onClick = { onSelected("UNSURE") }
        )
    }
}

@Composable
private fun EventCategoryOptions(selected: String?, onSelected: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EventCategoryOption("POSSIBLE_SEIZURE", "Possible seizure", selected, onSelected)
        EventCategoryOption("FALL_OR_IMPACT", "Fall or impact", selected, onSelected)
        EventCategoryOption("TREMOR_OR_SHAKING", "Tremor or shaking", selected, onSelected)
        EventCategoryOption("EXERCISE_OR_MOVEMENT", "Exercise or movement", selected, onSelected)
        EventCategoryOption("SLEEP_MOVEMENT", "Sleep movement", selected, onSelected)
        EventCategoryOption("OTHER", "Other", selected, onSelected)
    }
}

@Composable
private fun EventCategoryOption(
    value: String,
    label: String,
    selected: String?,
    onSelected: (String?) -> Unit
) {
    ReviewOptionRow(
        label = label,
        selected = selected == value,
        onClick = { onSelected(value) }
    )
}

@Composable
private fun ReviewOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReviewCheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun AlertState.toUiLabel(): String =
    when (this) {
        AlertState.DETECTED -> "Recorded event"
        AlertState.COUNTDOWN_STARTED -> "Alert pending"
        AlertState.CANCELLED_BY_USER -> "Cancelled"
        AlertState.SMS_PENDING -> "SMS pending"
        AlertState.SMS_SENT -> "SMS sent"
        AlertState.SMS_FAILED -> "SMS failed"
        AlertState.EXPIRED -> "Expired"
        AlertState.UNKNOWN_FAILURE -> "Issue recorded"
    }

private fun String.toUiReviewStatusLabel(): String =
    when (this) {
        "REAL_EVENT" -> "Reviewed as real event"
        "FALSE_ALARM" -> "Marked false alarm"
        "UNSURE" -> "Unsure"
        else -> "Not reviewed"
    }

private fun String.toUiEventCategoryLabel(): String =
    when (this) {
        "POSSIBLE_SEIZURE" -> "Possible seizure"
        "FALL_OR_IMPACT" -> "Fall or impact"
        "TREMOR_OR_SHAKING" -> "Tremor or shaking"
        "EXERCISE_OR_MOVEMENT" -> "Exercise or movement"
        "SLEEP_MOVEMENT" -> "Sleep movement"
        "OTHER" -> "Other"
        "UNKNOWN" -> "Unknown"
        else -> "Unknown"
    }

private fun String.toUiCancelSourceLabel(): String =
    when (this) {
        "user_phone" -> "Phone user"
        "user_watch" -> "Watch user"
        "system" -> "System"
        else -> "Unknown"
    }

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SeizureDetectorTheme {}
}
