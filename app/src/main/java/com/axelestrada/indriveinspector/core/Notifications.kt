package com.axelestrada.indriveinspector.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.axelestrada.indriveinspector.MainActivity
import com.axelestrada.indriveinspector.overlay.StopAllReceiver

/**
 * Una única notificación para los tres servicios (captura, OCR y widget).
 *
 * Para que el sistema muestre una sola, los tres usan el mismo canal y el
 * mismo ID de notificación: así comparten la misma entrada, en lugar de crear
 * tres.
 */
object Notifications {

    const val CHANNEL_ID = "indrive_inspector"
    const val NOTIFICATION_ID = 4711

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "InDrive Inspector",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Widget de rentabilidad sobre inDrive"
            setShowBadge(false)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, StopAllReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("InDrive Inspector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                stop
            )
            .build()
    }
}
