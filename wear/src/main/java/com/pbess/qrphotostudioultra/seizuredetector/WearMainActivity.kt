package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class WearMainActivity : ComponentActivity() {
    private lateinit var sensorManager: SensorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setContent {
            WearApp(sensorManager)
        }
    }
}

@Composable
fun WearApp(sensorManager: SensorManager) {
    var isMonitoring by remember { mutableStateOf(false) }
    var isAlertPending by remember { mutableStateOf(false) }
    var alertCountdown by remember { mutableIntStateOf(20) }
    var magnitude by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataClient = remember { Wearable.getDataClient(context) }
    val messageClient = remember { Wearable.getMessageClient(context) }

    // Sync state from phone
    DisposableEffect(Unit) {
        val dataListener = DataClient.OnDataChangedListener { dataEvents ->
            dataEvents.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/state") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    isMonitoring = dataMap.getBoolean("isMonitoring")
                    isAlertPending = dataMap.getBoolean("isAlertPending")
                    alertCountdown = dataMap.getInt("alertCountdown")
                }
            }
        }
        dataClient.addListener(dataListener)
        onDispose {
            dataClient.removeListener(dataListener)
        }
    }

    // Local Sensor Monitoring (Optional but good for Wear OS)
    DisposableEffect(isMonitoring) {
        var listener: SensorEventListener? = null
        if (isMonitoring) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            if (sensor != null) {
                listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val rawMagnitude = sqrt(x * x + y * y + z * z)
                        magnitude = if (sensor.type == Sensor.TYPE_ACCELEROMETER) {
                            (rawMagnitude - 9.8f).coerceAtLeast(0f)
                        } else {
                            rawMagnitude
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

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            modifier = Modifier.background(
                if (isAlertPending) MaterialTheme.colors.error else MaterialTheme.colors.background
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isAlertPending) {
                        Text(
                            text = "ALERT IN $alertCountdown",
                            color = MaterialTheme.colors.onError,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val nodes = withContext(Dispatchers.IO) {
                                            Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                                        }
                                        nodes.forEach { node ->
                                            messageClient.sendMessage(node.id, "/cancel_alert", byteArrayOf())
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("WearApp", "Failed to send cancel", e)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface)
                        ) {
                            Text("CANCEL", color = MaterialTheme.colors.onSurface)
                        }
                    } else {
                        Text(
                            text = if (isMonitoring) "Monitoring..." else "Phone Idle",
                            fontSize = 14.sp
                        )
                        if (isMonitoring) {
                            Text(
                                text = "%.1f".format(magnitude),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
