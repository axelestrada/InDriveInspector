package com.axelestrada.indriveinspector

import com.axelestrada.indriveinspector.core.LiveOffer
import com.axelestrada.indriveinspector.core.ProfitSettings
import com.axelestrada.indriveinspector.core.ProfitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifica el semáforo de rentabilidad del widget flotante.
 *
 * Oferta de referencia (la del OCR real de inDrive):
 *   - trayecto hasta A: 1,6 km / 3 min
 *   - trayecto del viaje: 7,1 km / 23 min
 *   - total: 8,7 km / 26 min
 *   - precio: HNL126   (alternativas: HNL116 y HNL124)
 */
class ProfitWidgetTest {

    private val settings = ProfitSettings(
        costPerKm = 10.0,
        targetPricePerHour = 250.0,
        includeDeadhead = true
    )

    private val offer = LiveOffer(
        price = 126.0,
        currency = "HNL",
        alternatives = listOf(116.0, 124.0),
        pickupKm = 1.6,
        pickupMinutes = 3,
        tripKm = 7.1,
        tripMinutes = 23,
        postedMinutes = 5,
        updatedAt = 0L
    )

    @Test
    fun `calcula precio por km y por hora`() {
        val metrics = offer.metrics(126.0, settings)

        assertEquals(126.0 / 8.7, metrics.pricePerKm!!, 0.001)
        // 26 min -> 126 / (26/60) = 290,8 HNL/h
        assertEquals(290.77, metrics.pricePerHour!!, 0.01)
        assertEquals(8.7 * 10.0, metrics.cost!!, 0.001)
        assertEquals(126.0 - metrics.cost!!, metrics.net!!, 0.001)
    }

    @Test
    fun `la oferta de 126 es verde`() {
        assertEquals(ProfitStatus.GREEN, offer.metrics(126.0, settings).status)
    }

    @Test
    fun `el precio alternativo de 124 sigue siendo verde`() {
        assertEquals(ProfitStatus.GREEN, offer.metrics(124.0, settings).status)
    }

    @Test
    fun `el precio alternativo de 116 es amarillo con un objetivo mas alto`() {
        val exigente = settings.copy(targetPricePerHour = 280.0)
        // 116/(26/60) = 267,7 HNL/h -> 267,7/280 = 0,955 -> amarillo
        assertEquals(ProfitStatus.YELLOW, offer.metrics(116.0, exigente).status)
    }

    @Test
    fun `un precio por debajo del 85 por ciento es rojo`() {
        // 100 HNL -> 100/(26/60) = 230,7/h -> 230,7/250 = 0,923 -> amarillo
        assertEquals(ProfitStatus.YELLOW, offer.metrics(100.0, settings).status)

        // 80 HNL -> 80/(26/60) = 184,6/h -> 184,6/250 = 0,738 -> rojo
        assertEquals(ProfitStatus.RED, offer.metrics(80.0, settings).status)
    }

    @Test
    fun `sin datos de ruta no hay color`() {
        val sinDatos = offer.copy(tripKm = null, tripMinutes = null)
        val metrics = sinDatos.metrics(126.0, settings)
        assertNull(metrics.pricePerKm)
        assertNull(metrics.pricePerHour)
        assertEquals(ProfitStatus.NEUTRAL, metrics.status)
    }

    @Test
    fun `sin contar el trayecto hasta A cambian las metricas`() {
        val sinDeadhead = settings.copy(includeDeadhead = false)
        val metrics = offer.metrics(126.0, sinDeadhead)

        assertEquals(126.0 / 7.1, metrics.pricePerKm!!, 0.001)
        assertEquals(126.0 / (23.0 / 60.0), metrics.pricePerHour!!, 0.01)
    }

    @Test
    fun `un trayecto corto en metros se normaliza a km`() {
        // 735 m + 1,2 km = 1,935 km en 8 min
        val corto = offer.copy(
            pickupKm = 0.735,
            pickupMinutes = 3,
            tripKm = 1.2,
            tripMinutes = 5
        )
        val metrics = corto.metrics(126.0, settings)

        assertEquals(126.0 / 1.935, metrics.pricePerKm!!, 0.001)
        assertEquals(126.0 / (8.0 / 60.0), metrics.pricePerHour!!, 0.01)
    }

    @Test
    fun `lista todos los precios de la oferta`() {
        assertEquals(listOf(126.0, 116.0, 124.0), offer.allPrices())
    }

    @Test
    fun `un precio por debajo del minimo se marca y no es rentable`() {
        val conMinimo = settings.copy(minimumTripPrice = 120.0)

        // 116 < 120 -> no llega al mínimo
        val metrics = offer.metrics(116.0, conMinimo)
        assertEquals(false, metrics.meetsMinimum)
        assertEquals(ProfitStatus.YELLOW, metrics.status) // 116/120 = 0,967

        // 126 >= 120 -> llega al mínimo y sigue verde
        val buena = offer.metrics(126.0, conMinimo)
        assertEquals(true, buena.meetsMinimum)
        assertEquals(ProfitStatus.GREEN, buena.status)
    }

    @Test
    fun `el minimo manda si el resto de indicadores estan bien`() {
        val conMinimo = settings.copy(minimumTripPrice = 200.0)

        // Por km y por hora todo perfecto, pero la tarifa es demasiado baja
        val metrics = offer.metrics(126.0, conMinimo)
        assertEquals(false, metrics.meetsMinimum)
        assertEquals(ProfitStatus.RED, metrics.status) // 126/200 = 0,63
    }

    @Test
    fun `sin minimo configurado no se aplica`() {
        val metrics = offer.metrics(50.0, settings.copy(minimumTripPrice = 0.0))
        assertEquals(null, metrics.meetsMinimum)
        assertEquals(null, metrics.minimumPrice)
    }

    @Test
    fun `el score es el peor indicador acotado a 100`() {
        val metrics = offer.metrics(126.0, settings)
        assertEquals(1f, metrics.score!!, 0.001f)
        assertEquals(100, metrics.scorePercent)
    }

    @Test
    fun `el score del anillo baja con el minimo`() {
        val conMinimo = settings.copy(minimumTripPrice = 300.0)
        val metrics = offer.metrics(126.0, conMinimo)
        // 126/300 = 0,42 -> 42%
        assertEquals(42, metrics.scorePercent)
    }

    @Test
    fun `sin datos de ruta el score es cero`() {
        val sinDatos = offer.copy(tripKm = null, tripMinutes = null, pickupKm = null)
        val metrics = sinDatos.metrics(126.0, settings)
        assertEquals(0, metrics.scorePercent)
    }
}
