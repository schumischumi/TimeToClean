package com.example.timetoclean

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
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
        private const val FINISHED_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.example.timetoclean.STOP"
        private const val ACTION_DISMISS_ALARM = "com.example.timetoclean.DISMISS_ALARM"
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

        fun stopTimerService(context: Context) {
            val intent =
                Intent(context, TimerService::class.java).apply {
                    action = ACTION_STOP
                }
            context.startService(intent)
        }
    }

    private var timer: CountDownTimer? = null
    private var ringtone: Ringtone? = null
    private var timeLeftMillis by Delegates.observable(0L) { _, _, newValue ->
        updateNotification(newValue)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        try {
            createNotificationChannel()
            // Start foreground immediately with a default notification
            startForeground(NOTIFICATION_ID, buildDefaultNotification())
            Log.d(TAG, "Foreground service started with default notification")
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

        when (intent?.action) {
            ACTION_STOP -> {
                stopTimer()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> {
                stopAlarmSound()
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.cancel(FINISHED_NOTIFICATION_ID)
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val totalMillis = intent?.getLongExtra(EXTRA_TIME_MILLIS, 0L) ?: 0L
                Log.d(TAG, "Received totalMillis: $totalMillis")
                if (totalMillis > 0) {
                    timeLeftMillis = totalMillis
                    updateNotification(totalMillis) // Update to show correct time
                    startTimer(totalMillis)
                } else {
                    Log.w(TAG, "Invalid totalMillis ($totalMillis), stopping service")
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startTimer(totalMillis: Long) {
        Log.d(TAG, "Starting timer for $totalMillis ms")
        timer?.cancel()
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
                }
            }.start()
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
        stopAlarmSound()
        Log.d(TAG, "Timer stopped")
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

            val finishedChannel =
                NotificationChannel(
                    FINISHED_CHANNEL_ID,
                    "Timer Finished",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Channel for Timer Finished alerts"
                    setBypassDnd(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(finishedChannel)
            Log.d(TAG, "Notification channels created")
        }
    }

    private fun showTimerFinishedNotification() {
        val dismissIntent =
            Intent(this, TimerService::class.java).apply {
                action = ACTION_DISMISS_ALARM
            }
        val dismissPendingIntent =
            PendingIntent.getService(
                this,
                0,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(this, FINISHED_CHANNEL_ID)
                .setContentTitle("Timer Finished!")
                .setContentText("Your timer has ended.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                .addAction(android.R.drawable.ic_delete, "Dismiss", dismissPendingIntent)
                .setAutoCancel(true)
                .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(FINISHED_NOTIFICATION_ID, notification)
        Log.d(TAG, "Timer finished notification shown")
    }

    private fun playNotificationSound() {
        try {
            val soundUri =
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            soundUri?.let {
                ringtone = RingtoneManager.getRingtone(applicationContext, it)
                ringtone?.audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ringtone?.play()
                Log.d(TAG, "Playing notification sound")
            } ?: Log.w(TAG, "No default sound URI found to play.")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing notification sound", e)
        }
    }

    private fun stopAlarmSound() {
        ringtone?.stop()
        ringtone = null
        Log.d(TAG, "Alarm sound stopped")
    }

    private fun buildDefaultNotification(): Notification {
        val stopIntent =
            Intent(this, TimerService::class.java).apply {
                action = ACTION_STOP
            }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Timer Starting")
            .setContentText("Preparing timer...")
            .setSmallIcon(R.drawable.ic_timer)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildNotification(timeLeft: Long): Notification {
        val stopIntent =
            Intent(this, TimerService::class.java).apply {
                action = ACTION_STOP
            }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val minutes = (timeLeft / 1000) / 60
        val seconds = (timeLeft / 1000) % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)
        Log.d(TAG, "Building notification with time: $timeString")

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Timer Running")
            .setContentText("Time remaining: $timeString")
            .setSmallIcon(R.drawable.ic_timer)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        stopTimer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
