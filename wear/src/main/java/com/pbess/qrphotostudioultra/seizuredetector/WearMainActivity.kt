package com.pbess.qrphotostudioultra.seizuredetector.wear

import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    var isMonitoring by remember { mutableStateOf(false) }
    var isAlertPending by remember { mutableStateOf(false) }
    var alertCountdown by remember { mutableIntStateOf(20) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataClient = remember { Wearable.getDataClient(context) }
    val messageClient = remember { Wearable.getMessageClient(context) }

    fun applyState(map: DataMap) {
        val monitoring = map.getBoolean("isMonitoring")
        val pending = map.getBoolean("isAlertPending")
        val countdown = map.getInt("alertCountdown")
        Log.d(TAG, "Parsed isMonitoring=$monitoring")
        isMonitoring = monitoring
        isAlertPending = pending
        alertCountdown = countdown
        Log.d(TAG, "Updating phone status text")
    }

    LaunchedEffect(Unit) {
        try {
            val buffer = withContext(Dispatchers.IO) { Tasks.await(dataClient.dataItems) }
            try {
                var found = false
                buffer.forEach { item ->
                    if (item.uri.path == "/state") {
                        found = true
                        Log.d(TAG, "Received DataItem path=/state (startup)")
                        applyState(DataMapItem.fromDataItem(item).dataMap)
                    }
                }
                if (!found) {
                    Log.d(TAG, "No persisted /state DataItem found at startup")
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading startup /state DataItem", e)
        }
    }

    // Sync state from phone — monitoring toggle, alert state, countdown
    DisposableEffect(Unit) {
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/state") {
                    try {
                        Log.d(TAG, "Received DataItem path=/state")
                        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                        applyState(map)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse /state", e)
                    }
                }
            }
        }
        dataClient.addListener(listener)
        onDispose { dataClient.removeListener(listener) }
    }

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            modifier = Modifier.background(
                if (isAlertPending) MaterialTheme.colors.error
                else MaterialTheme.colors.background
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
                                    // Include the eventId so the phone can deduplicate
                                    val eventId = ""
                                    val payload = eventId.toByteArray(Charsets.UTF_8)
                                    try {
                                        val nodes = withContext(Dispatchers.IO) {
                                            Tasks.await(
                                                Wearable.getNodeClient(context).connectedNodes
                                            )
                                        }
                                        nodes.forEach { node ->
                                            messageClient.sendMessage(
                                                node.id, "/cancel_alert", payload
                                            )
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("WearApp", "Failed to send cancel", e)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.surface
                            )
                        ) {
                            Text("CANCEL", color = MaterialTheme.colors.onSurface)
                        }
                    } else {
                        Text(
                            text = if (isMonitoring) "Monitoring..." else "Phone Idle",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private const val TAG = "WatchStateReceiver"
