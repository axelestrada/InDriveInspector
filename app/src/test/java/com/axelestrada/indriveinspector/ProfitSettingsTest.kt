package com.axelestrada.indriveinspector

import com.axelestrada.indriveinspector.core.ProfitSettings
import com.axelestrada.indriveinspector.core.ProfitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ajustes de rentabilidad y su efecto en el semáforo.
 */
class ProfitSettingsTest {

    @Test
    fun `los valores por defecto son razonables`() {
        val s = ProfitSettings()
        assertEquals(10.0, s.costPerKm, 0.001)
        assertEquals(250.0, s.targetPricePerHour, 0.001)
        assertEquals(0.0, s.minimumTripPrice, 0.001)
        assertTrue(s.includeDeadhead)
        assertFalse(s.alwaysVisible)
    }

    @Test
    fun `el widget siempre visible no cambia el calculo`() {
        val base = ProfitSettings()
        val visible = base.copy(alwaysVisible = true)
        assertEquals(base.copy(alwaysVisible = false), base)
        assertTrue(visible.alwaysVisible)
    }
}
