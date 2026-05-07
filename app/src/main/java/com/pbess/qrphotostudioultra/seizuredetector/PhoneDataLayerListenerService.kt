package com.pbess.qrphotostudioultra.seizuredetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.util.UUID

/**
 * Receives Wearable messages from the watch even when the phone app is not running.
 * Delegates to [PhoneAlertService] to handle all state and safety logic.
 *
 * This is the only component on the phone side that must always be available
 * to receive watch-triggered events in the background.
 */
class PhoneDataLayerListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(EVENT_FLOW_TAG, "received path=${messageEvent.path} payloadBytes=${messageEvent.data.size}")
        when (messageEvent.path) {
            "/trigger_alert" -> {
                val eventId = messageEvent.data
                    .takeIf { it.isNotEmpty() }
                    ?.toString(Charsets.UTF_8)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: UUID.randomUUID().toString()
                Log.d(EVENT_FLOW_TAG, "received /trigger_alert eventId=$eventId")
                
                // On Android 12+, starting a foreground service from background is restricted.
                // We show a high-priority notification to ensure the user is alerted,
                // and starting the service from here is allowed because WearableListenerService
                // is an exempted background component for specific intents.
                PhoneAlertService.startAlert(this, eventId)
            }
            "/cancel_alert" -> {
                val eventId = messageEvent.data
                    .takeIf { it.isNotEmpty() }
                    ?.toString(Charsets.UTF_8)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                Log.d(EVENT_FLOW_TAG, "received /cancel_alert eventId=$eventId")
                PhoneAlertService.cancelAlert(
                    this,
                    eventId,
                    PhoneAlertService.CANCEL_SOURCE_WATCH
                )
            }
            else -> Log.d(EVENT_FLOW_TAG, "unhandled message path=${messageEvent.path}")
        }
    }

    companion object {
        private const val EVENT_FLOW_TAG = "EventFlow"
    }
}
