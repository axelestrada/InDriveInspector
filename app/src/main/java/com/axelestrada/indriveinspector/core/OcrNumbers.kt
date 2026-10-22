package com.axelestrada.indriveinspector.core

/**
 * Utilidades para números leídos por OCR.
 *
 * Al bajar la resolución de la imagen, el OCR confunde más caracteres:
 * "HNL1O5" en vez de "HNL105", "73S metro" en vez de "735 metro".
 * Estas funciones reparan esas lecturas.
 */

/** Caracteres que el OCR suele confundir con dígitos. */
private val CONFUSABLE = mapOf(
    'O' to '0', 'o' to '0',
    'l' to '1', 'I' to '1', '|' to '1',
    'S' to '5', 's' to '5',
    'B' to '8',
    'G' to '6',
    'Z' to '2'
)

/** Sustituye las letras confundidas por su dígito equivalente. */
fun sanitizeNumber(raw: String): String {
    if (raw.isEmpty()) return raw
    val needsFix = raw.any { !it.isDigit() && it != '.' && it != ',' }
    if (!needsFix) return raw
    return buildString {
        for (c in raw) append(CONFUSABLE[c] ?: c)
    }
}

/** Convierte un número OCR (con o sin confusiones) a decimal. */
fun parseOcrNumber(raw: String): Double? =
    sanitizeNumber(raw).replace(',', '.').toDoubleOrNull()

/** Patrón de un número que puede traer letras confundidas. */
const val OCR_NUMBER = """[0-9OoIl|SsBGZ]{1,5}(?:[.,][0-9OoIl|SsBGZ]{1,2})?"""
