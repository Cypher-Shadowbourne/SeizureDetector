package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoneStatePublisher(context: Context) {

    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)

    suspend fun sendMonitoringState(
        isMonitoring: Boolean,
        isAlertPending: Boolean = false,
        alertCountdown: Int = 0
    ) {
        val req = PutDataMapRequest.create(STATE_PATH).apply {
            dataMap.putBoolean(KEY_IS_MONITORING, isMonitoring)
            dataMap.putBoolean(KEY_IS_ALERT_PENDING, isAlertPending)
            dataMap.putInt(KEY_ALERT_COUNTDOWN, alertCountdown)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            dataMap.putString(KEY_SOURCE, "phone")
        }
        Log.d(TAG, "Publishing monitoring state: $isMonitoring path=$STATE_PATH")
        try {
            withContext(Dispatchers.IO) {
                Tasks.await(dataClient.putDataItem(req.asPutDataRequest().setUrgent()))
            }
            Log.d(TAG, "DataItem put success path=$STATE_PATH")
        } catch (e: Exception) {
            Log.e(TAG, "DataItem put failure path=$STATE_PATH", e)
        }
    }

    companion object {
        private const val TAG = "PhoneStatePublisher"
        const val STATE_PATH = "/state"
        const val KEY_IS_MONITORING = "isMonitoring"
        const val KEY_IS_ALERT_PENDING = "isAlertPending"
        const val KEY_ALERT_COUNTDOWN = "alertCountdown"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_SOURCE = "source"
    }
}
