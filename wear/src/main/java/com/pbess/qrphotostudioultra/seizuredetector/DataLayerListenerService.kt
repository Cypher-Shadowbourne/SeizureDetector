package com.pbess.qrphotostudioultra.seizuredetector.wear

import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives Data Layer changes from the phone and controls [SensorMonitoringService].
 *
 * The phone publishes a "/state" data item whenever monitoring or alert state changes.
 * This service starts or stops [SensorMonitoringService] accordingly, so watch-side
 * monitoring responds to phone-side control even when [WearMainActivity] is not running.
 */
class DataLayerListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path ?: return@forEach
            Log.d(TAG, "onDataChanged path=$path")

            if (path == "/state") {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val isMonitoring = dataMap.getBoolean("isMonitoring", false)
                    val isAlertPending = dataMap.getBoolean("isAlertPending", false)

                    if (isMonitoring) {
                        Log.d(TAG, "Phone monitoring ON — starting SensorMonitoringService")
                        SensorMonitoringService.start(this)
                    } else {
                        Log.d(TAG, "Phone monitoring OFF — stopping SensorMonitoringService")
                        SensorMonitoringService.stop(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse /state data", e)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived path=${messageEvent.path}")
        // Phone-originated messages are not expected on the watch side in Phase 0.
        // The watch sends messages; the phone's PhoneDataLayerListenerService handles replies.
    }

    companion object {
        private const val TAG = "WearDataLayerSvc"
    }
}
