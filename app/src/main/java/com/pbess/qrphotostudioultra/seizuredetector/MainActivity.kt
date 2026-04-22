package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.os.VibratorManager
import android.telephony.SmsManager
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pbess.qrphotostudioultra.seizuredetector.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        setContent {
            SeizureDetectorTheme {
                MainScreen(
                    sensorManager = sensorManager,
                    vibrator = vibrator
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    sensorManager: SensorManager,
    vibrator: Vibrator
) {
    var isMonitoring by remember { mutableStateOf(false) }
    var accelX by remember { mutableFloatStateOf(0f) }
    var accelY by remember { mutableFloatStateOf(0f) }
    var accelZ by remember { mutableFloatStateOf(0f) }
    var magnitude by remember { mutableFloatStateOf(0f) }
    var peakMagnitude by remember { mutableFloatStateOf(0f) }
    var highMovementDetected by remember { mutableStateOf(false) }
    var highMovementDuration by remember { mutableFloatStateOf(0f) }
    var isAlertPending by remember { mutableStateOf(false) }
    var alertCountdown by remember { mutableIntStateOf(20) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Wear Data Layer Sync
    val dataClient = remember { Wearable.getDataClient(context) }
    val messageClient = remember { Wearable.getMessageClient(context) }

    LaunchedEffect(isMonitoring, isAlertPending, alertCountdown) {
        val putDataMapReq = PutDataMapRequest.create("/state").apply {
            dataMap.putBoolean("isMonitoring", isMonitoring)
            dataMap.putBoolean("isAlertPending", isAlertPending)
            dataMap.putInt("alertCountdown", alertCountdown)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        try {
            withContext(Dispatchers.IO) {
                Tasks.await(dataClient.putDataItem(putDataReq))
            }
        } catch (e: Exception) {
            android.util.Log.e("WearSync", "Failed to sync state to wear", e)
        }
    }

    DisposableEffect(Unit) {
        val messageListener = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { messageEvent ->
            if (messageEvent.path == "/cancel_alert") {
                isAlertPending = false
            }
        }
        messageClient.addListener(messageListener)
        onDispose {
            messageClient.removeListener(messageListener)
        }
    }

    val sharedPrefs = remember { context.getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE) }
    var contactList by remember { 
        mutableStateOf(sharedPrefs.getStringSet("numbers", emptySet())?.toSet() ?: emptySet()) 
    }
    var newNumber by remember { mutableStateOf("") }

    fun saveContacts(newSet: Set<String>) {
        contactList = newSet
        sharedPrefs.edit { putStringSet("numbers", newSet) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[android.Manifest.permission.SEND_SMS] ?: false
        val locationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        
        if (smsGranted) {
            isMonitoring = true
            if (!locationGranted) {
                Toast.makeText(context, "Location permission denied. Alerts will not include position.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "SMS Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendSmsAlerts() {
        if (contactList.isNotEmpty()) {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { location ->
                        val locationMsg = if (location != null) {
                            "\n\nLocation: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                        } else ""
                        
                        contactList.forEach { number ->
                            try {
                                smsManager.sendTextMessage(number, null, "Emergency: Seizure activity detected!$locationMsg", null, null)
                            } catch (e: Exception) {
                                android.util.Log.e("SmsAlert", "Failed to send SMS to $number", e)
                            }
                        }
                        Toast.makeText(context, "Alert Sent ${if (location != null) "with location " else ""}to ${contactList.size} contacts", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        contactList.forEach { number ->
                            smsManager.sendTextMessage(number, null, "Emergency: Seizure activity detected!", null, null)
                        }
                        Toast.makeText(context, "Alert Sent (location fetch failed) to ${contactList.size} contacts", Toast.LENGTH_SHORT).show()
                    }
            } else {
                contactList.forEach { number ->
                    try {
                        smsManager.sendTextMessage(number, null, "Emergency: Seizure activity detected!", null, null)
                    } catch (e: Exception) {
                        android.util.Log.e("SmsAlert", "Failed to send SMS to $number", e)
                    }
                }
                Toast.makeText(context, "Alert Sent to ${contactList.size} contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var lastHighTime by remember { mutableLongStateOf(0L) }
    var lastMovementTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isAlertPending) {
        if (isAlertPending) {
            alertCountdown = 20
            while (alertCountdown > 0) {
                delay(1000)
                alertCountdown--
            }
            sendSmsAlerts()
            isAlertPending = false
        }
    }

    LaunchedEffect(highMovementDetected) {
        if (highMovementDetected) {
            delay(3000)
            highMovementDetected = false
        }
    }

    DisposableEffect(isMonitoring) {
        var listener: SensorEventListener? = null

        if (isMonitoring) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) 
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            if (sensor != null) {
                listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        accelX = event.values[0]
                        accelY = event.values[1]
                        accelZ = event.values[2]

                        val rawMagnitude = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
                        magnitude = if (sensor.type == Sensor.TYPE_ACCELEROMETER) {
                            (rawMagnitude - 9.8f).coerceAtLeast(0f)
                        } else {
                            rawMagnitude
                        }

                        if (magnitude > peakMagnitude) peakMagnitude = magnitude
                        val now = System.currentTimeMillis()

                        if (magnitude > 15f) {
                            lastMovementTime = now
                            if (lastHighTime == 0L) lastHighTime = now
                            highMovementDuration = (now - lastHighTime) / 1000f

                            if (highMovementDuration > 0.2f && !highMovementDetected) {
                                highMovementDetected = true
                                if (!isAlertPending) {
                                    isAlertPending = true
                                }
                                val effect = VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE)
                                val attributes = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                                
                                try {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(effect, attributes)
                                } catch (e: Exception) {
                                    // Fallback for older devices or failures
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(1000)
                                }
                            }
                        } else {
                            if (now - lastMovementTime > 150) {
                                lastHighTime = 0L
                                highMovementDuration = 0f
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }

        onDispose {
            listener?.let { sensorManager.unregisterListener(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Decorative background elements
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

            // Status Card
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusText = if (isAlertPending) "⚠️ ALERT PENDING" 
                                    else if (isMonitoring) "ACTIVE MONITORING" 
                                    else "SYSTEM IDLE"
                    val statusColor = if (isAlertPending) ErrorColor 
                                     else if (isMonitoring) SuccessColor 
                                     else Color.Gray

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isMonitoring || isAlertPending) {
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { (magnitude / 20f).coerceIn(0f, 1f) },
                                modifier = Modifier.size(180.dp),
                                color = if (magnitude > 15f) ErrorColor else AccentPrimary,
                                strokeWidth = 8.dp,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeCap = StrokeCap.Round
                            )
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "%.1f".format(magnitude),
                                    style = MaterialTheme.typography.displayLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = "MAGNITUDE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Peak: %.1f".format(peakMagnitude),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Ready to detect seizure activity",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Alert Section
            AnimatedVisibility(
                visible = isAlertPending,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    borderColor = ErrorColor
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "EMERGENCY ALERT",
                            style = MaterialTheme.typography.titleLarge,
                            color = ErrorColor
                        )
                        Text(
                            text = "Sending SMS in $alertCountdown seconds",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        SexyButton(
                            text = "CANCEL ALERT",
                            onClick = { 
                                isAlertPending = false
                                Toast.makeText(context, "Alert Cancelled", Toast.LENGTH_SHORT).show()
                            },
                            brush = AlertGradient
                        )
                    }
                }
            }

            // Contacts Section
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Emergency Contacts", 
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
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
                                    if (trimmed.isNotBlank()) {
                                        saveContacts(contactList + trimmed)
                                        newNumber = ""
                                    }
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AccentPrimary)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    contactList.forEach { number ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(number, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            if (!isMonitoring) {
                                IconButton(
                                    onClick = { saveContacts(contactList - number) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorColor, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    
                    if (contactList.isEmpty()) {
                        Text(
                            "No contacts added yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Action Buttons
            SexyButton(
                text = if (isMonitoring) "STOP MONITORING" else "START MONITORING",
                onClick = {
                    if (!isMonitoring) {
                        if (contactList.isEmpty()) {
                            Toast.makeText(context, "Please add at least one contact", Toast.LENGTH_SHORT).show()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.SEND_SMS,
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    } else {
                        isMonitoring = false
                        isAlertPending = false
                    }
                },
                brush = if (isMonitoring) AlertGradient else PrimaryGradient
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    try {
                        if (vibrator.hasVibrator()) {
                            val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                            val attributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(effect, attributes)
                        } else {
                            Toast.makeText(context, "No vibrator detected", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        // Fallback for older devices or if VibrationEffect fails
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(500)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Text("TEST VIBRATION", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Color.White.copy(alpha = 0.1f),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp)),
        color = Color.White.copy(alpha = 0.05f),
        content = content
    )
}

@Composable
fun SexyButton(
    text: String,
    onClick: () -> Unit,
    brush: Brush,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brush),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SeizureDetectorTheme {
        // Mock data for preview
    }
}