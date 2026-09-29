package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val DEFAULT_GLOW = Color(0xFF8FD8FF)
private val UNLIT_CENTRE = Color(0xFFD9DCE0)

private fun ledColor(rgb: Int): Color? = if (rgb and 0xFFFFFF == 0) null else Color(0xFF000000.toInt() or rgb)

/** Polls the colours the game sets on the portal's lights: left, right, trap. */
@Composable
private fun rememberPortalLedColors(): State<IntArray> = produceState(IntArray(3)) {
    while (true) {
        val colors = runCatching { NativeEmulatedUSBDevices.getSkylanderPortalColors() }.getOrNull()
        if (colors != null && colors.size == 3 && !colors.contentEquals(value)) {
            value = colors
        }
        delay(500)
    }
}

/**
 * The Portal of Power: a ring of stone bricks with rune marks around a glowing centre, with the
 * figures that are on the portal standing in the middle.
 *
 * The glow takes the colour the game sets on the portal's lights, like the real portal. With
 * [isGlowEnabled] off the portal is drawn unlit and nothing is animated.
 */
@Composable
fun PortalOfPower(
    figuresOnPortal: List<PortalFigure>,
    artIndex: Map<String, File>,
    isGlowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val ledColors by rememberPortalLedColors()
    val targetGlow = ledColor(ledColors[1]) ?: ledColor(ledColors[0])
        ?: figuresOnPortal.firstOrNull()?.element?.color ?: DEFAULT_GLOW
    val glowColor by animateColorAsState(targetGlow, tween(800), label = "portalGlow")
    val trapTarget = ledColor(ledColors[2]) ?: Color.Transparent
    val trapColor by animateColorAsState(trapTarget, tween(800), label = "portalTrap")

    // A bright flash when figures go on or come off.
    val flash = remember { Animatable(0f) }
    val occupancy = figuresOnPortal.map { it.installed.path }
    LaunchedEffect(occupancy) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(900))
    }

    // Slow "breathing" and swirl for an ethereal look. Only runs while the glow is on. The values
    // are read while drawing, so the animation redraws the portal without recomposing it.
    val animation = if (isGlowEnabled) rememberGlowAnimation() else null

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2.1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawPortal(
                glowColor = glowColor,
                trapColor = trapColor,
                isGlowEnabled = isGlowEnabled,
                breathe = animation?.first?.value ?: 0.5f,
                swirlDegrees = animation?.second?.value ?: 0f,
                flash = flash.value,
            )
        }

        // The figures on the portal, standing in the middle of the glowing centre.
        val portraitSize = (maxWidth * 0.16f).coerceAtMost(72.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.offset(y = -(maxHeight * 0.1f)),
        ) {
            figuresOnPortal.take(MAX_PORTRAITS).forEach { figure ->
                FigurePortrait(figure = figure, artFile = artIndex.findArt(figure), size = portraitSize)
            }
        }
    }
}

private const val MAX_PORTRAITS = 4

/** The breathing (0..1) and swirl (degrees) values of the portal's glow animation. */
@Composable
private fun rememberGlowAnimation(): Pair<State<Float>, State<Float>> {
    val transition = rememberInfiniteTransition(label = "portal")
    val breathe = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val swirl = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "swirl",
    )
    return breathe to swirl
}

@Composable
private fun FigurePortrait(figure: PortalFigure, artFile: File?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, figure.element.color, CircleShape),
    ) {
        FigureArt(figure = figure, artFile = artFile, modifier = Modifier.size(size))
    }
}

/** Draws the portal: base glow, brick wall, brick rim with runes, and the glowing centre. */
private fun DrawScope.drawPortal(
    glowColor: Color,
    trapColor: Color,
    isGlowEnabled: Boolean,
    breathe: Float,
    swirlDegrees: Float,
    flash: Float,
) {
    val w = size.width
    val h = size.height
    val wallHeight = h * 0.18f
    // Top face of the portal: an ellipse seen at an angle.
    val top = Rect(w * 0.04f, h * 0.06f, w * 0.96f, h - wallHeight - h * 0.04f)
    val inner = Rect(
        top.left + top.width * 0.13f,
        top.top + top.height * 0.15f,
        top.right - top.width * 0.13f,
        top.bottom - top.height * 0.15f,
    )
    val glowStrength = if (isGlowEnabled) 0.55f + 0.25f * breathe + 0.2f * flash else 0f

    // Light spilling out underneath the portal, like the lit band on the real one.
    if (isGlowEnabled) {
        drawOval(
            brush = Brush.radialGradient(
                listOf(glowColor.copy(alpha = 0.55f * glowStrength), Color.Transparent),
                center = Offset(w / 2, top.bottom + wallHeight),
                radius = w * 0.5f,
            ),
            topLeft = Offset(0f, top.bottom + wallHeight * 0.3f),
            size = Size(w, wallHeight * 1.6f),
        )
    }

    drawWall(top, wallHeight, glowColor, glowStrength)
    drawRim(top, inner, glowColor, glowStrength)
    drawCentre(inner, glowColor, trapColor, isGlowEnabled, glowStrength, swirlDegrees)
}

private fun ellipsePoint(rect: Rect, angle: Double): Offset = Offset(
    rect.center.x + (rect.width / 2 * cos(angle)).toFloat(),
    rect.center.y + (rect.height / 2 * sin(angle)).toFloat(),
)

/** A deterministic shade of stone grey for brick [index], so the bricks don't flicker. */
private fun stoneShade(index: Int, base: Float): Color {
    val noise = ((index * 7919 + 13) % 17) / 17f
    val v = (base + noise * 0.14f).coerceIn(0f, 1f)
    return Color(v, v, v * 1.03f)
}

private const val BRICK_COUNT = 22

/** The front of the portal: a wall of stone bricks below the rim, lit along the bottom. */
private fun DrawScope.drawWall(top: Rect, wallHeight: Float, glowColor: Color, glowStrength: Float) {
    val bricksPerRow = BRICK_COUNT / 2
    for (row in 0..1) {
        val rowTop = row * wallHeight / 2
        val rowBottom = rowTop + wallHeight / 2
        // Offset every other row by half a brick, like real brickwork.
        val shift = if (row == 0) 0.0 else PI / bricksPerRow / 2
        for (i in 0 until bricksPerRow) {
            val a0 = i * PI / bricksPerRow + shift
            val a1 = (i + 1) * PI / bricksPerRow + shift
            if (a0 >= PI) continue
            val end = minOf(a1, PI)
            val gap = 0.012
            val p0 = ellipsePoint(top, a0 + gap)
            val p1 = ellipsePoint(top, end - gap)
            val path = Path().apply {
                moveTo(p0.x, p0.y + rowTop + 1)
                val steps = 6
                for (s in 1..steps) {
                    val p = ellipsePoint(top, a0 + gap + (end - a0 - 2 * gap) * s / steps)
                    lineTo(p.x, p.y + rowTop + 1)
                }
                lineTo(p1.x, p1.y + rowBottom - 1)
                for (s in steps - 1 downTo 0) {
                    val p = ellipsePoint(top, a0 + gap + (end - a0 - 2 * gap) * s / steps)
                    lineTo(p.x, p.y + rowBottom - 1)
                }
                close()
            }
            // Bricks towards the sides are in shadow.
            val facing = sin((a0 + end) / 2).toFloat()
            drawPath(path, stoneShade(i + row * 31, 0.22f + 0.18f * facing))
        }
    }
    // The lit band along the bottom edge.
    if (glowStrength > 0f) {
        val band = Path().apply {
            val steps = 40
            for (s in 0..steps) {
                val p = ellipsePoint(top, PI * s / steps)
                if (s == 0) moveTo(p.x, p.y + wallHeight) else lineTo(p.x, p.y + wallHeight)
            }
        }
        drawPath(band, glowColor.copy(alpha = 0.9f * glowStrength), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(band, glowColor.copy(alpha = 0.3f * glowStrength), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The top rim: a ring of stone bricks between the outer and inner ellipse, with rune marks. */
private fun DrawScope.drawRim(top: Rect, inner: Rect, glowColor: Color, glowStrength: Float) {
    // Mortar behind the bricks.
    drawOval(color = Color(0xFF1C1D20), topLeft = top.topLeft, size = top.size)
    for (i in 0 until BRICK_COUNT) {
        val gap = 0.018
        val a0 = 2 * PI * i / BRICK_COUNT + gap
        val a1 = 2 * PI * (i + 1) / BRICK_COUNT - gap
        val path = Path()
        val steps = 5
        for (s in 0..steps) {
            val p = ellipsePoint(top, a0 + (a1 - a0) * s / steps)
            if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        for (s in steps downTo 0) {
            val p = ellipsePoint(inner, a0 + (a1 - a0) * s / steps)
            path.lineTo(p.x, p.y)
        }
        path.close()
        // Bricks at the front catch more light than those at the back.
        val front = ((sin((a0 + a1) / 2) + 1) / 2).toFloat()
        drawPath(path, stoneShade(i, 0.38f + 0.2f * front))
        // A thin highlight along each brick's outer edge.
        val o0 = ellipsePoint(top, a0)
        val o1 = ellipsePoint(top, a1)
        drawLine(Color.White.copy(alpha = 0.12f), o0, o1, strokeWidth = 1.5f)

        // Rune marks cut into some of the front bricks; they glow when the portal is lit.
        if (i % 4 == 1 && front > 0.55f) {
            val c = ellipsePoint(
                Rect(
                    (top.left + inner.left) / 2, (top.top + inner.top) / 2,
                    (top.right + inner.right) / 2, (top.bottom + inner.bottom) / 2,
                ),
                (a0 + a1) / 2,
            )
            val r = (top.height - inner.height) * 0.16f
            val runeColor = if (glowStrength > 0f) {
                lerp(Color(0xFFE8ECEF), glowColor, 0.6f).copy(alpha = 0.6f + 0.4f * glowStrength)
            } else {
                Color(0xFFD8DCE0).copy(alpha = 0.7f)
            }
            val stroke = 2.dp.toPx()
            if (i % 8 == 1) {
                // A "V" rune.
                drawLine(runeColor, Offset(c.x - r, c.y - r), Offset(c.x, c.y + r), stroke, StrokeCap.Round)
                drawLine(runeColor, Offset(c.x + r, c.y - r), Offset(c.x, c.y + r), stroke, StrokeCap.Round)
            } else {
                // A "K" rune.
                drawLine(runeColor, Offset(c.x - r * 0.5f, c.y - r), Offset(c.x - r * 0.5f, c.y + r), stroke, StrokeCap.Round)
                drawLine(runeColor, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.7f, c.y - r), stroke, StrokeCap.Round)
                drawLine(runeColor, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.7f, c.y + r), stroke, StrokeCap.Round)
            }
        }
    }
}

/** The centre of the portal: a glowing, softly swirling light, or a plain white top when unlit. */
private fun DrawScope.drawCentre(
    inner: Rect,
    glowColor: Color,
    trapColor: Color,
    isGlowEnabled: Boolean,
    glowStrength: Float,
    swirlDegrees: Float,
) {
    // A dark lip just inside the rim.
    drawOval(color = Color(0xFF101114), topLeft = inner.topLeft, size = inner.size)
    val surface = Rect(inner.left + 3f, inner.top + 3f, inner.right - 3f, inner.bottom - 3f)

    if (!isGlowEnabled) {
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color.White, UNLIT_CENTRE),
                center = surface.center,
                radius = surface.width / 2,
            ),
            topLeft = surface.topLeft,
            size = surface.size,
        )
        return
    }

    // Glowing surface: bright core fading into the portal colour at the edge.
    drawOval(
        brush = Brush.radialGradient(
            listOf(
                lerp(Color.White, glowColor, 0.25f),
                lerp(Color.White, glowColor, 0.65f),
                glowColor.copy(alpha = 0.9f),
            ),
            center = surface.center,
            radius = surface.width / 2,
        ),
        topLeft = surface.topLeft,
        size = surface.size,
        alpha = 0.75f + 0.25f * glowStrength,
    )

    // Soft wisps slowly circling, squashed to the portal's perspective.
    withTransform({
        scale(1f, surface.height / surface.width, surface.center)
        rotate(swirlDegrees, surface.center)
    }) {
        for (k in 0 until 3) {
            val angle = k * 2 * PI / 3
            val radius = surface.width * 0.28f
            val c = Offset(
                surface.center.x + (radius * cos(angle)).toFloat(),
                surface.center.y + (radius * sin(angle)).toFloat(),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.35f * glowStrength), Color.Transparent),
                    center = c,
                    radius = surface.width * 0.22f,
                ),
                radius = surface.width * 0.22f,
                center = c,
            )
        }
    }

    // The trap light in the middle, when the game has lit it.
    if (trapColor.alpha > 0f) {
        drawOval(
            brush = Brush.radialGradient(
                listOf(trapColor.copy(alpha = 0.7f * trapColor.alpha), Color.Transparent),
                center = surface.center,
                radius = surface.width * 0.25f,
            ),
            topLeft = surface.topLeft,
            size = surface.size,
        )
    }

    // A faint halo above the portal.
    drawOval(
        brush = Brush.radialGradient(
            listOf(glowColor.copy(alpha = 0.25f * glowStrength), Color.Transparent),
            center = surface.center,
            radius = surface.width * 0.6f,
        ),
        topLeft = Offset(surface.left - surface.width * 0.1f, surface.top - surface.height * 0.6f),
        size = Size(surface.width * 1.2f, surface.height * 1.4f),
    )
}
