package com.axelestrada.indriveinspector.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartido entre el lector de OCR, el widget flotante y la app.
 *
 * Todo vive en el mismo proceso, así que un `object` con `StateFlow` es
 * suficiente y evita sincronizar datos entre componentes.
 */
object InspectorRepository {

    const val PREFS_NAME = "inspector_settings"

    /** La oferta que hay en pantalla ahora mismo (leída con OCR). */
    private val _liveOffer = MutableStateFlow<LiveOffer?>(null)
    val liveOffer: StateFlow<LiveOffer?> = _liveOffer.asStateFlow()

    private val _settings = MutableStateFlow(ProfitSettings())
    val settings: StateFlow<ProfitSettings> = _settings.asStateFlow()

    /** El inspector está corriendo (OCR + widget). */
    private val _servicesActive = MutableStateFlow(false)
    val servicesActive: StateFlow<Boolean> = _servicesActive.asStateFlow()

    private var settingsLoaded = false

    fun setServicesActive(value: Boolean) {
        _servicesActive.value = value
    }

    fun publishOffer(offer: LiveOffer) {
        _liveOffer.value = offer
    }

    // ---- Ajustes -------------------------------------------------------------------

    /** Lee los ajustes guardados (una sola vez por proceso). */
    fun loadSettings(context: Context) {
        if (settingsLoaded) return
        settingsLoaded = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = ProfitSettings(
            costPerKm = prefs.getFloat(KEY_COST_PER_KM, DEFAULT_COST_PER_KM).toDouble(),
            targetPricePerHour = prefs.getFloat(
                KEY_TARGET_PER_HOUR,
                DEFAULT_TARGET_PER_HOUR
            ).toDouble(),
            minimumTripPrice = prefs.getFloat(KEY_MIN_TRIP_PRICE, 0f).toDouble(),
            includeDeadhead = prefs.getBoolean(KEY_INCLUDE_DEADHEAD, true),
            alwaysVisible = prefs.getBoolean(KEY_ALWAYS_VISIBLE, false)
        )
    }

    fun updateSettings(context: Context, settings: ProfitSettings) {
        _settings.value = settings
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_COST_PER_KM, settings.costPerKm.toFloat())
            .putFloat(KEY_TARGET_PER_HOUR, settings.targetPricePerHour.toFloat())
            .putFloat(KEY_MIN_TRIP_PRICE, settings.minimumTripPrice.toFloat())
            .putBoolean(KEY_INCLUDE_DEADHEAD, settings.includeDeadhead)
            .putBoolean(KEY_ALWAYS_VISIBLE, settings.alwaysVisible)
            .apply()
    }

    const val KEY_COST_PER_KM = "cost_per_km"
    const val KEY_TARGET_PER_HOUR = "target_price_per_hour"
    const val KEY_MIN_TRIP_PRICE = "minimum_trip_price"
    const val KEY_INCLUDE_DEADHEAD = "include_deadhead"
    const val KEY_ALWAYS_VISIBLE = "always_visible"

    const val DEFAULT_COST_PER_KM = 10f
    const val DEFAULT_TARGET_PER_HOUR = 250f
}
