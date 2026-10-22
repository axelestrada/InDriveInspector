package com.axelestrada.indriveinspector.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.axelestrada.indriveinspector.ui.theme.Lime
import com.axelestrada.indriveinspector.ui.theme.Outline
import com.axelestrada.indriveinspector.ui.theme.Surface1
import com.axelestrada.indriveinspector.ui.theme.TextHigh
import com.axelestrada.indriveinspector.ui.theme.TextLow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Duración del vaciado cuando se suelta antes de tiempo. */
private const val RESET_MILLIS = 300

/**
 * Botón de inicio "tipo agua".
 *
 * - Hay que **mantener pulsado** hasta que se llene: primero sube el líquido
 *   y después se llena el borde circular. Al completarse el borde se activa
 *   (sin esperar a soltar el dedo).
 * - Si se suelta antes, se vacía y no pasa nada.
 * - El texto tiene dos capas: la clara (siempre) y un duplicado oscuro que
 *   aparece conforme el líquido lo va cubriendo.
 */
@Composable
fun HoldToStartButton(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onStop: (() -> Unit)? = null,
    resetSignal: Int = 0,
    durationMillis: Int = 1600,
    label: String = "Iniciar",
    sublabel: String = "mantén pulsado"
) {
    var pressed by remember { mutableStateOf(false) }
    val liquid = remember { Animatable(if (active) 1f else 0f) }
    val ring = remember { Animatable(if (active) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    // Al activarse el servicio, el botón queda lleno
    LaunchedEffect(active) {
        if (active) {
            coroutineScope {
                launch { liquid.animateTo(1f, tween(400, easing = LinearEasing)) }
                launch { ring.animateTo(1f, tween(400, easing = LinearEasing)) }
            }
        } else {
            coroutineScope {
                launch { liquid.animateTo(0f, tween(300, easing = LinearEasing)) }
                launch { ring.animateTo(0f, tween(300, easing = LinearEasing)) }
            }
        }
    }

    // Si la activación falla (por ejemplo, se deniega el permiso), se vacía
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) {
            coroutineScope {
                launch { liquid.animateTo(0f, tween(320, easing = LinearEasing)) }
                launch { ring.animateTo(0f, tween(320, easing = LinearEasing)) }
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val titleText = if (active) "Detener" else label
    val subText = when {
        active -> "toca para parar"
        pressed -> "suelta para cancelar"
        else -> sublabel
    }

    // Los estilos se leen fuera del Canvas (son @Composable)
    val titleStyle = MaterialTheme.typography.headlineSmall
    val subStyle = MaterialTheme.typography.labelSmall

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(active) {
                    if (active) {
                        // Un solo toque detiene
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            waitForUpOrCancellation()
                            onStop?.invoke()
                        }
                    } else {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true

                            val job = scope.launch {
                                // El líquido y el borde se llenan A LA VEZ
                                // y terminan el mismo instante.
                                liquid.snapTo(0f)
                                ring.snapTo(0f)
                                coroutineScope {
                                    launch {
                                        liquid.animateTo(
                                            targetValue = 1f,
                                            animationSpec = tween(
                                                durationMillis,
                                                easing = LinearEasing
                                            )
                                        )
                                    }
                                    launch {
                                        ring.animateTo(
                                            targetValue = 1f,
                                            animationSpec = tween(
                                                durationMillis,
                                                easing = LinearEasing
                                            )
                                        )
                                    }
                                }
                                // Ambos llenos: se activa sin esperar al dedo
                                onCompleted()
                            }

                            waitForUpOrCancellation()
                            pressed = false

                            if (ring.value < 1f) {
                                // Soltado antes de tiempo: se vacía (liquid y
                                // borde a la vez, sin retardos entre ellos)
                                job.cancel()
                                scope.launch {
                                    coroutineScope {
                                        launch {
                                            liquid.animateTo(
                                                0f,
                                                tween(RESET_MILLIS, easing = LinearEasing)
                                            )
                                        }
                                        launch {
                                            ring.animateTo(
                                                0f,
                                                tween(RESET_MILLIS, easing = LinearEasing)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            val base = radius * 0.66f
            val level = liquid.value
            val ringLevel = ring.value

            // Ondas que invitan a pulsar
            if (!pressed) {
                repeat(3) { i ->
                    val t = (phase + i * 0.34f) % 1f
                    drawCircle(
                        color = if (active) {
                            Color.Black.copy(alpha = 0.16f * (1f - t))
                        } else {
                            Lime.copy(alpha = 0.16f * (1f - t))
                        },
                        radius = base * (0.95f + 0.5f * t),
                        center = center,
                        style = Stroke(width = if (active) 2.dp.toPx() else 1.5.dp.toPx())
                    )
                }
            }

            // Base del botón
            drawCircle(color = Surface1, radius = base, center = center)

            // Patrón de puntos
            drawDotPattern(
                color = Lime.copy(alpha = 0.20f),
                center = center,
                radius = base,
                spacing = 13.dp.toPx(),
                dotRadius = 1.4.dp.toPx()
            )

            // --- Texto: capa clara (siempre visible) ---
            val titleLayout = textMeasurer.measure(
                AnnotatedString(titleText),
                titleStyle
            )
            val subLayout = textMeasurer.measure(
                AnnotatedString(subText),
                subStyle
            )
            val gap = 3.dp.toPx()
            val totalHeight = titleLayout.size.height + gap + subLayout.size.height
            val titlePos = Offset(
                x = (size.width - titleLayout.size.width) / 2f,
                y = (size.height - totalHeight) / 2f
            )
            val subPos = Offset(
                x = (size.width - subLayout.size.width) / 2f,
                y = titlePos.y + titleLayout.size.height + gap
            )

            drawText(titleLayout, color = TextHigh, topLeft = titlePos)
            drawText(subLayout, color = TextLow, topLeft = subPos)

            // --- Líquido ---
            val liquidPath = Path()
            if (level > 0f) {
                // El frente va del fondo del círculo (nivel 0) a su parte
                // superior (nivel 1): así no queda ni una banda de color
                // cuando está vacío y sube a la misma velocidad que el borde.
                val top = center.y + base - 2f * base * level
                val amp = 4.dp.toPx() * level
                val period = 52.dp.toPx()
                val step = 3.dp.toPx()
                liquidPath.moveTo(0f, top)
                var x = 0f
                while (x <= size.width) {
                    val y = top + sin((x / period) * 2f * PI.toFloat() + phase * 2f * PI.toFloat()) * amp
                    liquidPath.lineTo(x, y)
                    x += step
                }
                liquidPath.lineTo(size.width, size.height)
                liquidPath.lineTo(0f, size.height)
                liquidPath.close()

                clipPath(circlePath(center, base)) {
                    drawPath(liquidPath, color = Lime)
                }

                // --- Duplicado del texto, revelado por el líquido ---
                clipPath(liquidPath) {
                    clipPath(circlePath(center, base)) {
                        drawText(
                            titleLayout,
                            color = Color.Black.copy(alpha = 0.88f),
                            topLeft = titlePos
                        )
                        drawText(
                            subLayout,
                            color = Color.Black.copy(alpha = 0.6f),
                            topLeft = subPos
                        )
                    }
                }
            }

            // --- Borde ---
            drawCircle(
                color = if (pressed) Lime else Outline,
                radius = base,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // --- Anillo: se llena DESPUÉS del líquido ---
            if (ringLevel > 0f) {
                val r = base + 8.dp.toPx()
                drawArc(
                    color = Lime.copy(alpha = 0.95f),
                    startAngle = -90f,
                    sweepAngle = 360f * ringLevel,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

private fun circlePath(center: Offset, radius: Float): Path = Path().apply {
    addOval(
        Rect(
            center.x - radius,
            center.y - radius,
            center.x + radius,
            center.y + radius
        )
    )
}

/**
 * Retícula de puntos (halftone) recortada al círculo del botón.
 */
private fun DrawScope.drawDotPattern(
    color: Color,
    center: Offset,
    radius: Float,
    spacing: Float,
    dotRadius: Float
) {
    if (color.alpha <= 0f) return
    clipPath(circlePath(center, radius)) {
        var y = center.y - radius
        while (y <= center.y + radius) {
            var x = center.x - radius
            while (x <= center.x + radius) {
                drawCircle(color = color, radius = dotRadius, center = Offset(x, y))
                x += spacing
            }
            y += spacing
        }
    }
}
