package com.example.timetoclean

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.properties.Delegates

class TimerService : Service() {
    companion object {
        private const val TAG = "TimerService"
        private const val CHANNEL_ID = "TimerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val FINISHED_CHANNEL_ID = "TimerFinishedChannel"
        private const val FINISHED_NOTIFICATION_ID = 2 // Use a
        const val EXTRA_TIME_MILLIS = "extra_time_millis"

        fun startTimerService(
            context: Context,
            totalMillis: Long,
        ) {
            Log.d(TAG, "Starting TimerService with totalMillis: $totalMillis")
            val intent =
                Intent(context, TimerService::class.java).apply {
                    putExtra(EXTRA_TIME_MILLIS, totalMillis)
                }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                    Log.d(TAG, "Started foreground service")
                } else {
                    context.startService(intent)
                    Log.d(TAG, "Started background service")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
    }

    private var timer: CountDownTimer? = null
    private var timeLeftMillis by Delegates.observable(0L) { _, _, newValue ->
        updateNotification(newValue)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification(0L))
            Log.d(TAG, "Foreground service started with initial notification")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")
        val totalMillis = intent?.getLongExtra(EXTRA_TIME_MILLIS, 0L) ?: 0L
        Log.d(TAG, "Received totalMillis: $totalMillis")
        if (totalMillis > 0) {
            updateNotification(totalMillis)
            startTimer(totalMillis)
        } else {
            Log.w(TAG, "Invalid totalMillis ($totalMillis), stopping service")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startTimer(totalMillis: Long) {
        Log.d(TAG, "Starting timer for $totalMillis ms")
        timer?.cancel()
        timeLeftMillis = totalMillis
        timer =
            object : CountDownTimer(totalMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    timeLeftMillis = millisUntilFinished
                    Log.d(TAG, "Timer tick: $millisUntilFinished ms remaining")
                }

                override fun onFinish() {
                    timeLeftMillis = 0L
                    Log.d(TAG, "Timer finished")
                    showTimerFinishedNotification()
                    playNotificationSound()
                    stopSelf()
                }
            }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channel")
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Timer Service",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Channel for Timer Service notifications"
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")

            // Timer Finished Channel (for the alarm)
            val finishedChannel =
                NotificationChannel(
                    FINISHED_CHANNEL_ID,
                    "Timer Finished",
                    NotificationManager.IMPORTANCE_HIGH, // Use HIGH for the alarm
                ).apply {
                    description = "Channel for Timer Finished alerts"
                    // Optionally enable vibration, lights, etc.
                    // enableLights(true)
                    // lightColor = Color.RED
                    // enableVibration(true)
                    // vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                }
            manager.createNotificationChannel(finishedChannel)
            Log.d(TAG, "Timer finished notification channel created")
        }
    }

    private fun showTimerFinishedNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val finishedNotification =
            NotificationCompat
                .Builder(this, FINISHED_CHANNEL_ID)
                .setContentTitle("Timer Finished!")
                .setContentText("Your timer has ended.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // Or your custom alarm icon
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true) // Dismiss when tapped
                // .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) // Add sound
                // .setVibrate(longArrayOf(0, 1000, 500, 1000)) // Add vibration
                .build()

        notificationManager.notify(FINISHED_NOTIFICATION_ID, finishedNotification)
        Log.d(TAG, "Timer finished notification shown")
    }

    private fun buildNotification(timeLeft: Long): Notification {
        val minutes = (timeLeft / 1000) / 60
        val seconds = (timeLeft / 1000) % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)
        Log.d(TAG, "Building notification with time: $timeString")

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Timer Running")
            .setContentText("Time remaining: $timeString")
            .setSmallIcon(R.drawable.ic_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun playNotificationSound() {
        try {
            // Get the default alarm sound URI
            var soundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            if (soundUri == null) {
                // Alarm sound not found, try the default notification sound
                soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            // If a sound URI is found, play it
            soundUri?.let {
                val ringtone = RingtoneManager.getRingtone(applicationContext, it)
                ringtone.play()
                Log.d(TAG, "Playing notification sound")
            } ?: Log.w(TAG, "No default sound URI found to play.")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing notification sound", e)
        }
    }

    private fun updateNotification(timeLeft: Long) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(timeLeft))
            Log.d(TAG, "Notification updated")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        timer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
