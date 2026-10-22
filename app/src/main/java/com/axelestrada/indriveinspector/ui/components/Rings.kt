package com.axelestrada.indriveinspector.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.axelestrada.indriveinspector.ui.theme.Ink
import com.axelestrada.indriveinspector.ui.theme.Lime
import com.axelestrada.indriveinspector.ui.theme.Mint
import com.axelestrada.indriveinspector.ui.theme.Sky

/**
 * Anillo de progreso circular con el contenido en el centro.
 * Es el elemento gráfico central del widget y de la app.
 */
@Composable
fun RingProgress(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 7.dp,
    trackColor: Color = Color.White.copy(alpha = 0.10f),
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val target = progress.coerceIn(0f, 1f)
    val value by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (animate) 450 else 0),
        label = "ring"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val diameter = minOf(size.width, size.height) - stroke
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (value > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // referencia mínima: la línea no debe tocar el centro
            if (inset < 0) Unit
        }
        content()
    }
}

/**
 * Fondo con las manchas de color (azul → verde) difuminadas.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.background(Ink)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Sky.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.02f),
                    radius = size.minDimension * 0.8f
                ),
                radius = size.minDimension * 0.8f,
                center = Offset(size.width * 0.92f, size.height * 0.02f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Mint.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(size.width * 0.02f, size.height * 0.30f),
                    radius = size.minDimension * 0.75f
                ),
                radius = size.minDimension * 0.75f,
                center = Offset(size.width * 0.02f, size.height * 0.30f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Lime.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.75f, size.height * 0.95f),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(size.width * 0.75f, size.height * 0.95f)
            )
        }
        content()
    }
}
