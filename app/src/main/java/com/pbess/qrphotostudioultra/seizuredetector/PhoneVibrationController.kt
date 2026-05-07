package com.pbess.qrphotostudioultra.seizuredetector

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

class PhoneVibrationController(context: Context) {

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val audioAttributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    fun testVibration() {
        Log.d(TAG, "test vibration requested")
        if (!canVibrate()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(TEST_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(TEST_DURATION_MS)
            }
            Log.d(TAG, "vibration effect started: test")
        } catch (se: SecurityException) {
            Log.e(TAG, "permission/security exception starting test vibration", se)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected error starting test vibration", e)
        }
    }

    fun startAlertVibration() {
        Log.d(TAG, "start alert vibration requested")
        if (!canVibrate()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(ALERT_PATTERN, 0)
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ALERT_PATTERN, 0)
            }
            Log.d(TAG, "vibration effect started: alert")
        } catch (se: SecurityException) {
            Log.e(TAG, "permission/security exception starting alert vibration", se)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected error starting alert vibration", e)
        }
    }

    fun stopVibration() {
        try {
            vibrator?.cancel()
            Log.d(TAG, "vibration cancelled")
        } catch (se: SecurityException) {
            Log.e(TAG, "permission/security exception stopping vibration", se)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected error stopping vibration", e)
        }
    }

    private fun canVibrate(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.VIBRATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "vibration unavailable: missing android.permission.VIBRATE")
            return false
        }

        val available = vibrator?.hasVibrator() == true
        if (!available) {
            Log.w(TAG, "vibration unavailable: device reports no vibrator")
            return false
        }

        Log.d(TAG, "vibrator available")
        return true
    }

    companion object {
        private const val TAG = "PhoneVibrationController"
        private const val TEST_DURATION_MS = 500L
        private val ALERT_PATTERN = longArrayOf(0L, 500L, 500L)
    }
}
