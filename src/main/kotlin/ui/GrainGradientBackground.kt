package ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Paint as SkiaPaint

/**
 * GrainGradient Composable implementing the visual parameters from @componentry/grain-gradient:
 * - colorLight = "#dce5df" (Color(0xFFDCE5DF))
 * - colorMid = "#83b9ad"   (Color(0xFF83B9AD))
 * - colorDark = "#031419"  (Color(0xFF031419))
 * - angle = 0
 * - position = 0
 * - curve = 0.48
 * - softness = 0.13
 * - scale = 1
 * - grain = 0.32
 * - grainSize = 1
 * - seed = 1
 * - speed = 1
 */
@Composable
fun GrainGradientBackground(
    modifier: Modifier = Modifier,
    colorLight: Color = Color(0xFFDCE5DF),
    colorMid: Color = Color(0xFF83B9AD),
    colorDark: Color = Color(0xFF031419),
    angle: Float = 0f,
    position: Float = 0f,
    curve: Float = 0.48f,
    softness: Float = 0.13f,
    scale: Float = 1f,
    grain: Float = 0.32f,
    grainSize: Float = 1f,
    seed: Float = 1f,
    speed: Float = 1f,
    content: @Composable () -> Unit
) {
    var elapsedSeconds by remember { mutableStateOf(0f) }

    LaunchedEffect(speed) {
        var lastTimeNanos = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { frameTimeMillis ->
                val currentNanos = frameTimeMillis * 1_000_000L
                if (lastTimeNanos != 0L) {
                    val deltaSeconds = (currentNanos - lastTimeNanos) / 1_000_000_000f
                    elapsedSeconds += deltaSeconds.coerceAtMost(0.05f) * speed
                }
                lastTimeNanos = currentNanos
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (width <= 0 || height <= 0) return@Canvas

            try {
                val skiaShader = GrainGradientShader.makeShader(
                    width = width,
                    height = height,
                    time = elapsedSeconds,
                    ratio = 1f,
                    colorLight = colorLight,
                    colorMid = colorMid,
                    colorDark = colorDark,
                    angle = angle,
                    position = position,
                    curve = curve,
                    softness = softness,
                    scale = scale,
                    grain = grain,
                    grainSize = grainSize,
                    seed = seed
                )

                val skiaPaint = SkiaPaint().apply {
                    this.shader = skiaShader
                }

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(
                        org.jetbrains.skia.Rect.makeWH(width, height),
                        skiaPaint
                    )
                }
            } catch (e: Throwable) {
                // Fallback graceful linear gradient if SkSL runtime effect fails
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(colorMid, colorLight, colorDark),
                        start = Offset(0f, 0f),
                        end = Offset(width, height)
                    ),
                    size = Size(width, height)
                )
            }
        }

        content()
    }
}
