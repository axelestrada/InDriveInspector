package com.axelestrada.indriveinspector.core

/**
 * Decide qué globo del mapa es el trayecto "hasta el punto A" y cuál es el
 * trayecto del viaje (A → B).
 *
 * Reglas, en orden de prioridad:
 *  1. El panel inferior de inDrive dice la distancia hasta A ("~1,6 km" o
 *     "735 m"), leída por OCR. El globo cuya distancia coincide es el trayecto
 *     hasta A.
 *  2. Por color: Google usa azul para el tramo desde tu posición al recojo
 *     y verde para el trayecto completo.
 *  3. Por magnitud: el tramo hasta A siempre es más corto que el viaje.
 */
object RouteAnalyzer {

    data class Assignment(
        val toPickup: MapBadge?,
        val trip: MapBadge?
    )

    fun assign(badges: List<MapBadge>, distanceToPickupKm: Double?): Assignment {
        val candidates = badges
        if (candidates.isEmpty()) return Assignment(null, null)

        var toPickup: MapBadge? = null
        var trip: MapBadge? = null

        // 1) Ancla: la distancia del panel
        if (distanceToPickupKm != null) {
            val exact = candidates.filter {
                it.distanceKm != null && isClose(it.distanceKm!!, distanceToPickupKm)
            }
            if (exact.size == 1) {
                toPickup = exact.first()
            } else if (exact.size > 1) {
                toPickup = exact.minByOrNull {
                    kotlin.math.abs(it.distanceKm!! - distanceToPickupKm)
                }
            }
        }

        // 2) Color
        if (toPickup == null) {
            val blue = candidates.firstOrNull { it.color == BadgeColor.BLUE }
            val green = candidates.firstOrNull { it.color == BadgeColor.GREEN }
            if (blue != null && green != null) {
                toPickup = blue
                trip = green
            }
        }

        // 3) Magnitud: el tramo hasta A siempre es el más corto
        if (toPickup == null && candidates.size >= 2) {
            val sorted = candidates.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            toPickup = sorted.first()
            trip = sorted.getOrNull(1)
        }

        // 4) Un solo globo: si coincide con la distancia del panel, es hasta A
        if (toPickup == null && trip == null && candidates.size == 1) {
            val only = candidates.first()
            val matchesPanel = distanceToPickupKm != null && only.distanceKm != null &&
                isClose(only.distanceKm!!, distanceToPickupKm)
            if (matchesPanel || only.color == BadgeColor.BLUE) {
                toPickup = only
            } else {
                trip = only
            }
        }

        // El trayecto del viaje es el globo que sobra (o el más largo)
        if (trip == null && toPickup != null) {
            trip = candidates.filter { it !== toPickup }
                .maxByOrNull { badge -> badge.distanceKm ?: badge.minutes?.toDouble() ?: 0.0 }
        }

        return Assignment(toPickup, trip)
    }

    private fun isClose(a: Double, b: Double): Boolean {
        val tolerance = maxOf(0.4, b * 0.18)
        return kotlin.math.abs(a - b) <= tolerance
    }
}
