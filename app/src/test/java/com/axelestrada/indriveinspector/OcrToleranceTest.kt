package com.axelestrada.indriveinspector

import com.axelestrada.indriveinspector.core.Box
import com.axelestrada.indriveinspector.ocr.OcrLine
import com.axelestrada.indriveinspector.ocr.ScreenOcr
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tolerancia del parser a los errores típicos del OCR, que aumentan cuando
 * se baja la resolución de la imagen.
 */
class OcrToleranceTest {

    private fun line(text: String) = OcrLine(text, Box(0, 0, 200, 40))

    private fun pantalla(vararg textos: String) = textos.map { line(it) }

    @Test
    fun `lee un precio con O en vez de cero`() {
        val parsed = ScreenOcr.parseLines(
            pantalla("Aceptar por HNL1O5", "Ofrece tu tarifa")
        )
        assertEquals(105.0, parsed.offerPrice!!, 0.001)
    }

    @Test
    fun `lee un precio con l en vez de uno`() {
        val parsed = ScreenOcr.parseLines(
            pantalla("Aceptar por HNLlO5", "Ofrece tu tarifa")
        )
        assertEquals(105.0, parsed.offerPrice!!, 0.001)
    }

    @Test
    fun `lee una distancia con O en vez de cero`() {
        val parsed = ScreenOcr.parseLines(
            pantalla(
                "Aceptar por HNL105",
                "Ofrece tu tarifa",
                "~1,O6 km"
            )
        )
        assertEquals(1.06, parsed.panelDistanceKm!!, 0.001)
    }

    @Test
    fun `lee una distancia en metros con S en vez de cinco`() {
        val parsed = ScreenOcr.parseLines(
            pantalla(
                "Aceptar por HNL105",
                "Ofrece tu tarifa",
                "73S metro"
            )
        )
        assertEquals(0.735, parsed.panelDistanceKm!!, 0.0001)
    }
}
