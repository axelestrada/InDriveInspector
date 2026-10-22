package com.axelestrada.indriveinspector.core

/** Semáforo de rentabilidad de una oferta. */
enum class ProfitStatus { GREEN, YELLOW, RED, NEUTRAL }

/**
 * Parámetros de rentabilidad configurables por el usuario.
 *
 *  - [costPerKm]: precio por km que necesitas para que el viaje salga a cuenta.
 *  - [targetPricePerHour]: precio por hora que tú consideras rentable.
 *  - [minimumTripPrice]: tarifa mínima que aceptas (0 = sin mínimo).
 *  - [includeDeadhead]: contar también el trayecto hasta el punto de recojo.
 *  - [alwaysVisible]: dejar el widget en pantalla aunque no haya oferta.
 */
data class ProfitSettings(
    val costPerKm: Double = 10.0,
    val targetPricePerHour: Double = 250.0,
    val minimumTripPrice: Double = 0.0,
    val includeDeadhead: Boolean = true,
    val alwaysVisible: Boolean = false
)

/** Métricas de una oferta concreta (la actual o una alternativa). */
data class PriceMetrics(
    val price: Double,
    val currency: String,
    val pricePerKm: Double?,
    val pricePerHour: Double?,
    val cost: Double?,
    val net: Double?,
    val status: ProfitStatus,
    /** Precio mínimo configurado (null = no hay mínimo). */
    val minimumPrice: Double? = null,
    /**
     * Puntuación 0..1: el peor de los indicadores, acotado a 1.
     * Es lo que se pinta en el anillo circular.
     */
    val score: Float? = null
) {
    /** ¿La tarifa llega al mínimo que aceptas? (null = no hay mínimo). */
    val meetsMinimum: Boolean?
        get() = minimumPrice?.let { price >= it }

    /** Porcentaje para el anillo (0..100). */
    val scorePercent: Int
        get() = ((score ?: 0f) * 100f).toInt().coerceIn(0, 100)
}

/**
 * La oferta que hay en pantalla ahora mismo: precio, precios alternativos y
 * los tramos (obtenidos por OCR del mapa).
 */
data class LiveOffer(
    val price: Double?,
    val currency: String,
    val alternatives: List<Double>,
    val pickupKm: Double?,
    val pickupMinutes: Int?,
    val tripKm: Double?,
    val tripMinutes: Int?,
    val postedMinutes: Int?,
    val updatedAt: Long
) {

    val totalKm: Double?
        get() {
            val trip = tripKm ?: return null
            val pickup = pickupKm ?: 0.0
            return trip + pickup
        }

    val totalMinutes: Int?
        get() {
            val trip = tripMinutes ?: return null
            val pickup = pickupMinutes ?: 0
            return trip + pickup
        }

    /**
     * Calcula las métricas de un precio (el de la oferta o una alternativa).
     *
     * El semáforo se basa en el peor de los indicadores:
     *   - precio por km / [ProfitSettings.costPerKm]
     *   - precio por hora / [ProfitSettings.targetPricePerHour]
     *   - precio / [ProfitSettings.minimumTripPrice] (si hay mínimo)
     * Verde si todos llegan a 1, amarillo si el peor está entre 85% y 100%,
     * rojo por debajo del 85%.
     */
    fun metrics(price: Double, settings: ProfitSettings): PriceMetrics {
        val km = if (settings.includeDeadhead) totalKm else tripKm
        val minutes = if (settings.includeDeadhead) totalMinutes else tripMinutes

        val pricePerKm = if (km != null && km > 0) price / km else null
        val pricePerHour = if (minutes != null && minutes > 0) price / (minutes / 60.0) else null

        val cost = if (km != null) km * settings.costPerKm else null
        val net = cost?.let { price - it }

        val ratios = mutableListOf<Double>()
        if (pricePerKm != null && settings.costPerKm > 0) {
            ratios.add(pricePerKm / settings.costPerKm)
        }
        if (pricePerHour != null && settings.targetPricePerHour > 0) {
            ratios.add(pricePerHour / settings.targetPricePerHour)
        }
        val hasMinimum = settings.minimumTripPrice > 0
        if (hasMinimum) {
            ratios.add(price / settings.minimumTripPrice)
        }

        val status = when {
            ratios.isEmpty() -> ProfitStatus.NEUTRAL
            ratios.min() >= 1.0 -> ProfitStatus.GREEN
            ratios.min() >= 0.85 -> ProfitStatus.YELLOW
            else -> ProfitStatus.RED
        }

        return PriceMetrics(
            price = price,
            currency = currency,
            pricePerKm = pricePerKm,
            pricePerHour = pricePerHour,
            cost = cost,
            net = net,
            status = status,
            minimumPrice = if (hasMinimum) settings.minimumTripPrice else null,
            score = if (ratios.isEmpty()) null else ratios.min().coerceIn(0.0, 1.0).toFloat()
        )
    }

    /** Todos los precios a mostrar: el de la oferta y las alternativas. */
    fun allPrices(): List<Double> {
        val result = mutableListOf<Double>()
        price?.let { result.add(it) }
        alternatives.forEach { if (!result.contains(it)) result.add(it) }
        return result
    }
}
