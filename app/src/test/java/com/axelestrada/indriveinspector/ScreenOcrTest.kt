package com.axelestrada.indriveinspector

import com.axelestrada.indriveinspector.core.Box
import com.axelestrada.indriveinspector.ocr.OcrLine
import com.axelestrada.indriveinspector.ocr.ScreenOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura de la pantalla de inDrive con OCR puro (sin accesibilidad).
 *
 * Coordenadas de la pantalla real (1080x2400) del dump del usuario:
 *   título "Solicitud de viaje"   y 192..286
 *   distancia del panel "~3,4 km"  y 1312..1361
 *   precio del panel "HNL105"      y 1361..1455  (letra grande)
 *   botón "Aceptar por HNL105"     y 1683..1830
 *   alternativas "HNL116"/"HNL124" y 1983..2130
 */
class ScreenOcrTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrLine(text, Box(l, t, r, b))

    private val rideScreen = listOf(
        line("Cascadas M", 10, 200, 300, 250),
        line("Solicitud de viaje", 306, 192, 775, 286),
        line("6 min.", 50, 1509, 119, 1549),
        line("~3,4 km", 168, 1312, 312, 1361),
        line("HNL105", 168, 1361, 382, 1455),
        line("Blvd. Kuwait (Colonia Las Brisas)", 242, 1455, 1038, 1517),
        line("TGU", 242, 1538, 1038, 1600),
        line("Aceptar por HNL105", 200, 1720, 520, 1780),
        line("Ofrece tu tarifa", 42, 1900, 400, 1950),
        line("HNL116", 150, 2010, 300, 2060),
        line("HNL124", 650, 2010, 800, 2060),
        line("Cerrar", 200, 2200, 400, 2250)
    )

    private val listScreen = listOf(
        line("Ocupado", 240, 90, 450, 140),
        line("Tu saldo es bajo. Auméntalo", 120, 210, 840, 260),
        line("~895 metro", 140, 320, 330, 360),
        line("HNL65", 140, 370, 310, 420),
        line("Natalí", 40, 425, 110, 455),
        line("4.92(11)", 40, 460, 140, 490),
        line("5 min.", 40, 495, 120, 525),
        line("Boulevard Suyapa (Colonia Florencia Sur)", 140, 440, 830, 480),
        line("~951 metro", 140, 640, 330, 680),
        line("HNL79", 140, 690, 310, 740),
        line("David", 40, 745, 110, 775),
        line("4.81(18)", 40, 780, 140, 810),
        line("57 seg.", 40, 815, 130, 845),
        line("HNL89", 140, 960, 310, 1010),
        line("Arely", 40, 1015, 110, 1045),
        line("~1,6 km", 140, 1180, 310, 1220),
        line("HNL160", 140, 1230, 340, 1280),
        line("Yanori", 40, 1285, 110, 1315),
        line("~3,3 km", 140, 1540, 310, 1580),
        line("HNL164", 140, 1590, 340, 1640),
        line("Sofia", 40, 1645, 110, 1675),
        line("Solicitudes de viaje", 30, 1850, 250, 1900),
        line("Demanda", 400, 1850, 550, 1900),
        line("Desempeño", 690, 1850, 880, 1900)
    )

    @Test
    fun `detecta la pantalla de una carrera`() {
        assertTrue(ScreenOcr.parseLines(rideScreen).isRideScreen)
    }

    @Test
    fun `la lista de carreras no es una carrera`() {
        assertFalse(ScreenOcr.parseLines(listScreen).isRideScreen)
    }

    @Test
    fun `detecta la carrera aunque el mapa tape el titulo`() {
        // El mapa puede cubrir el título "Solicitud de viaje": la detección
        // no depende de él, sólo de "Ofrece tu tarifa" y "Aceptar".
        val sinTitulo = rideScreen.filterNot { it.text == "Solicitud de viaje" }
        assertTrue(ScreenOcr.parseLines(sinTitulo).isRideScreen)
    }

    @Test
    fun `detecta la carrera con el boton aceptar aunque no haya etiqueta`() {
        // Sólo el botón "Aceptar por HNL105" (sin "Ofrece tu tarifa" ni título)
        val soloAceptar = listOf(
            line("Aceptar por HNL105", 200, 1720, 520, 1780)
        )
        assertTrue(ScreenOcr.parseLines(soloAceptar).isRideScreen)
    }

    @Test
    fun `la pestaña solicitudes de viaje no es una carrera`() {
        // "Solicitudes de viaje" (plural, la pestaña inferior) no cuenta
        val soloPestana = listOf(line("Solicitudes de viaje", 30, 1850, 250, 1900))
        assertFalse(ScreenOcr.parseLines(soloPestana).isRideScreen)
    }

    @Test
    fun `lee el precio de la oferta y las alternativas`() {
        val parsed = ScreenOcr.parseLines(rideScreen)

        assertEquals(105.0, parsed.offerPrice!!, 0.001)
        assertEquals("HNL", parsed.currency)
        assertEquals(listOf(116.0, 124.0), parsed.alternatives)
    }

    @Test
    fun `lee la distancia y los minutos del panel`() {
        val parsed = ScreenOcr.parseLines(rideScreen)

        assertEquals(3.4, parsed.panelDistanceKm!!, 0.001)
        assertEquals(6, parsed.postedMinutes)
    }

    @Test
    fun `la distancia en metros se normaliza a km`() {
        val enMetros = rideScreen.map {
            if (it.text == "~3,4 km") it.copy(text = "735 metro") else it
        }
        assertEquals(0.735, ScreenOcr.parseLines(enMetros).panelDistanceKm!!, 0.0001)
    }

    @Test
    fun `no confunde los minutos con un precio`() {
        val parsed = ScreenOcr.parseLines(rideScreen)
        // "6 min." no es un precio: el precio viene del botón "Aceptar por"
        assertEquals(105.0, parsed.offerPrice!!, 0.001)
    }

    @Test
    fun `acepta el titulo en ingles`() {
        val ingles = rideScreen.map {
            if (it.text == "Solicitud de viaje") it.copy(text = "Ride request") else it
        }
        assertTrue(ScreenOcr.parseLines(ingles).isRideScreen)
    }

    @Test
    fun `el boton de aceptar tambien delata una carrera`() {
        // El mapa puede tapar el título un instante: el botón no falla
        val sinTitulo = rideScreen.filterNot { it.text == "Solicitud de viaje" }
        val parsed = ScreenOcr.parseLines(sinTitulo)

        assertTrue(parsed.isRideScreen)
        assertEquals(105.0, parsed.offerPrice!!, 0.001)
    }

    @Test
    fun `sin boton de aceptar usa el precio grande del panel`() {
        val sinBoton = rideScreen.filterNot { it.text.startsWith("Aceptar") }
        val parsed = ScreenOcr.parseLines(sinBoton)

        assertEquals(105.0, parsed.offerPrice!!, 0.001)
        assertEquals("HNL", parsed.currency)
        // Las alternativas son los precios por debajo del precio del panel
        assertEquals(listOf(116.0, 124.0), parsed.alternatives)
    }

    @Test
    fun `en la lista no hay precio de oferta`() {
        val parsed = ScreenOcr.parseLines(listScreen)
        // En la lista hay varios precios sueltos y ningún botón "Aceptar por"
        assertFalse(parsed.isRideScreen)
    }
}
