package com.axelestrada.indriveinspector.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.axelestrada.indriveinspector.core.LiveOffer
import com.axelestrada.indriveinspector.core.PriceMetrics
import com.axelestrada.indriveinspector.core.ProfitSettings
import com.axelestrada.indriveinspector.core.ProfitStatus
import java.util.Locale

/**
 * Indicador flotante horizontal y pequeño que se dibuja encima de inDrive.
 *
 * Muestra el precio de la oferta con un anillo de progreso (rentabilidad),
 * el precio por km y el precio por hora, con el color según la rentabilidad:
 *   verde  = rentable
 *   amarillo = hasta 15% por debajo del objetivo
 *   rojo   = más de 15% por debajo
 *
 * Al tocarlo se despliega la lista con los precios alternativos.
 */
class ProfitWidgetView(context: Context) : LinearLayout(context) {

    private val pill = LinearLayout(context)
    private val expanded = LinearLayout(context)

    private val ring = RingProgressView(context)
    private val ringValue = TextView(context)
    private val title = TextView(context)
    private val perKm = TextView(context)
    private val perHour = TextView(context)

    /** Vista que hace de asa para arrastrar (la píldora principal). */
    val touchTarget: View get() = pill

    private var expandedVisible = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)

        pill.orientation = HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(6), dp(6), dp(12), dp(6))
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // --- Anillo ---
        val ringBox = FrameLayout(context)
        val ringSize = dp(38)
        ringBox.addView(
            ring,
            FrameLayout.LayoutParams(ringSize, ringSize).apply {
                gravity = Gravity.CENTER
            }
        )
        ringValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        ringValue.setTextColor(Color.rgb(245, 245, 245))
        ringValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        ringValue.gravity = Gravity.CENTER
        ringBox.addView(
            ringValue,
            FrameLayout.LayoutParams(ringSize, ringSize).apply { gravity = Gravity.CENTER }
        )
        pill.addView(ringBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // --- Textos ---
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }

        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        texts.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val details = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        perKm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        perKm.setPadding(dp(2), 0, dp(8), 0)
        details.addView(perKm, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        perHour.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        details.addView(perHour, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        texts.addView(details, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        pill.addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        expanded.orientation = VERTICAL
        expanded.visibility = GONE
        expanded.setPadding(dp(10), dp(8), dp(10), dp(8))
        expanded.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(205, 17, 19, 24))
            setStroke(dp(1), Color.argb(150, 90, 98, 110))
        }
        addView(expanded, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        pill.setOnClickListener {
            expandedVisible = !expandedVisible
            expanded.visibility = if (expandedVisible) VISIBLE else GONE
        }
    }

    /**
     * Actualiza el contenido.
     *
     * @param offer la oferta leída; si es null (no hay carrera en pantalla) y
     *        el widget es "siempre visible", se muestra con guiones.
     * @return true si hay algo que mostrar.
     */
    fun render(offer: LiveOffer?, settings: ProfitSettings): Boolean {
        val price = offer?.price
        if (price == null) {
            // Sin oferta: sólo se ve si el usuario pidió mantenerlo visible
            if (!settings.alwaysVisible) {
                visibility = View.GONE
                return false
            }
            visibility = View.VISIBLE
            applyStatusStyle(pill, ProfitStatus.NEUTRAL)
            ring.ringColor = ringColorFor(ProfitStatus.NEUTRAL)
            ring.progress = 0f
            ringValue.text = "—"
            title.text = "—"
            perKm.text = "—/km"
            perHour.text = "—/h"
            expanded.removeAllViews()
            expandedVisible = false
            expanded.visibility = GONE
            return true
        }
        visibility = View.VISIBLE

        val metrics = offer.metrics(price, settings)
        applyStatusStyle(pill, metrics.status)

        ring.ringColor = ringColorFor(metrics.status)
        ring.progress = metrics.score ?: 0f
        ringValue.text = "${metrics.scorePercent}"

        title.text = formatPrice(price, offer.currency) + belowMinimumMark(metrics)
        perKm.text = metrics.pricePerKm?.let { "${fmt(it)}/km" } ?: "—/km"
        perHour.text = metrics.pricePerHour?.let { "${fmt(it)}/h" } ?: "—/h"

        renderAlternatives(offer, settings)
        return true
    }

    private fun renderAlternatives(offer: LiveOffer, settings: ProfitSettings) {
        expanded.removeAllViews()
        val alternatives = offer.alternatives.filter { it != offer.price }
        if (alternatives.isEmpty()) {
            expandedVisible = false
            expanded.visibility = GONE
            return
        }

        for (alt in alternatives) {
            val metrics = offer.metrics(alt, settings)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }

            val miniRing = RingProgressView(context).apply {
                ringColor = ringColorFor(metrics.status)
                progress = metrics.score ?: 0f
                ringWidth = dp(3).toFloat()
            }
            row.addView(
                miniRing,
                LayoutParams(dp(14), dp(14))
            )

            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                text = formatPrice(alt, offer.currency) + belowMinimumMark(metrics)
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            val detail = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                text = "${metrics.pricePerKm?.let { "${fmt(it)}/km" } ?: "—"}" +
                    "   ${metrics.pricePerHour?.let { "${fmt(it)}/h" } ?: "—"}" +
                    "   ${metrics.scorePercent}%"
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(detail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            expanded.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            val color = textColorFor(metrics.status)
            label.setTextColor(color)
            detail.setTextColor(color)
        }
    }

    /**
     * El color del semáforo va **sólo en el anillo de progreso**.
     * El fondo y el borde se quedan neutros para que el indicador sea el
     * único que da color.
     */
    private fun applyStatusStyle(view: View, status: ProfitStatus) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(225, 14, 16, 20))
            setStroke(dp(1), Color.argb(110, 110, 118, 130))
        }
        val color = textColorFor(status)
        title.setTextColor(color)
        perKm.setTextColor(color)
        perHour.setTextColor(color)
        ringValue.setTextColor(color)
    }

    private fun ringColorFor(status: ProfitStatus): Int = when (status) {
        ProfitStatus.GREEN -> Color.rgb(53, 211, 154)
        ProfitStatus.YELLOW -> Color.rgb(242, 179, 61)
        ProfitStatus.RED -> Color.rgb(240, 101, 91)
        ProfitStatus.NEUTRAL -> Color.rgb(150, 158, 170)
    }

    private fun textColorFor(status: ProfitStatus): Int = when (status) {
        // El color lo pone el anillo; el texto siempre es #F5F5F5
        ProfitStatus.GREEN -> Color.rgb(245, 245, 245)
        ProfitStatus.YELLOW -> Color.rgb(245, 245, 245)
        ProfitStatus.RED -> Color.rgb(245, 245, 245)
        ProfitStatus.NEUTRAL -> Color.argb(235, 245, 245, 245)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        fun fmt(value: Double): String =
            if (value % 1.0 == 0.0) {
                String.format(Locale.getDefault(), "%.0f", value)
            } else {
                String.format(Locale.getDefault(), "%.1f", value)
            }

        fun formatPrice(value: Double, currency: String): String =
            if (currency.isBlank()) fmt(value) else "$currency ${fmt(value)}"

        /** Marca cuando la tarifa no llega al mínimo configurado. */
        fun belowMinimumMark(metrics: PriceMetrics): String =
            if (metrics.meetsMinimum == false) " ↓mín" else ""
    }
}
