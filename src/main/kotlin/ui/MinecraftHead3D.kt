package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a 3D isometric Minecraft player head extracted from the skin texture.
 * Handles both standard 64x64/64x32 skins and procedural default skins (Steve/Alex).
 */
@Composable
fun MinecraftHead3D(
    skinUrl: String?,
    playerName: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    var skinBitmap by remember(skinUrl, playerName) { mutableStateOf<BufferedImage?>(null) }

    LaunchedEffect(skinUrl, playerName) {
        withContext(Dispatchers.IO) {
            val img = if (!skinUrl.isNullOrBlank()) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder().url(skinUrl).build()
                    client.newCall(req).execute().use { res ->
                        res.body?.byteStream()?.let { ImageIO.read(it) }
                    }
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            skinBitmap = img ?: generateDefaultSteveSkin(playerName)
        }
    }

    val headMesh = remember(skinBitmap) {
        skinBitmap?.let { extractHeadCube(it) }
    }

    Canvas(modifier = modifier.size(size)) {
        if (headMesh == null) return@Canvas

        val w = this.size.width
        val h = this.size.height

        // Isometric projection:
        // Center of the cube is at (w/2, h/2)
        // We draw:
        // Top face (Y = 1), Right face (X = 1), Front face (Z = 1)
        val cx = w / 2f
        val cy = h / 2f
        val radius = w * 0.42f

        // Isometric unit vectors:
        // X axis: down-right (30 deg)
        // Z axis: down-left (150 deg)
        // Y axis: straight up (-90 deg)
        val rad30 = Math.toRadians(30.0)
        val cos30 = cos(rad30).toFloat()
        val sin30 = sin(rad30).toFloat()

        // 8x8 grid for each visible face
        fun project(x: Float, y: Float, z: Float): Offset {
            // x in [-0.5, 0.5], y in [-0.5, 0.5], z in [-0.5, 0.5]
            val px = cx + (x * cos30 - z * cos30) * radius
            val py = cy + (x * sin30 + z * sin30 - y) * radius
            return Offset(px, py)
        }

        // Draw Front Face (Z = 0.5, X from -0.5 to 0.5, Y from -0.5 to 0.5)
        for (gridY in 0 until 8) {
            for (gridX in 0 until 8) {
                val col = headMesh.front[gridY * 8 + gridX]
                if (col.alpha > 0.05f) {
                    val x0 = -0.5f + gridX / 8f
                    val x1 = -0.5f + (gridX + 1) / 8f
                    val y0 = 0.5f - gridY / 8f
                    val y1 = 0.5f - (gridY + 1) / 8f
                    val z = 0.5f

                    val p1 = project(x0, y0, z)
                    val p2 = project(x1, y0, z)
                    val p3 = project(x1, y1, z)
                    val p4 = project(x0, y1, z)

                    val path = Path().apply {
                        moveTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        lineTo(p3.x, p3.y)
                        lineTo(p4.x, p4.y)
                        close()
                    }
                    // Front face: direct light (1.0x)
                    drawPath(path, col)
                }
            }
        }

        // Draw Right Face (X = 0.5, Z from 0.5 to -0.5, Y from -0.5 to 0.5)
        for (gridY in 0 until 8) {
            for (gridZ in 0 until 8) {
                val col = headMesh.right[gridY * 8 + gridZ]
                if (col.alpha > 0.05f) {
                    val z0 = 0.5f - gridZ / 8f
                    val z1 = 0.5f - (gridZ + 1) / 8f
                    val y0 = 0.5f - gridY / 8f
                    val y1 = 0.5f - (gridY + 1) / 8f
                    val x = 0.5f

                    val p1 = project(x, y0, z0)
                    val p2 = project(x, y0, z1)
                    val p3 = project(x, y1, z1)
                    val p4 = project(x, y1, z0)

                    val path = Path().apply {
                        moveTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        lineTo(p3.x, p3.y)
                        lineTo(p4.x, p4.y)
                        close()
                    }
                    // Right face: subtle shade (0.8x)
                    val shaded = Color(col.red * 0.82f, col.green * 0.82f, col.blue * 0.82f, col.alpha)
                    drawPath(path, shaded)
                }
            }
        }

        // Draw Top Face (Y = 0.5, X from -0.5 to 0.5, Z from -0.5 to 0.5)
        for (gridZ in 0 until 8) {
            for (gridX in 0 until 8) {
                val col = headMesh.top[gridZ * 8 + gridX]
                if (col.alpha > 0.05f) {
                    val x0 = -0.5f + gridX / 8f
                    val x1 = -0.5f + (gridX + 1) / 8f
                    val z0 = -0.5f + gridZ / 8f
                    val z1 = -0.5f + (gridZ + 1) / 8f
                    val y = 0.5f

                    val p1 = project(x0, y, z0)
                    val p2 = project(x1, y, z0)
                    val p3 = project(x1, y, z1)
                    val p4 = project(x0, y, z1)

                    val path = Path().apply {
                        moveTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        lineTo(p3.x, p3.y)
                        lineTo(p4.x, p4.y)
                        close()
                    }
                    // Top face: bright ambient light (1.15x)
                    val lightCol = Color(
                        (col.red * 1.15f).coerceAtMost(1f),
                        (col.green * 1.15f).coerceAtMost(1f),
                        (col.blue * 1.15f).coerceAtMost(1f),
                        col.alpha
                    )
                    drawPath(path, lightCol)
                }
            }
        }
    }
}

class HeadCubeData(
    val front: List<Color>,
    val right: List<Color>,
    val top: List<Color>
)

private fun extractHeadCube(image: BufferedImage): HeadCubeData {
    // Skin layout:
    // Head Base:
    // Top: (8, 0) -> 8x8
    // Right: (16, 8) -> 8x8
    // Front: (8, 8) -> 8x8
    // Head Overlay (Hat layer):
    // Top: (40, 0) -> 8x8
    // Right: (48, 8) -> 8x8
    // Front: (40, 8) -> 8x8

    fun readPixel(x: Int, y: Int): Color {
        if (x !in 0 until image.width || y !in 0 until image.height) return Color.Transparent
        val argb = image.getRGB(x, y)
        val a = ((argb shr 24) and 0xFF) / 255f
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return Color(r, g, b, a)
    }

    fun blend(base: Color, overlay: Color): Color {
        if (overlay.alpha < 0.1f) return base
        if (overlay.alpha >= 0.99f) return overlay
        val a = overlay.alpha + base.alpha * (1f - overlay.alpha)
        val r = overlay.red * overlay.alpha + base.red * (1f - overlay.alpha)
        val g = overlay.green * overlay.alpha + base.green * (1f - overlay.alpha)
        val b = overlay.blue * overlay.alpha + base.blue * (1f - overlay.alpha)
        return Color(r, g, b, a)
    }

    val frontColors = mutableListOf<Color>()
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val base = readPixel(8 + x, 8 + y)
            val hat = readPixel(40 + x, 8 + y)
            frontColors.add(blend(base, hat))
        }
    }

    val rightColors = mutableListOf<Color>()
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val base = readPixel(16 + x, 8 + y)
            val hat = readPixel(48 + x, 8 + y)
            rightColors.add(blend(base, hat))
        }
    }

    val topColors = mutableListOf<Color>()
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val base = readPixel(8 + x, y)
            val hat = readPixel(40 + x, y)
            topColors.add(blend(base, hat))
        }
    }

    return HeadCubeData(frontColors, rightColors, topColors)
}

/**
 * Generates a standard default Steve skin bitmap if none is provided.
 */
private fun generateDefaultSteveSkin(name: String): BufferedImage {
    val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()

    // Skin tones
    val skinTone = java.awt.Color(0xb5, 0x88, 0x68)
    val hair = java.awt.Color(0x4a, 0x32, 0x1e)
    val eyes = java.awt.Color(0x2d, 0x1b, 0x5a)
    val white = java.awt.Color(0xff, 0xff, 0xff)
    val mouth = java.awt.Color(0x61, 0x39, 0x24)

    // Fill head front base
    g.color = skinTone
    g.fillRect(8, 8, 8, 8)
    // Hair on front
    g.color = hair
    g.fillRect(8, 8, 8, 2)
    g.fillRect(8, 10, 1, 1)
    g.fillRect(15, 10, 1, 1)
    // Eyes
    g.color = white
    g.fillRect(9, 12, 1, 1)
    g.fillRect(14, 12, 1, 1)
    g.color = eyes
    g.fillRect(10, 12, 1, 1)
    g.fillRect(13, 12, 1, 1)
    // Mouth / Nose
    g.color = mouth
    g.fillRect(11, 13, 2, 1)
    g.fillRect(10, 14, 4, 1)

    // Right face
    g.color = skinTone
    g.fillRect(16, 8, 8, 8)
    g.color = hair
    g.fillRect(16, 8, 8, 3)
    g.fillRect(16, 11, 2, 2)

    // Top face
    g.color = hair
    g.fillRect(8, 0, 8, 8)

    g.dispose()
    return img
}
