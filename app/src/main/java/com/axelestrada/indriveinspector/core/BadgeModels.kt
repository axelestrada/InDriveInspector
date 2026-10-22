package com.axelestrada.indriveinspector.core

/** Rectángulo simple, sin depender de Android (para poder testearlo en la JVM). */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun union(other: Box): Box = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom)
    )
}

/** Color del globo/badge del mapa donde Google Maps dibuja tiempos y distancias. */
enum class BadgeColor { GREEN, BLUE, RED, ORANGE, WHITE, GRAY, OTHER }

/** Unidad en la que inDrive/Google muestran la distancia dentro del globo. */
enum class DistanceUnit { KM, METERS }

/**
 * Un "badge" del mapa: el par tiempo/distancia que Google dibuja sobre la ruta.
 *  - `19 min` + `6,7 km` → trayecto total del viaje (punto A → punto B)
 *  - `9 min`  + `3,4 km` → trayecto desde tu posición hasta el punto A
 *  - `5 min`  + `735 metro` → trayectos cortos, en metros
 */
data class MapBadge(
    val minutes: Int?,
    val distance: Double?,
    val distanceUnit: DistanceUnit,
    val color: BadgeColor,
    val text: String,
    val box: Box
) {
    /** Distancia normalizada a kilómetros (735 metros -> 0,735 km). */
    val distanceKm: Double?
        get() = when (distanceUnit) {
            DistanceUnit.KM -> distance
            DistanceUnit.METERS -> distance?.div(1000.0)
        }

    val isSaturated: Boolean
        get() = color == BadgeColor.GREEN || color == BadgeColor.BLUE ||
            color == BadgeColor.RED || color == BadgeColor.ORANGE
}
