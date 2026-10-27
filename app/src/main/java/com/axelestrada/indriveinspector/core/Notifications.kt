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
    const val ACTION_START_FROM_NOTIFICATION =
        "com.axelestrada.indriveinspector.action.START_FROM_NOTIFICATION"

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

    fun build(context: Context, text: String, active: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val action = if (active) {
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, StopAllReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java).apply {
                    this.action = ACTION_START_FROM_NOTIFICATION
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("InDrive Inspector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(active)
            .setContentIntent(open)
            .addAction(
                if (active) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_media_play,
                if (active) "Desactivar" else "Activar",
                action
            )
            .build()
    }

    /** Mantiene a mano el control de activación cuando el inspector está parado. */
    fun showStopped(context: Context) {
        createChannel(context)
        val notification = build(
            context,
            "Servicio detenido · toca Activar para iniciarlo",
            active = false
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
