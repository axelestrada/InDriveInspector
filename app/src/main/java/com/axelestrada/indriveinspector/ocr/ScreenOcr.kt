package com.axelestrada.indriveinspector.ocr

import android.graphics.Bitmap
import com.axelestrada.indriveinspector.core.Box
import com.axelestrada.indriveinspector.core.MapBadge
import com.axelestrada.indriveinspector.core.OCR_NUMBER
import com.axelestrada.indriveinspector.core.parseOcrNumber

/**
 * Todo lo que se puede leer de UNA captura de pantalla con OCR.
 *
 * Ya no se usa la accesibilidad: el mapa, los precios y los botones de inDrive
 * se leen directamente de los píxeles.
 */
data class ScreenSnapshot(
    val isRideScreen: Boolean = false,
    val currency: String = "",
    val offerPrice: Double? = null,
    val alternatives: List<Double> = emptyList(),
    val badges: List<MapBadge> = emptyList(),
    /** Distancia hasta el punto de recojo (la del panel, "~1,6 km" / "735 m"). */
    val panelDistanceKm: Double? = null,
    /** Minutos desde que se publicó la solicitud ("5 min."). */
    val postedMinutes: Int? = null,
    val rawText: String = "",
    val error: String? = null
) {
    /** Huella de lo que se ve: si no cambia, es la misma oferta. */
    fun fingerprint(): String =
        "$isRideScreen|$currency|$offerPrice|${alternatives.joinToString(",")}|$panelDistanceKm|" +
            badges.joinToString(";") { "${it.minutes}:${it.distance}:${it.distanceUnit}" }
}

/** Datos leídos del texto de la pantalla (sin depender del mapa). */
data class ParsedScreen(
    val isRideScreen: Boolean = false,
    val currency: String = "",
    val offerPrice: Double? = null,
    val alternatives: List<Double> = emptyList(),
    val panelDistanceKm: Double? = null,
    val postedMinutes: Int? = null,
    val rawText: String = ""
)

/**
 * Analiza la pantalla completa de inDrive a partir de una imagen.
 */
object ScreenOcr {

    // Perezoso: así la parte de texto se puede testear en la JVM sin ML Kit
    private val engine by lazy { OcrEngine() }

    /**
     * Máximo de precios distintos que puede tener la pantalla de UNA carrera
     * (el de la oferta + las alternativas). Por encima de esto ya es la lista.
     */
    private const val MAX_PRICES_IN_RIDE = 6

    /**
     * Factor de reducción para el OCR. 0,7x es unas dos veces más rápido y
     * se lee igual de bien; si el mapa se resiente, el reintento a resolución
     * completa cubre el caso.
     */
    private const val OCR_SCALE = 0.85f
    private val CURRENCIES = setOf(
        "HNL", "HTG", "MXN", "USD", "COP", "ARS", "CLP", "PEN", "BRL", "DOP", "GTQ",
        "CRC", "PAB", "VES", "EUR", "BOB", "PYG", "UYU", "NIO", "JMD", "TTD", "BBD", "CAD"
    )

    private val CURRENCY_PRICE_RE =
        Regex("""([A-Za-z]{2,4})[\s.]*($OCR_NUMBER)""")
    private val NUMBER_AFTER_RE = Regex("""^[\s.]*($OCR_NUMBER)""")
    private val SYMBOL_PRICE_RE =
        Regex("""([$€£])\s*($OCR_NUMBER)""")
    private val KM_RE = Regex("""($OCR_NUMBER)\s*km\b""", RegexOption.IGNORE_CASE)
    private val METER_RE = Regex(
        """($OCR_NUMBER)\s*(?:mts?|mt|mtr|metro|metros|m)\b""",
        RegexOption.IGNORE_CASE
    )
    private val MINUTES_RE = Regex("""($OCR_NUMBER)\s*min\b""", RegexOption.IGNORE_CASE)

    /**
     * Analiza la pantalla en dos mitades y a **resolución reducida**.
     *
     * OCR a 0,7x es ~2 veces más rápido que a resolución completa y con el
     * texto de inDrive (botones grandes) se lee igual de bien. Si aun así no
     * se encuentra ningún globo, se reintenta a resolución completa: el mapa
     * tiene el texto más pequeño ("19 min", "6,7 km") y es lo único que
     * necesita el Double Pass.
     *
     * @param onPartial recibe el resultado parcial en cuanto se sabe el
     *        precio, para poder publicar antes de terminar el mapa.
     */
    suspend fun analyze(
        frame: Bitmap,
        onPartial: ((ScreenSnapshot) -> Unit)? = null
    ): ScreenSnapshot {
        val scaled = scale(frame, OCR_SCALE)
        val fast = analyzeScaled(scaled, onPartial)

        // Sin globos: se reintenta con la imagen original
        if (fast.isRideScreen && fast.badges.isEmpty()) {
            return analyzeScaled(frame, onPartial)
        }
        return fast
    }

    /** Reduce la imagen antes de pasarla al OCR. */
    private fun scale(source: Bitmap, factor: Float): Bitmap {
        if (factor >= 0.99f) return source
        val w = (source.width * factor).toInt().coerceAtLeast(1)
        val h = (source.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * Analiza la pantalla en dos mitades.
     *
     * La mitad **inferior** (el panel con el precio y los botones) es la que
     * dice si es una carrera, y se analiza primero: así el widget se actualiza
     * con el precio en la mitad del tiempo. La mitad **superior** (el mapa) se
     * analiza después, para los globos de distancia y tiempo.
     */
    private suspend fun analyzeScaled(
        frame: Bitmap,
        onPartial: ((ScreenSnapshot) -> Unit)? = null
    ): ScreenSnapshot {
        val half = frame.height / 2

        // ---- Mitad inferior: ¿es una carrera? ¿cuánto vale? ----
        val bottomCrop = Bitmap.createBitmap(
            frame, 0, half, frame.width, frame.height - half
        )
        val bottomOcr = engine.recognize(bottomCrop)
        if (bottomOcr.lines.isEmpty()) {
            return ScreenSnapshot(error = "No se reconoció texto en la pantalla")
        }

        val bottomLines = bottomOcr.lines.map { line ->
            line.copy(
                box = line.box.copy(
                    top = line.box.top + half,
                    bottom = line.box.bottom + half
                )
            )
        }
        val parsed = parseLines(bottomLines)

        if (!parsed.isRideScreen) {
            return ScreenSnapshot(
                isRideScreen = false,
                rawText = parsed.rawText
            )
        }

        // Se publica ya: precio, alternativas y datos del panel.
        onPartial?.invoke(
            ScreenSnapshot(
                isRideScreen = true,
                currency = parsed.currency,
                offerPrice = parsed.offerPrice,
                alternatives = parsed.alternatives,
                panelDistanceKm = parsed.panelDistanceKm,
                postedMinutes = parsed.postedMinutes,
                rawText = parsed.rawText
            )
        )

        // ---- Mitad superior: el mapa y sus globos ----
        val topCrop = Bitmap.createBitmap(frame, 0, 0, frame.width, half)
        val topOcr = engine.recognize(topCrop)

        val badges = colorizeBadges(topOcr.elements, topCrop) +
            colorizeBadges(bottomOcr.elements, bottomCrop)

        return ScreenSnapshot(
            isRideScreen = true,
            currency = parsed.currency,
            offerPrice = parsed.offerPrice,
            alternatives = parsed.alternatives,
            badges = badges,
            panelDistanceKm = parsed.panelDistanceKm,
            postedMinutes = parsed.postedMinutes,
            rawText = parsed.rawText
        )
    }

    /** Calcula el color de fondo de cada globo y se queda con los de color. */
    private fun colorizeBadges(
        elements: List<OcrElement>,
        bitmap: Bitmap
    ): List<MapBadge> = MapBadgeParser.candidates(elements).map { candidate ->
        MapBadge(
            minutes = candidate.minutes?.toInt(),
            distance = candidate.distance,
            distanceUnit = candidate.distanceUnit,
            color = MapBadgeParser.classifyColor(bitmap, candidate.box),
            text = candidate.raw,
            box = candidate.box
        )
    }.filter { it.isSaturated }

    /**
     * Lee el texto de la pantalla. Puro (sin bitmap), para poder testearlo.
     */
    fun parseLines(lines: List<OcrLine>): ParsedScreen {
        // ---------- Precios (primero: nos sirven para detectar la lista) ----------
        val priceLines = lines.mapNotNull { line ->
            parsePrice(line.text)?.let { (amount, currency) -> PriceHit(amount, currency, line.box) }
        }
        val distinctPrices = priceLines.map { it.amount }.distinct().size

        // ---------- 1) ¿Es la pantalla de UNA carrera? ----------
        // La lista de carreras tiene un "Aceptar" por cada tarjeta y muchos
        // precios, así que hay que ser estrictos o el widget calcularía
        // cualquier tontería con la lista.
        //
        // NO se usa el título "Solicitud de viaje" como señal: el mapa puede
        // taparlo y entonces no se leería. Las señales fiables son:
        //   - "Ofrece tu tarifa"  (sólo aparece en una carrera)
        //   - "Aceptar"           (el botón de aceptar de la carrera)
        val normalized = lines.map { normalize(it.text) }

        val hasOfferLabel = normalized.any { it.contains("ofrece tu tarifa") }
        val hasAccept = normalized.any { it.contains("aceptar") }

        // Una carrera tiene el precio de la oferta y como mucho un par de
        // alternativas; una lista muestra el precio de cada tarjeta.
        val looksLikeList = distinctPrices > MAX_PRICES_IN_RIDE

        val isRide = !looksLikeList && (hasOfferLabel || hasAccept)

        // ---------- 2) El precio de la oferta ----------
        val acceptLine = lines.firstOrNull {
            normalize(it.text).contains("aceptar") && parsePrice(it.text) != null
        }
        val offerFromButton = acceptLine?.let { parsePrice(it.text) }

        // El precio grande del panel es el de la tipografía más grande de los
        // que están por encima del botón "Aceptar".
        val aboveAccept = priceLines.filter {
            acceptLine == null || it.box.bottom <= acceptLine.box.bottom
        }
        val panelPrice = aboveAccept.maxByOrNull { it.box.height }

        val offerPrice = offerFromButton?.first ?: panelPrice?.amount
        val currency = offerFromButton?.second ?: panelPrice?.currency.orEmpty()

        // Las alternativas son los precios por debajo del botón "Aceptar";
        // si no se encuentra, se usan los que están bajo el precio del panel.
        val referenceBottom = acceptLine?.box?.bottom ?: panelPrice?.box?.bottom
        val alternatives = priceLines
            .filter { referenceBottom == null || it.box.top > referenceBottom }
            .map { it.amount }
            .filter { it != offerPrice }
            .distinct()

        // ---------- 3) Datos del panel (distancia y minutos) ----------
        // Se toma lo que esté más cerca del precio del panel: así no se cuela
        // un globo del mapa ("3 min", "1,6 km") que esté más arriba.
        val panelDistanceKm = lines
            .mapNotNull { line -> parseDistanceKm(line.text)?.let { d -> line to d } }
            .minByOrNull { (line, _) -> distanceTo(panelPrice, line) }
            ?.second

        val postedMinutes = lines
            .mapNotNull { line ->
                MINUTES_RE.find(line.text)?.groupValues?.get(1)?.toIntOrNull()?.let { m -> line to m }
            }
            .minByOrNull { (line, _) -> distanceTo(panelPrice, line) }
            ?.second

        return ParsedScreen(
            isRideScreen = isRide,
            currency = currency,
            offerPrice = offerPrice,
            alternatives = alternatives,
            panelDistanceKm = panelDistanceKm,
            postedMinutes = postedMinutes,
            rawText = lines.joinToString(" | ") { it.text }
        )
    }

    /** Distancia vertical entre el centro de una línea y el del precio del panel. */
    private fun distanceTo(panelPrice: PriceHit?, line: OcrLine): Int {
        if (panelPrice == null) return 0
        val a = panelPrice.box.centerY
        val b = line.box.centerY
        return kotlin.math.abs(a - b)
    }

    private data class PriceHit(val amount: Double, val currency: String, val box: Box)

    /** Normaliza para comparar: minúsculas y sin espacios extra. */
    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""\s+"""), " ").trim()

    /**
     * Extrae (importe, moneda) de un texto. Sólo acepta monedas conocidas o
     * el símbolo $, para no confundir "5 min" con un precio.
     *
     * Se busca la monedaKNOWN y el número que va justo detrás, en vez de un
     * regex libre: así "HNLlO5" se lee como HNL + 105 y no como "HNLl" + 05.
     */
    private fun parsePrice(text: String): Pair<Double, String>? {
        SYMBOL_PRICE_RE.find(text)?.let { m ->
            val amount = parseOcrNumber(m.groupValues[2])
            if (amount != null) return amount to m.groupValues[1]
        }

        val upper = text.uppercase()
        for (code in CURRENCIES) {
            var from = 0
            while (true) {
                val idx = upper.indexOf(code, from)
                if (idx < 0) break
                from = idx + 1
                val rest = text.substring(idx + code.length)
                val numMatch = NUMBER_AFTER_RE.find(rest) ?: continue
                val amount = parseOcrNumber(numMatch.groupValues[1]) ?: continue
                return amount to code
            }
        }
        return null
    }

    /** Distancia en km, aceptando metros ("735 metro" -> 0,735 km). */
    private fun parseDistanceKm(text: String): Double? {
        KM_RE.find(text)?.let { m ->
            return parseOcrNumber(m.groupValues[1])
        }
        METER_RE.find(text)?.let { m ->
            return parseOcrNumber(m.groupValues[1])?.div(1000.0)
        }
        return null
    }
}
