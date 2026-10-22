package com.axelestrada.indriveinspector

import com.axelestrada.indriveinspector.core.BadgeColor
import com.axelestrada.indriveinspector.core.Box
import com.axelestrada.indriveinspector.core.DistanceUnit
import com.axelestrada.indriveinspector.core.MapBadge
import com.axelestrada.indriveinspector.core.RouteAnalyzer
import com.axelestrada.indriveinspector.ocr.MapBadgeParser
import com.axelestrada.indriveinspector.ocr.OcrElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifica el parser de globos del mapa con el OCR real de inDrive.
 *
 * Referencia (OCR capturado del mapa de Google en inDrive):
 *
 *   Calle | scadas | Mall | (A | Goocl | María | 4.9 | Parque | Estudiantil |
 *   Cerro | Juana | Laínez | (840) | 3 | min. | ~1,6 | km | Mal | El | Dorado |
 *   Solicitud | de | viaje | 5 | min | IA | 1,6 | km | HNL126 | A | 7,1 | km |
 *   DEL | RO | 23 | min | UCigalpa | B | CD | KENNEDY | ...
 *
 * ML Kit devuelve cada palabra por separado, así que el parser tiene que
 * emparejar "3" con "min." y "~1,6" con "km" por posición.
 */
class MapBadgeParserTest {

    private fun el(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrElement(text, Box(l, t, r, b))

    private val elementos = listOf(
        // --- Rótulos del mapa (no deben generar globos) ---
        el("Calle", 10, 40, 90, 80),
        el("4.9", 300, 40, 350, 80),
        el("(840)", 400, 40, 480, 80),
        el("Parque", 520, 40, 640, 80),
        el("Mall", 700, 160, 780, 200),
        el("Dorado", 790, 160, 900, 200),

        // --- Globo 1 (arriba a la izquierda): 3 min / ~1,6 km ---
        el("3", 60, 120, 95, 170),
        el("min.", 105, 120, 200, 170),
        el("~1,6", 60, 180, 130, 230),
        el("km", 140, 180, 200, 230),

        // --- Globo 2 (cerca del punto A): 7,1 km / 23 min ---
        el("A", 420, 904, 483, 967),
        el("7,1", 390, 805, 470, 855),
        el("km", 480, 805, 555, 855),
        el("23", 390, 870, 455, 920),
        el("min", 465, 870, 550, 920),

        // --- Panel inferior de la oferta (NO son globos) ---
        el("5", 50, 1349, 80, 1389),
        el("min", 90, 1349, 140, 1389),
        el("IA", 150, 1152, 160, 1201),
        el("1,6", 168, 1152, 240, 1201),
        el("km", 250, 1152, 310, 1201),
        el("HNL126", 168, 1201, 330, 1251)
    )

    private fun badges(candidates: List<MapBadgeParser.Candidate>) = candidates.map {
        MapBadge(
            minutes = it.minutes?.toInt(),
            distance = it.distance,
            distanceUnit = it.distanceUnit,
            color = BadgeColor.OTHER,
            text = it.raw,
            box = it.box
        )
    }

    @Test
    fun `encuentra los dos globos del mapa`() {
        val candidates = MapBadgeParser.candidates(elementos)

        val globo1 = candidates.firstOrNull { it.minutes == 3.0 }
        assertNotNull("No se encontró el globo de 3 min", globo1)
        assertEquals(1.6, globo1!!.distance!!, 0.001)
        assertEquals(DistanceUnit.KM, globo1.distanceUnit)

        val globo2 = candidates.firstOrNull { it.minutes == 23.0 }
        assertNotNull("No se encontró el globo de 23 min", globo2)
        assertEquals(7.1, globo2!!.distance!!, 0.001)
    }

    @Test
    fun `el panel inferior no forma parte de los globos`() {
        // El "~1,6 km" y el "5 min." del panel también salen como candidatos,
        // pero van sin burbuja de color: el filtro por color los descarta.
        val candidates = MapBadgeParser.candidates(elementos)
        val delMapa = candidates.filter {
            it.minutes == 3.0 || it.minutes == 23.0
        }
        assertEquals(2, delMapa.size)
    }

    @Test
    fun `asigna el trayecto hasta A y el del viaje`() {
        val assignment = RouteAnalyzer.assign(
            badges = badges(MapBadgeParser.candidates(elementos)),
            distanceToPickupKm = 1.6
        )

        assertNotNull(assignment.toPickup)
        assertEquals(3, assignment.toPickup!!.minutes)
        assertEquals(1.6, assignment.toPickup!!.distanceKm!!, 0.001)

        assertNotNull(assignment.trip)
        assertEquals(23, assignment.trip!!.minutes)
        assertEquals(7.1, assignment.trip!!.distanceKm!!, 0.001)
    }

    @Test
    fun `reconoce distancias en metros`() {
        // Globo con "735 metro" (así lo muestra inDrive en trayectos cortos)
        val conMetros = listOf(
            el("5", 60, 120, 95, 170),
            el("min", 105, 120, 200, 170),
            el("735", 60, 180, 140, 230),
            el("metro", 150, 180, 240, 230)
        )

        val candidates = MapBadgeParser.candidates(conMetros)
        assertEquals(1, candidates.size)

        val globo = candidates.first()
        assertEquals(5.0, globo.minutes!!, 0.001)
        assertEquals(735.0, globo.distance!!, 0.001)
        assertEquals(DistanceUnit.METERS, globo.distanceUnit)
        // normalizado a km
        assertEquals(0.735, globo.distanceKm!!, 0.0001)
    }

    @Test
    fun `acepta metro y m`() {
        val conM = listOf(
            el("5", 60, 120, 95, 170),
            el("min", 105, 120, 200, 170),
            el("735", 60, 180, 140, 230),
            el("m", 150, 180, 180, 230)
        )
        val globo = MapBadgeParser.candidates(conM).first()
        assertEquals(DistanceUnit.METERS, globo.distanceUnit)
        assertEquals(0.735, globo.distanceKm!!, 0.0001)
    }
}
