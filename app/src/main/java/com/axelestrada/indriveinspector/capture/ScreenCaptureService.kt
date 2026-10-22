package com.axelestrada.indriveinspector.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.axelestrada.indriveinspector.core.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Captura la pantalla con MediaProjection para poder aplicar OCR al mapa.
 *
 * La accesibilidad por sí sola no puede leer el texto dibujado por el mapa
 * de Google (es un canvas), así que hace falta un fotograma real.
 *
 * Detalle de rendimiento: el buffer se decodifica en un Bitmap reutilizable
 * (evita 10 MB de basura por fotograma) y sólo se decodifica un frame cada
 * ~700 ms salvo que se pida uno fresco.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "InDriveInspector"

        const val ACTION_START = "com.axelestrada.indriveinspector.action.START_CAPTURE"
        const val ACTION_STOP = "com.axelestrada.indriveinspector.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        /** Tamaño de la rejilla de muestreo para detectar cambios. */
        private const val GRID_W = 54
        private const val GRID_H = 120

        /** Porcentaje de la pantalla que tiene que cambiar para darla por distinta. */
        private const val CHANGE_THRESHOLD = 0.012f

        /** Instancia viva, para poder pedir fotogramas desde el inspector. */
        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val decodeLock = Any()

    private var frameBuffer: Bitmap? = null
    private var lastDecodeAt = 0L

    /** Rejilla muestreada del último frame, para detectar cambios. */
    @Volatile
    private var lastSamples: ByteArray? = null

    /** Puesto a true cuando la pantalla ha cambiado de verdad. */
    @Volatile
    private var frameChanged = false

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var forceNextFrame = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection detenida por el sistema")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        releaseProjection()
        frameBuffer = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(
                    intent, EXTRA_RESULT_DATA, Intent::class.java
                )
                if (data == null) {
                    Log.e(TAG, "Falta el Intent de resultado de MediaProjection")
                    stopSelf()
                } else {
                    setupProjection(resultCode, data)
                }
            }

            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ MediaProjection

    private fun setupProjection(resultCode: Int, data: Intent) {
        if (mediaProjection != null) {
            Log.i(TAG, "La proyección ya está activa")
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.e(TAG, "getMediaProjection devolvió null")
                stopSelf()
                return
            }
            mediaProjection = projection

            // Obligatorio en Android 14+ antes de crear el VirtualDisplay
            projection.registerCallback(projectionCallback, null)

            val metrics = displayMetrics()
            val reader = ImageReader.newInstance(
                metrics.first, metrics.second, PixelFormat.RGBA_8888, 3
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "indrive-inspector",
                metrics.first,
                metrics.second,
                metrics.third,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            Log.i(TAG, "Captura de pantalla activa: ${metrics.first}x${metrics.second}")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la captura: ${e.message}")
            stopSelf()
        }
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando la proyección: ${e.message}")
        }
    }

    private fun displayMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val density = resources.displayMetrics.densityDpi
        return Triple(bounds.width(), bounds.height(), density)
    }

    // ------------------------------------------------------------------ Fotogramas

    /**
     * Llega un frame nuevo.
     *
     * Para detectar si la pantalla ha cambiado **no** decodificamos la imagen
     * entera (son 10 MB): muestreamos una rejilla de ~6.500 píxeles, que
     * cuesta menos de un milisegundo. Sólo si el cambio es real decodificamos
     * el frame completo, y de momento para el OCR.
     *
     * Así la detección es casi inmediata (llega a nivel de frame) y el consumo
     * de CPU se mantiene bajo.
     */
    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val samples = image.sampleGrid()
            val ratio = differenceRatio(samples, lastSamples)
            lastSamples = samples

            if (ratio > CHANGE_THRESHOLD || forceNextFrame) {
                synchronized(decodeLock) {
                    val target = frameBuffer
                    if (target == null || target.isRecycled || target.width != image.width ||
                        target.height != image.height
                    ) {
                        frameBuffer?.recycle()
                        frameBuffer = Bitmap.createBitmap(
                            image.width, image.height, Bitmap.Config.ARGB_8888
                        )
                    }
                    image.copyInto(frameBuffer!!)
                    lastFrameAt = SystemClock.elapsedRealtime()
                    forceNextFrame = false
                }
                frameChanged = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error procesando el frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Muestrea una rejilla de la imagen (un byte por píxel muestreado).
     */
    private fun Image.sampleGrid(): ByteArray {
        val plane = planes[0]
        val buf = plane.buffer
        val px = plane.pixelStride
        val rs = plane.rowStride
        val stepX = maxOf(1, width / GRID_W)
        val stepY = maxOf(1, height / GRID_H)
        val out = ByteArray(GRID_W * GRID_H)
        val limit = buf.limit()
        var i = 0
        var y = 0
        while (y < height && i < out.size) {
            var x = 0
            while (x < width && i < out.size) {
                val idx = y * rs + x * px
                if (idx + 2 < limit) {
                    val lum = (buf.get(idx).toInt() + buf.get(idx + 1).toInt() +
                        buf.get(idx + 2).toInt()) / 3
                    out[i] = lum.toByte()
                }
                i++
                x += stepX
            }
            y += stepY
        }
        return out
    }

    /** Porcentaje de píxeles muestreados que han cambiado. */
    private fun differenceRatio(current: ByteArray, previous: ByteArray?): Float {
        if (previous == null || previous.size != current.size) return 1f
        var diff = 0
        for (i in current.indices) {
            if (current[i] != previous[i]) diff++
        }
        return diff.toFloat() / current.size
    }

    /** ¿Ha cambiado la pantalla desde la última vez que se miró? */
    fun consumeChangeFlag(): Boolean {
        if (!frameChanged) return false
        frameChanged = false
        return true
    }

    /**
     * Devuelve una copia del fotograma más reciente.
     *
     * IMPORTANTE: si ya hay un fotograma en caché se devuelve al instante. La
     * pantalla de inDrive es estática, así que esperar a que llegue un frame
     * nuevo sólo añadiría latencia (hasta 1,2 s) sin ganar nada.
     */
    suspend fun grab(timeoutMs: Long = 1200): Bitmap? = withContext(Dispatchers.Default) {
        synchronized(decodeLock) { frameBuffer }?.let { return@withContext snapshotCopy() }

        // No hay fotograma todavía: esperamos a que llegue uno
        val requestedAt = SystemClock.elapsedRealtime()
        forceNextFrame = true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lastFrameAt >= requestedAt) break
            delay(50)
        }
        forceNextFrame = false

        snapshotCopy()
    }

    private fun snapshotCopy(): Bitmap? {
        // La copia se hace DENTRO del lock: si no, la captura podría estar
        // sobrescribiendo el buffer durante la copia y el OCR leería una
        // imagen a medias (títulos y precios incompletos).
        return synchronized(decodeLock) {
            val buffer = frameBuffer ?: return null
            if (buffer.isRecycled) return null
            try {
                Bitmap.createBitmap(buffer)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo copiar el frame: ${e.message}")
                null
            }
        }
    }

    fun hasProjection(): Boolean = mediaProjection != null

    /**
     * Copia segura del fotograma actual, para analizarlo sin que la captura
     * lo sobrescriba por debajo.
     */
    fun copyFrame(): Bitmap? = snapshotCopy()

    // ------------------------------------------------------------------ Utilidades

    private fun createChannel() {
        Notifications.createChannel(this)
    }

    private fun startForegroundCompat() {
        val notification = Notifications.build(this, "Leyendo el mapa de inDrive")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.NOTIFICATION_ID,
            notification,
            type
        )
    }
    /**
     * Copia el frame dentro de un Bitmap reutilizable. Usa copia nativa cuando
     * el formato es contiguo y recurre a un bucle manual si hay padding.
     */
    private fun Image.copyInto(target: Bitmap) {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 4 && rowStride == width * 4) {
            buffer.position(0)
            target.copyPixelsFromBuffer(buffer)
            return
        }

        val row = ByteArray(rowStride)
        val pixels = IntArray(width)
        var offset = 0
        for (y in 0 until height) {
            if (offset >= buffer.limit()) break
            buffer.position(offset)
            val toRead = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, toRead)
            for (x in 0 until width) {
                val base = x * pixelStride
                if (base + 3 >= toRead) break
                val r = row[base].toInt() and 0xFF
                val g = row[base + 1].toInt() and 0xFF
                val b = row[base + 2].toInt() and 0xFF
                val a = row[base + 3].toInt() and 0xFF
                pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            target.setPixels(pixels, 0, width, 0, y, width, 1)
            offset += rowStride
        }
    }
}
