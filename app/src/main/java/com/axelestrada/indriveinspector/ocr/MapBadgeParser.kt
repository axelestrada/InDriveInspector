package com.axelestrada.indriveinspector.ocr

import android.graphics.Bitmap
import android.graphics.Color
import com.axelestrada.indriveinspector.core.BadgeColor
import com.axelestrada.indriveinspector.core.DistanceUnit
import com.axelestrada.indriveinspector.core.OCR_NUMBER
import com.axelestrada.indriveinspector.core.parseOcrNumber
import com.axelestrada.indriveinspector.core.Box
import com.axelestrada.indriveinspector.core.MapBadge

/**
 * Convierte el texto reconocido por OCR en los "globos" del mapa de Google
 * (los bubbles con `19 min` / `6,7 km`).
 *
 * ML Kit devuelve **cada palabra por separado** (`3`, `min.`, `~1,6`, `km`),
 * así que el número y su unidad hay que emparejarlos por posición:
 *   1. Se parte el texto en palabras con una caja aproximada para cada una.
 *   2. Cada palabra se clasifica: número / unidad de minutos / unidad de km.
 *   3. Se empareja el número con la unidad que tenga a su derecha (misma línea).
 *   4. Se agrupan las mediciones MIN + KM que estén en el mismo globo.
 *   5. El color del fondo del globo (verde/azul) confirma de qué tramo se trata
 *      y descarta las etiquetas del mapa.
 *
 * La parte de texto es pura (testeable en la JVM); el color necesita el bitmap.
 */
object MapBadgeParser {

    private val NUMBER_RE = Regex("""^[~≈]?($OCR_NUMBER)$""")
    private val MIN_UNIT_RE = Regex(
        """^(?:min|mins|minutos?|mín)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val KM_UNIT_RE = Regex(
        """^(?:km|kms|kilómetros?|kilometros?)\.?$""",
        RegexOption.IGNORE_CASE
    )
    private val METER_UNIT_RE = Regex(
        """^(?:m|mts?|mt|mtr|metro|metros)\.?$""",
        RegexOption.IGNORE_CASE
    )

    /** Distancia horizontal máxima entre un número y su unidad. */
    private const val MAX_H_GAP = 60

    /** Desalineación vertical tolerada para considerar la misma línea. */
    private const val MAX_V_ALIGN = 30

    /** Distancia vertical máxima entre las dos líneas de un mismo globo. */
    private const val MAX_BUBBLE_V = 120

    /** Desalineación horizontal máxima entre las dos líneas de un globo. */
    private const val MAX_BUBBLE_X = 110

    data class Word(val text: String, val box: Box)

    /** Tipo de medida reconocida en un globo. */
    enum class MeasureKind { MINUTES, KM, METERS }

    data class Measurement(
        val value: Double,
        val kind: MeasureKind,
        val box: Box,
        val raw: String
    )

    /** Globo candidato, ya emparejado, pero sin color (el color necesita el bitmap). */
    data class Candidate(
        val minutes: Double?,
        val distance: Double?,
        val distanceUnit: DistanceUnit,
        val box: Box,
        val raw: String
    ) {
        /** Distancia normalizada a kilómetros (735 metros -> 0,735 km). */
        val distanceKm: Double?
            get() = when (distanceUnit) {
                DistanceUnit.KM -> distance
                DistanceUnit.METERS -> distance?.div(1000.0)
            }
    }

    /**
     * Parte los elementos de OCR en palabras, repartiendo el ancho del
     * elemento de forma proporcional al número de caracteres.
     */
    fun splitWords(elements: List<OcrElement>): List<Word> {
        val words = mutableListOf<Word>()
        for (element in elements) {
            val text = element.text
            if (text.isBlank()) continue
            val total = text.length.coerceAtLeast(1)
            val box = element.box
            val width = box.width.coerceAtLeast(1)
            var cursor = 0
            val pieces = text.split(' ', '\n', '\r', '\t').filter { it.isNotBlank() }
            for (piece in pieces) {
                val start = text.indexOf(piece, cursor).let { if (it < 0) cursor else it }
                cursor = start + piece.length
                val left = box.left + (width * start / total)
                val right = box.left + (width * cursor / total)
                words.add(Word(piece.trim(), Box(left, box.top, right, box.bottom)))
            }
        }
        return words
    }

    /** Palabras que parecen números o unidades: útiles para depurar. */
    fun relevantWords(elements: List<OcrElement>): List<Word> =
        splitWords(elements).filter {
            NUMBER_RE.matches(it.text) ||
                MIN_UNIT_RE.matches(it.text) ||
                KM_UNIT_RE.matches(it.text) ||
                METER_UNIT_RE.matches(it.text)
        }

    /**
     * Parte puramente textual: devuelve los globos candidatos (sin color).
     */
    fun candidates(elements: List<OcrElement>): List<Candidate> {
        val measurements = extractMeasurements(splitWords(elements))
        return groupBadges(measurements)
    }

    /** Globo con el color del fondo ya clasificado. */
    fun parse(bitmap: Bitmap, elements: List<OcrElement>): List<MapBadge> =
        candidates(elements).map { candidate ->
            MapBadge(
                minutes = candidate.minutes?.toInt(),
                distance = candidate.distance,
                distanceUnit = candidate.distanceUnit,
                color = classifyColor(bitmap, candidate.box),
                text = candidate.raw,
                box = candidate.box
            )
        }

    // ------------------------------------------------------------------ Mediciones

    private fun extractMeasurements(words: List<Word>): List<Measurement> {
        val result = mutableListOf<Measurement>()
        val consumed = BooleanArray(words.size)

        for ((i, word) in words.withIndex()) {
            if (consumed[i]) continue
            val match = NUMBER_RE.find(word.text) ?: continue
            val value = parseOcrNumber(match.groupValues[1]) ?: continue

            val unit = findUnit(words, i, consumed) ?: continue
            consumed[i] = true
            consumed[unit.index] = true

            val unitText = words[unit.index].text
            val kind = when {
                MIN_UNIT_RE.matches(unitText) -> MeasureKind.MINUTES
                METER_UNIT_RE.matches(unitText) -> MeasureKind.METERS
                else -> MeasureKind.KM
            }
            val box = word.box.union(words[unit.index].box)
            result.add(
                Measurement(value, kind, box, "${word.text} $unitText")
            )
        }
        return result
    }

    private data class UnitHit(val index: Int, val kind: MeasureKind)

    /**
     * Busca la unidad asociada a un número: normalmente a su derecha y en la
     * misma línea ("3 min."). Por seguridad también se admite a la izquierda.
     */
    private fun findUnit(words: List<Word>, numberIndex: Int, consumed: BooleanArray): UnitHit? {
        val number = words[numberIndex].box

        var best: UnitHit? = null
        var bestScore = Int.MAX_VALUE

        for ((j, other) in words.withIndex()) {
            if (j == numberIndex || consumed[j]) continue
            val kind = when {
                MIN_UNIT_RE.matches(other.text) -> MeasureKind.MINUTES
                KM_UNIT_RE.matches(other.text) -> MeasureKind.KM
                METER_UNIT_RE.matches(other.text) -> MeasureKind.METERS
                else -> continue
            }

            val dy = kotlin.math.abs(other.box.centerY - number.centerY)
            if (dy > MAX_V_ALIGN) continue

            val gapRight = other.box.left - number.right
            val gapLeft = number.left - other.box.right

            val score = when {
                gapRight in 0..MAX_H_GAP -> gapRight + dy
                gapLeft in 0..25 -> gapLeft + dy + 10 // penaliza "min 3"
                else -> continue
            }
            if (score < bestScore) {
                bestScore = score
                best = UnitHit(j, kind)
            }
        }
        return best
    }

    // ------------------------------------------------------------------ Globos

    private fun groupBadges(measurements: List<Measurement>): List<Candidate> {
        val used = BooleanArray(measurements.size)
        val candidates = mutableListOf<Candidate>()

        for ((i, minutes) in measurements.withIndex()) {
            if (used[i] || minutes.kind != MeasureKind.MINUTES) continue
            used[i] = true

            var partner = -1
            for ((j, other) in measurements.withIndex()) {
                if (used[j] || other.kind == MeasureKind.MINUTES) continue
                if (!sameBubble(minutes.box, other.box)) continue
                partner = j
                break
            }

            val box = if (partner >= 0) {
                used[partner] = true
                minutes.box.union(measurements[partner].box)
            } else {
                minutes.box
            }
            val distance = if (partner >= 0) measurements[partner].value else null
            val unit = if (partner >= 0) {
                if (measurements[partner].kind == MeasureKind.METERS) {
                    DistanceUnit.METERS
                } else {
                    DistanceUnit.KM
                }
            } else {
                DistanceUnit.KM
            }
            val label = if (partner >= 0) {
                "${minutes.raw} · ${measurements[partner].raw}"
            } else {
                minutes.raw
            }

            candidates.add(Candidate(minutes.value, distance, unit, box, label))
        }

        // Distancias que no quedaron emparejadas con unos minutos
        for ((i, distance) in measurements.withIndex()) {
            if (used[i] || distance.kind == MeasureKind.MINUTES) continue
            candidates.add(
                Candidate(
                    null,
                    distance.value,
                    if (distance.kind == MeasureKind.METERS) DistanceUnit.METERS else DistanceUnit.KM,
                    distance.box,
                    distance.raw
                )
            )
        }

        return candidates.sortedBy { it.box.top }
    }

    /** Dos mediciones pertenecen al mismo globo si están apiladas y alineadas. */
    private fun sameBubble(a: Box, b: Box): Boolean {
        val dx = kotlin.math.abs(a.centerX - b.centerX)
        if (dx > MAX_BUBBLE_X) return false

        val verticalGap = when {
            b.top >= a.top -> b.top - a.bottom
            else -> a.top - b.bottom
        }
        return verticalGap in -30..MAX_BUBBLE_V
    }

    // ------------------------------------------------------------------ Color

    /**
     * Clasifica el color de fondo del globo leyendo los píxeles de su
     * rectángulo: el texto es blanco sobre verde o azul sólido, mientras que
     * las etiquetas del mapa van sobre fondos claros o grises.
     */
    fun classifyColor(bitmap: Bitmap, box: Box): BadgeColor {
        val left = box.left.coerceAtLeast(0)
        val top = box.top.coerceAtLeast(0)
        val right = box.right.coerceAtMost(bitmap.width)
        val bottom = box.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return BadgeColor.OTHER

        var green = 0
        var blue = 0
        var red = 0
        var orange = 0
        var white = 0
        var gray = 0
        var total = 0

        val step = 2
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                when {
                    g > 95 && g > r + 18 && g > b + 18 -> green++
                    b > 95 && b > r + 18 && b > g + 4 -> blue++
                    r > 145 && g in 65..170 && b < 95 -> orange++
                    r > 125 && r > g + 40 && r > b + 40 -> red++
                    r > 195 && g > 195 && b > 195 -> white++
                    maxOf(r, g, b) - minOf(r, g, b) < 32 -> gray++
                }
                total++
                x += step
            }
            y += step
        }

        if (total == 0) return BadgeColor.OTHER

        val greenShare = green.toDouble() / total
        val blueShare = blue.toDouble() / total
        val orangeShare = orange.toDouble() / total
        val redShare = red.toDouble() / total
        val whiteShare = white.toDouble() / total
        val grayShare = gray.toDouble() / total

        return when {
            greenShare >= 0.18 && greenShare >= blueShare -> BadgeColor.GREEN
            blueShare >= 0.18 -> BadgeColor.BLUE
            orangeShare >= 0.18 -> BadgeColor.ORANGE
            redShare >= 0.18 -> BadgeColor.RED
            whiteShare >= 0.5 -> BadgeColor.WHITE
            grayShare >= 0.5 -> BadgeColor.GRAY
            else -> BadgeColor.OTHER
        }
    }
}
