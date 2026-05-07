package com.pbess.qrphotostudioultra.seizuredetector

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

/**
 * Foreground service that owns the phone-side alert lifecycle:
 * countdown, vibration, SMS dispatch, and cancellation.
 *
 * Safety-critical path works when the phone app is backgrounded.
 * The UI binds to this service and observes [state] to render alert UI.
 */
class PhoneAlertService : Service() {

    // ── State ──────────────────────────────────────────────────────────────

    data class ServiceState(
        val isAlertActive: Boolean = false,
        val eventId: String? = null,
        val countdown: Int = COUNTDOWN_SECONDS,
        val isMonitoring: Boolean = false
    )

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): PhoneAlertService = this@PhoneAlertService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownJob: Job? = null
    private lateinit var vibrationController: PhoneVibrationController
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var repository: EventRepository? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val database = EventDatabase.getDatabase(this)
            repository = EventRepository(database.eventDao())
            Log.d(EVENT_FLOW_TAG, "PhoneAlertService ready with EventRepository")
        } catch (e: Exception) {
            Log.e(TAG, "Event history disabled: Room init failed", e)
            repository = null
        }
        vibrationController = PhoneVibrationController(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification("Monitoring active"))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALERT -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return START_STICKY
                startAlert(eventId)
            }
            ACTION_CANCEL_ALERT -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
                val cancelSource = intent.getStringExtra(EXTRA_CANCEL_SOURCE) ?: CANCEL_SOURCE_UNKNOWN
                cancelAlert(eventId, cancelSource)
            }
            ACTION_SET_MONITORING -> {
                val monitoring = intent.getBooleanExtra(EXTRA_IS_MONITORING, false)
                _state.update { it.copy(isMonitoring = monitoring) }
                updateNotification()
                if (!monitoring && !_state.value.isAlertActive) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        serviceScope.cancel()
        vibrationController.stopVibration()
        super.onDestroy()
    }

    // ── Public API (called from binder or companion statics) ───────────────

    fun startAlert(eventId: String) {
        Log.d(EVENT_FLOW_TAG, "handleTriggerAlert eventId=$eventId")
        val current = _state.value
        if (current.isAlertActive && current.eventId == eventId) {
            Log.d(EVENT_FLOW_TAG, "duplicate alert ignored eventId=$eventId")
            return
        }
        if (current.isAlertActive) {
            Log.w(EVENT_FLOW_TAG, "alert already active current=${current.eventId} ignoring=$eventId")
            return
        }
        Log.d(EVENT_FLOW_TAG, "starting alert eventId=$eventId")
        _state.update { it.copy(isAlertActive = true, eventId = eventId, countdown = COUNTDOWN_SECONDS) }
        serviceScope.launch {
            repository?.createDetectedEvent(
                eventId = eventId,
                detectedAt = System.currentTimeMillis(),
                detectedBy = "watch",
                countdownDurationSeconds = COUNTDOWN_SECONDS
            )
            if (repository == null) {
                Log.e(EVENT_FLOW_TAG, "createDetectedEvent skipped repository=null eventId=$eventId")
            }
        }
        updateNotification()
        startCountdown()
    }

    fun cancelAlert(eventId: String?, cancelSource: String = CANCEL_SOURCE_UNKNOWN) {
        val current = _state.value
        if (!current.isAlertActive) return
        if (eventId != null && current.eventId != eventId) {
            Log.w(EVENT_FLOW_TAG, "cancel mismatch active=${current.eventId} received=$eventId")
            return
        }
        Log.d(EVENT_FLOW_TAG, "cancel received eventId=${current.eventId} cancelSource=$cancelSource")
        countdownJob?.cancel()
        vibrationController.stopVibration()
        current.eventId?.let { id ->
            serviceScope.launch { repository?.markCancelled(id, cancelSource) }
        }
        _state.update { it.copy(isAlertActive = false, eventId = null) }
        updateNotification()
        if (!_state.value.isMonitoring) stopSelf()
    }

    // ── Private logic ──────────────────────────────────────────────────────

    private fun startCountdown() {
        val eventId = _state.value.eventId ?: return
        Log.d(EVENT_FLOW_TAG, "countdown started eventId=$eventId")
        countdownJob?.cancel()
        vibrationController.startAlertVibration()
        countdownJob = serviceScope.launch {
            try {
                repository?.markCountdownStarted(eventId)
                var remaining = COUNTDOWN_SECONDS
                while (remaining > 0 && isActive) {
                    _state.update { it.copy(countdown = remaining) }
                    updateNotification()
                    delay(1_000L)
                    remaining--
                }
                if (isActive) {
                    dispatchSmsAlert()
                    _state.update { it.copy(isAlertActive = false, eventId = null) }
                    updateNotification()
                    if (!_state.value.isMonitoring) stopSelf()
                }
            } finally {
                vibrationController.stopVibration()
            }
        }
    }

    private suspend fun dispatchSmsAlert() {
        val eventId = _state.value.eventId ?: return
        val contacts = getContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts — SMS not sent")
            Log.d(EVENT_FLOW_TAG, "sms failed no contacts eventId=$eventId")
            repository?.markSmsFailed(eventId, FAILURE_NO_CONTACTS)
            return
        }

        Log.d(EVENT_FLOW_TAG, "sms pending eventId=$eventId")
        repository?.markSmsPending(eventId)

        // withTimeoutOrNull eliminates the Boolean race from the original implementation.
        // If location resolves within 5s the message includes it; otherwise empty string.
        var latitude: Double? = null
        var longitude: Double? = null
        val locationSuffix = withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                if (ActivityCompat.checkSelfPermission(
                        this@PhoneAlertService, android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    cont.resume("")
                    return@suspendCancellableCoroutine
                }
                val cts = CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }
                fusedLocationClient
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            latitude = location.latitude
                            longitude = location.longitude
                            serviceScope.launch {
                                repository?.updateLocation(eventId, location.latitude, location.longitude)
                            }
                            cont.resume("\n\nLocation: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}")
                        } else {
                            cont.resume("")
                        }
                    }
                    .addOnFailureListener { cont.resume("") }
            }
        } ?: ""
        val message = "Emergency: Seizure activity detected!$locationSuffix"

        val smsManager = getSmsManager() ?: run {
            Log.e(TAG, "Could not obtain SmsManager")
            repository?.markSmsFailed(eventId, FAILURE_SMS_PROVIDER)
            return
        }
        var sentCount = 0
        var lastError: String? = null
        contacts.forEach { number ->
            try {
                smsManager.sendTextMessage(number, null, message, null, null)
                Log.d(TAG, "SMS sent to $number")
                sentCount++
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed to $number", e)
                lastError = if (e is SecurityException) FAILURE_SMS_PERMISSION else FAILURE_SMS_PROVIDER
            }
        }
        if (sentCount > 0) {
            Log.d(EVENT_FLOW_TAG, "sms sent eventId=$eventId recipients=$sentCount")
            repository?.markSmsSent(eventId, sentCount)
        } else {
            val category = lastError ?: FAILURE_UNKNOWN
            Log.d(EVENT_FLOW_TAG, "sms failed eventId=$eventId failureCategory=$category")
            repository?.markSmsFailed(eventId, category)
        }
    }

    private suspend fun fetchLocationString(): String =
        suspendCancellableCoroutine { cont ->
            if (ActivityCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cont.resume("")
                return@suspendCancellableCoroutine
            }
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    val suffix = if (location != null)
                        "\n\nLocation: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                    else ""
                    cont.resume(suffix)
                }
                .addOnFailureListener { cont.resume("") }
        }

    private fun getContacts(): Set<String> =
        getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
            .getStringSet("numbers", emptySet()) ?: emptySet()

    private fun getSmsManager(): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get SmsManager", e)
        null
    }

    // ── Notifications ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Seizure Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Seizure detection alerts"
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Seizure Detector")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (_state.value.isAlertActive) builder.setFullScreenIntent(pi, true)
        return builder.build()
    }

    private fun updateNotification() {
        val current = _state.value
        val content = when {
            current.isAlertActive -> "ALERT IN ${current.countdown}s — tap to open"
            current.isMonitoring -> "Monitoring active"
            else -> "Ready"
        }
        startForegroundCompat(buildNotification(content))
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    // ── Companion (static helpers for callers) ─────────────────────────────

    companion object {
        private const val TAG = "PhoneAlertService"
        private const val EVENT_FLOW_TAG = "EventFlow"
        private const val NOTIFICATION_ID = 10
        const val CHANNEL_ID = "seizure_alert_channel"
        const val COUNTDOWN_SECONDS = 20

        const val ACTION_START_ALERT = "com.pbess.seizuredetector.START_ALERT"
        const val ACTION_CANCEL_ALERT = "com.pbess.seizuredetector.CANCEL_ALERT"
        const val ACTION_SET_MONITORING = "com.pbess.seizuredetector.SET_MONITORING"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_IS_MONITORING = "extra_is_monitoring"
        const val EXTRA_CANCEL_SOURCE = "extra_cancel_source"

        const val CANCEL_SOURCE_PHONE = "user_phone"
        const val CANCEL_SOURCE_WATCH = "user_watch"
        const val CANCEL_SOURCE_SYSTEM = "system"
        const val CANCEL_SOURCE_UNKNOWN = "unknown"

        const val FAILURE_NO_CONTACTS = "NO_CONTACTS"
        const val FAILURE_SMS_PERMISSION = "SMS_PERMISSION"
        const val FAILURE_SMS_PROVIDER = "SMS_PROVIDER"
        const val FAILURE_UNKNOWN = "UNKNOWN"

        fun startAlert(context: Context, eventId: String) {
            val intent = Intent(context, PhoneAlertService::class.java).apply {
                action = ACTION_START_ALERT
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            startCompat(context, intent)
        }

        fun cancelAlert(
            context: Context,
            eventId: String? = null,
            cancelSource: String = CANCEL_SOURCE_PHONE
        ) {
            val intent = Intent(context, PhoneAlertService::class.java).apply {
                action = ACTION_CANCEL_ALERT
                eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
                putExtra(EXTRA_CANCEL_SOURCE, cancelSource)
            }
            context.startService(intent)
        }

        fun setMonitoring(context: Context, monitoring: Boolean) {
            val intent = Intent(context, PhoneAlertService::class.java).apply {
                action = ACTION_SET_MONITORING
                putExtra(EXTRA_IS_MONITORING, monitoring)
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
