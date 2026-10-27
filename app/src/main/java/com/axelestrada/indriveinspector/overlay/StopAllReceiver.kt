package com.axelestrada.indriveinspector.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.axelestrada.indriveinspector.capture.ScreenCaptureService
import com.axelestrada.indriveinspector.core.InspectorRepository
import com.axelestrada.indriveinspector.core.Notifications
import com.axelestrada.indriveinspector.ocr.OcrWatchService

/**
 * Detiene todo desde el botón de la notificación del sistema.
 */
class StopAllReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
            context.stopService(Intent(context, OcrWatchService::class.java))
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        } catch (_: Exception) {
            // nada que hacer
        }
        // La misma notificación queda disponible para volver a activar el servicio.
        try {
            Notifications.showStopped(context)
        } catch (_: Exception) {
            // nada que hacer
        }
        InspectorRepository.setServicesActive(false)
    }
}
