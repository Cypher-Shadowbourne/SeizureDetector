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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.pbess.qrphotostudioultra.seizuredetector.ui.theme.SeizureDetectorTheme
import kotlinx.coroutines.delay
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        sensorManager = sensorManager,
                        vibrator = vibrator
                    )
                }
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

    val context = LocalContext.current
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
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isMonitoring = true
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

    var lastHighTime by remember { mutableLongStateOf(0L) }
    var lastMovementTime by remember { mutableLongStateOf(0L) }

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
                                sendSmsAlerts()
                                val effect = VibrationEffect.createOneShot(1000, 255)
                                val attributes = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                                vibrator.vibrate(effect, attributes)
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

    val backgroundColor = if (highMovementDetected)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Seizure Detector",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Contact Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Emergency Contacts", fontWeight = FontWeight.Bold)
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNumber,
                        onValueChange = { newNumber = it },
                        label = { Text("Add Phone Number") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isMonitoring,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newNumber.trim()
                            if (trimmed.isNotBlank()) {
                                saveContacts(contactList + trimmed)
                                newNumber = ""
                            }
                        },
                        enabled = !isMonitoring
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                contactList.forEach { number ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(number, fontSize = 18.sp)
                        IconButton(
                            onClick = { saveContacts(contactList - number) },
                            enabled = !isMonitoring
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isMonitoring)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isMonitoring) "Monitoring: ON" else "Monitoring: OFF",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )

                if (isMonitoring) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Magnitude",
                        fontSize = 18.sp
                    )
                    Text(
                        text = "%.1f".format(magnitude),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Peak: %.1f".format(peakMagnitude),
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        TextButton(onClick = { peakMagnitude = 0f }) {
                            Text("Reset")
                        }
                    }

                    if (highMovementDetected) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "⚠️ SEIZURE LIKELY!",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (!isMonitoring) {
                    if (contactList.isEmpty()) {
                        Toast.makeText(context, "Please add at least one contact", Toast.LENGTH_SHORT).show()
                    } else {
                        permissionLauncher.launch(android.Manifest.permission.SEND_SMS)
                    }
                } else {
                    isMonitoring = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMonitoring)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isMonitoring) "Stop Monitoring" else "Start Monitoring",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (vibrator.hasVibrator()) {
                    val effect = VibrationEffect.createOneShot(500, 255)
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator.vibrate(effect, attributes)
                    Toast.makeText(context, "Vibrating (Max Power)...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No vibrator detected on this device", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Test Vibration (should buzz)")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SeizureDetectorTheme {
        // Mock data for preview
    }
}