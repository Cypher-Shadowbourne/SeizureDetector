package com.pbess.qrphotostudioultra.seizuredetector

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import android.util.Log

class DataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("DataLayer", "onDataChanged")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("DataLayer", "onMessageReceived: ${messageEvent.path}")
    }
}
