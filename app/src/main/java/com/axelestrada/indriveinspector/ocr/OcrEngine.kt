package com.axelestrada.indriveinspector.ocr

import android.graphics.Bitmap
import com.axelestrada.indriveinspector.core.Box
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Palabra reconocida con su posición. */
data class OcrElement(
    val text: String,
    val box: Box
)

/** Línea de texto reconocida (agrupa palabras: "Solicitud de viaje"). */
data class OcrLine(
    val text: String,
    val box: Box
)

/** Todo lo que devuelve el OCR de una imagen. */
data class OcrResult(
    val lines: List<OcrLine>,
    val elements: List<OcrElement>
)

/** Envoltorio sobre el OCR de ML Kit (on-device, sin Play Services). */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = mutableListOf<OcrLine>()
                    val elements = mutableListOf<OcrElement>()

                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox
                            if (box != null) {
                                lines.add(OcrLine(line.text, Box(box.left, box.top, box.right, box.bottom)))
                            }
                            for (element in line.elements) {
                                val r = element.boundingBox ?: continue
                                elements.add(
                                    OcrElement(element.text, Box(r.left, r.top, r.right, r.bottom))
                                )
                            }
                        }
                    }

                    if (cont.isActive) cont.resume(OcrResult(lines, elements))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(OcrResult(emptyList(), emptyList()))
                }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resume(OcrResult(emptyList(), emptyList()))
                }
        }

    fun close() {
        recognizer.close()
    }
}
