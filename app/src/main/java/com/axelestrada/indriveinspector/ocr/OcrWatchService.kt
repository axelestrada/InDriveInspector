package com.axelestrada.indriveinspector.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.axelestrada.indriveinspector.capture.ScreenCaptureService
import com.axelestrada.indriveinspector.core.InspectorRepository
import com.axelestrada.indriveinspector.core.Notifications
import com.axelestrada.indriveinspector.core.LiveOffer
import com.axelestrada.indriveinspector.core.RouteAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Analiza la pantalla de inDrive con OCR, de forma continua.
 *
 * Sustituye por completo al AccessibilityService: en lugar de esperar al árbol
 * de accesibilidad (que llega tarde), se captura la pantalla y se lee con OCR.
 *
 * Para no gastar CPU, sólo se lanza el OCR cuando la miniatura de la pantalla
 * ha cambiado de verdad.
 */
class OcrWatchService : Service() {

    companion object {
        const val TAG = "InDriveInspector"

        const val ACTION_START = "com.axelestrada.indriveinspector.action.START_OCR_WATCH"
        const val ACTION_STOP = "com.axelestrada.indriveinspector.action.STOP_OCR_WATCH"

        /** Pausa entre iteraciones del bucle. */
    private const val LOOP_DELAY = 80L
        private const val CHANGE_THRESHOLD = 0.012

        /**
         * Lecturas fallidas seguidas antes de ocultar el widget. Una lectura
         * suelta puede fallar (el mapa tapa el título un instante) y no debe
         * hacer parpadear el indicador.
         */
        private const val MISSES_TO_HIDE = 1

        fun startIntent(context: Context): Intent =
            Intent(context, OcrWatchService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, OcrWatchService::class.java).apply { action = ACTION_STOP }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var looping = false

    /** Huella de lo último leído, para no publicar si no cambió nada. */
    @Volatile
    private var lastFingerprint: String = ""

    /** Última lectura con precio (para rellenar lecturas parciales). */
    @Volatile
    private var lastGood: LiveOffer? = null

    /** Lecturas fallidas seguidas. */
    private var missCount = 0

    /** Último precio publicado, para no redibujar el widget sin cambios. */
    private var lastPublishedPrice: Double? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        InspectorRepository.loadSettings(this)
        InspectorRepository.setServicesActive(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat()

        if (!looping) {
            looping = true
            scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        looping = false
        scope.coroutineContext.cancel()
        InspectorRepository.setServicesActive(false)
        // La notificación es compartida: no la quitamos al parar este servicio
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Bucle

    private suspend fun watchLoop() {
        while (scope.isActive) {
            val capture = ScreenCaptureService.instance
            if (capture != null && capture.hasProjection() && capture.consumeChangeFlag()) {
                analyzeNow(capture)
            }
            delay(LOOP_DELAY)
        }
    }

    private suspend fun analyzeNow(capture: ScreenCaptureService) {
        val frame = capture.copyFrame() ?: return
        val start = SystemClock.elapsedRealtime()

        // El análisis va en dos fases: la mitad inferior (precio) se publica
        // al instante y la superior (mapa) completa los datos.
        val snapshot = ScreenOcr.analyze(frame) { partial -> publish(partial) }
        publish(snapshot)

        Log.i(
            TAG,
            "OCR ${SystemClock.elapsedRealtime() - start} ms · " +
                "viaje=${snapshot.isRideScreen} precio=${snapshot.offerPrice} " +
                "alt=${snapshot.alternatives} globos=${snapshot.badges.size} " +
                "fallos=$missCount"
        )
    }

    /**
     * Publica un resultado (parcial o completo) aplicando las reglas de
     * histéresis: una lectura parcial no borra lo que ya sabíamos y hace
     * falta una lectura limpia para ocultar el widget.
     */
    private fun publish(snapshot: ScreenSnapshot) {
        // Una lectura sin texto (frame en transición, pantalla en negro) no
        // dice nada: no se oculta el widget por eso.
        if (snapshot.error != null) return

        val assignment = RouteAnalyzer.assign(snapshot.badges, snapshot.panelDistanceKm)
        val hasOffer = snapshot.isRideScreen && snapshot.offerPrice != null

        val read = LiveOffer(
            price = if (snapshot.isRideScreen) snapshot.offerPrice else null,
            currency = snapshot.currency,
            alternatives = if (snapshot.isRideScreen) snapshot.alternatives else emptyList(),
            pickupKm = assignment.toPickup?.distanceKm ?: snapshot.panelDistanceKm,
            pickupMinutes = assignment.toPickup?.minutes,
            tripKm = assignment.trip?.distanceKm,
            tripMinutes = assignment.trip?.minutes,
            postedMinutes = snapshot.postedMinutes,
            updatedAt = System.currentTimeMillis()
        )

        val fingerprint = snapshot.fingerprint()
        val changed = fingerprint != lastFingerprint
        lastFingerprint = fingerprint

        val published = if (hasOffer) {
            missCount = 0
            // Una lectura parcial (por ejemplo, sin globos) no debe borrar lo
            // que ya sabíamos: se rellena con la última lectura buena.
            val previous = lastGood
            val sameOffer = previous?.price == read.price
            val merged = if (sameOffer && previous != null) {
                read.copy(
                    pickupKm = read.pickupKm ?: previous.pickupKm,
                    pickupMinutes = read.pickupMinutes ?: previous.pickupMinutes,
                    tripKm = read.tripKm ?: previous.tripKm,
                    tripMinutes = read.tripMinutes ?: previous.tripMinutes,
                    alternatives = read.alternatives.ifEmpty { previous.alternatives }
                )
            } else {
                read
            }
            lastGood = merged
            merged
        } else {
            missCount++
            if (missCount >= MISSES_TO_HIDE) {
                lastGood = null
                read.copy(price = null, alternatives = emptyList())
            } else {
                // Todavía no estamos seguros: se mantiene lo que había
                return
            }
        }

        // Sólo se publica cuando algo ha cambiado: así el widget no se
        // redibuja en cada análisis.
        if (changed || published.price != lastPublishedPrice) {
            lastPublishedPrice = published.price
            InspectorRepository.publishOffer(published)
        }
    }

    // ------------------------------------------------------------------ Utilidades

    private fun createChannel() {
        Notifications.createChannel(this)
    }

    private fun startForegroundCompat() {
        val notification = Notifications.build(this, "Leyendo la pantalla de inDrive")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.NOTIFICATION_ID,
            notification,
            type
        )
    }}
