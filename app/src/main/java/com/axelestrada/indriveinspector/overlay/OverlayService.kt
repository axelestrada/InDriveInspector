package com.axelestrada.indriveinspector.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.axelestrada.indriveinspector.core.InspectorRepository
import com.axelestrada.indriveinspector.core.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene el indicador flotante sobre
 * cualquier app (enDrive incluida).
 *
 * No captura nada por sí mismo: se limita a leer el estado compartido
 * ([InspectorRepository]) y a pintar la vista.
 */
class OverlayService : Service() {

    companion object {
        const val TAG = "InDriveInspector"
        const val ACTION_START = "com.axelestrada.indriveinspector.action.START_OVERLAY"
        const val ACTION_STOP = "com.axelestrada.indriveinspector.action.STOP_OVERLAY"

        fun startIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var widget: ProfitWidgetView? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        InspectorRepository.loadSettings(this)
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

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Falta el permiso de superposición (SYSTEM_ALERT_WINDOW)")
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootView == null) {
            attachOverlay()
        }

        scope.launch {
            combine(
                InspectorRepository.liveOffer,
                InspectorRepository.settings
            ) { offer, settings -> offer to settings }
                .collect { (offer, settings) ->
                    // Si el usuario revoca el permiso, el overlay ya no puede
                    // mostrarse: paramos el servicio en vez de fallar.
                    if (!Settings.canDrawOverlays(this@OverlayService)) {
                        Log.w(TAG, "Permiso de superposición revocado: parando")
                        stopSelf()
                        return@collect
                    }
                    // El widget se oculta solo si no hay oferta y el usuario no
                    // ha pedido mantenerlo siempre visible.
                    widget?.render(offer, settings)
                }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        rootView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo quitar el overlay: ${e.message}")
            }
        }
        rootView = null
        widget = null
        windowManager = null
        // La notificación es compartida: no la quitamos al parar este servicio
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Overlay

    private fun attachOverlay() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.x = dp(10)
            layoutParams.y = statusBarHeight() + dp(6)

            val view = ProfitWidgetView(this)
            view.visibility = View.GONE
            view.touchTarget.setOnTouchListener { v, event -> handleTouch(v, event) }

            wm.addView(view, layoutParams)

            rootView = view
            widget = view
            params = layoutParams
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el overlay: ${e.message}")
            stopSelf()
        }
    }

    /**`r`n     * Arrastrar para mover el indicador y un toque para desplegar los
     * precios alternativos.
     */
    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = p.x
                startY = p.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6))) {
                    dragging = true
                }
                if (dragging) {
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    try {
                        windowManager?.updateViewLayout(rootView, p)
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo mover el overlay: ${e.message}")
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    view.performClick()
                }
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ Utilidades

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        Notifications.createChannel(this)
    }

    private fun startForegroundCompat() {
        val notification = Notifications.build(this, "Widget de rentabilidad activo")
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
