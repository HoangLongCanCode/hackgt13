package com.drivingassist.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val GlassMint = Color(0xFF3DFFB0)
private val GlassCyan = Color(0xFF3DDCFF)
private val GlassAmber = Color(0xFFFFB020)
private val DebugRed = Color(0xFFFF3B30)
private val LaneYellow = Color(0xFFFFE14A)

@Composable
fun AROverlay(
    visionData: VisionData,
    routeState: RouteState,
    isDebugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density).toFloat()
    val bottomInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (isDebugMode) {
                drawDebugOverlay(
                    visionData = visionData,
                    routeState = routeState,
                    textMeasurer = textMeasurer,
                    topInsetPx = topInsetPx,
                )
            } else {
                drawGlassOverlay(
                    visionData = visionData,
                    routeState = routeState,
                    textMeasurer = textMeasurer,
                    bottomInsetPx = bottomInsetPx,
                )
            }
        }

        Button(
            onClick = { onDebugModeChange(!isDebugMode) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDebugMode) Color(0xCCB71C1C) else Color(0xCC10241C),
                contentColor = Color.White,
            ),
        ) {
            Text(if (isDebugMode) "DEBUG" else "GLASS")
        }
    }
}

private fun DrawScope.drawDebugOverlay(
    visionData: VisionData,
    routeState: RouteState,
    textMeasurer: TextMeasurer,
    topInsetPx: Float,
) {
    drawLanes(visionData.lanes, LaneYellow, 2.5.dp.toPx())
    visionData.signs.forEach { sign ->
        drawTaggedBox(sign.box, sign.label, GlassAmber, textMeasurer, topInsetPx, debug = true)
    }
    visionData.exitSigns.forEach { sign ->
        drawTaggedBox(
            box = sign.box,
            label = "${sign.label}  ${sign.distanceMeters.roundToInt()} m",
            color = GlassCyan,
            textMeasurer = textMeasurer,
            topInsetPx = topInsetPx,
            debug = true,
        )
    }
    visionData.vehicles.forEach { vehicle ->
        val rect = vehicle.box.toScreenBox(size.width, size.height) ?: return@forEach
        drawRect(
            color = DebugRed,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawBadge(
            textMeasurer = textMeasurer,
            text = "#${vehicle.id}  ${vehicle.distanceMeters.roundToInt()} m",
            anchor = Offset(rect.left, (rect.top - 8.dp.toPx()).coerceAtLeast(topInsetPx + 4.dp.toPx())),
            color = DebugRed,
            alignBelow = rect.top < topInsetPx + 48.dp.toPx(),
        )
    }

    val header = "${routeState.action}\nui=${routeState.ui}   t=${"%.1f".format(visionData.time)}s"
    val layout = textMeasurer.measure(
        text = header,
        style = TextStyle(
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
        ),
    )
    val pad = 14.dp.toPx()
    val origin = Offset(16.dp.toPx(), topInsetPx + 12.dp.toPx())
    drawRoundRect(
        color = Color(0xCC1A0505),
        topLeft = origin,
        size = Size(layout.size.width + pad * 2, layout.size.height + pad * 2),
        cornerRadius = CornerRadius(16.dp.toPx()),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(origin.x + pad, origin.y + pad),
    )
}

private fun DrawScope.drawGlassOverlay(
    visionData: VisionData,
    routeState: RouteState,
    textMeasurer: TextMeasurer,
    bottomInsetPx: Float,
) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.55f),
        ),
    )
    if (visionData.lanes.isEmpty()) {
        drawLaneGuide(bottomInsetPx)
    } else {
        drawLanes(visionData.lanes, GlassMint.copy(alpha = 0.9f), 3.dp.toPx())
    }

    visionData.signs.forEach { sign ->
        drawTaggedBox(sign.box, sign.label.replace('_', ' '), GlassAmber, textMeasurer, 0f, debug = false)
    }
    visionData.exitSigns.forEach { sign ->
        drawTaggedBox(
            box = sign.box,
            label = "${sign.label}  ${sign.distanceMeters.roundToInt()} m",
            color = GlassCyan,
            textMeasurer = textMeasurer,
            topInsetPx = 0f,
            debug = false,
        )
    }

    val pulse = 0.72f + 0.28f * ((sin(visionData.time * 3.2f) + 1f) * 0.5f)
    visionData.vehicles.forEach { vehicle ->
        val rect = vehicle.box.toScreenBox(size.width, size.height) ?: return@forEach
        drawTargetingReticle(rect, GlassMint.copy(alpha = pulse))
        val labelY = if (rect.top > 40.dp.toPx()) rect.top - 18.dp.toPx() else rect.bottom + 18.dp.toPx()
        drawBadge(
            textMeasurer = textMeasurer,
            text = "${vehicle.distanceMeters.roundToInt()} m",
            anchor = Offset(rect.centerX, labelY),
            color = GlassMint,
            centered = true,
        )
    }

    val heading = headingDegrees(routeState)
    val arrowColor = arrowColor(routeState)
    val caption = textMeasurer.measure(
        text = routeState.audio,
        style = TextStyle(
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
    val captionPadX = 16.dp.toPx()
    val captionPadY = 8.dp.toPx()
    val captionWidth = caption.size.width + captionPadX * 2
    val captionHeight = caption.size.height + captionPadY * 2
    val captionLeft = (size.width - captionWidth) / 2f
    val captionTop = size.height - bottomInsetPx - captionHeight - 16.dp.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = Offset(captionLeft, captionTop),
        size = Size(captionWidth, captionHeight),
        cornerRadius = CornerRadius(captionHeight / 2f),
    )
    drawText(
        textLayoutResult = caption,
        topLeft = Offset(captionLeft + captionPadX, captionTop + captionPadY),
    )

    drawPseudo3dArrow(
        headingDegrees = heading,
        color = arrowColor,
        baseY = captionTop - 8.dp.toPx(),
    )
}

private fun DrawScope.drawLanes(lanes: List<LaneLine>, color: Color, strokeWidth: Float) {
    lanes.forEach { lane ->
        if (lane.points.size < 2) return@forEach
        val path = Path()
        lane.points.forEachIndexed { index, point ->
            val x = point.x * size.width
            val y = point.y * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawTaggedBox(
    box: FloatArray,
    label: String,
    color: Color,
    textMeasurer: TextMeasurer,
    topInsetPx: Float,
    debug: Boolean,
) {
    val rect = box.toScreenBox(size.width, size.height) ?: return
    if (debug) {
        drawRect(
            color = color,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 3.dp.toPx()),
        )
    } else {
        drawRoundRect(
            color = color,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
    drawBadge(
        textMeasurer = textMeasurer,
        text = label,
        anchor = Offset(rect.left, (rect.top - 8.dp.toPx()).coerceAtLeast(topInsetPx + 4.dp.toPx())),
        color = color,
        alignBelow = rect.top < topInsetPx + 48.dp.toPx(),
    )
}

private fun DrawScope.drawTargetingReticle(
    rect: ScreenBox,
    color: Color,
) {
    val arm = min(rect.width, rect.height) * 0.28f
    val stroke = 2.5.dp.toPx()
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
    seg(rect.left, rect.top + arm, rect.left, rect.top)
    seg(rect.left, rect.top, rect.left + arm, rect.top)
    seg(rect.right - arm, rect.top, rect.right, rect.top)
    seg(rect.right, rect.top, rect.right, rect.top + arm)
    seg(rect.right, rect.bottom - arm, rect.right, rect.bottom)
    seg(rect.right, rect.bottom, rect.right - arm, rect.bottom)
    seg(rect.left + arm, rect.bottom, rect.left, rect.bottom)
    seg(rect.left, rect.bottom, rect.left, rect.bottom - arm)

    drawCircle(
        color = color.copy(alpha = 0.35f),
        radius = 16.dp.toPx(),
        center = Offset(rect.centerX, rect.centerY),
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = color,
        radius = 3.dp.toPx(),
        center = Offset(rect.centerX, rect.centerY),
    )
}

private fun DrawScope.drawLaneGuide(bottomInsetPx: Float) {
    val floor = size.height - bottomInsetPx
    val w = size.width
    val horizon = floor * 0.58f
    val path = Path().apply {
        moveTo(w * 0.16f, floor)
        lineTo(w * 0.84f, floor)
        lineTo(w * 0.56f, horizon)
        lineTo(w * 0.44f, horizon)
        close()
    }
    drawPath(path, Color.White.copy(alpha = 0.07f))
    val edge = GlassMint.copy(alpha = 0.45f)
    drawLine(edge, Offset(w * 0.16f, floor), Offset(w * 0.44f, horizon), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(edge, Offset(w * 0.84f, floor), Offset(w * 0.56f, horizon), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

/**
 * Hackathon shortcut: scale and skew a flat arrow with a 2D canvas matrix so it
 * reads as lying on the road instead of standing up like a billboard.
 */
private fun DrawScope.drawPseudo3dArrow(
    headingDegrees: Float,
    color: Color,
    baseY: Float,
) {
    val unit = size.minDimension * 0.42f / 220f
    val arrow = Path().apply {
        moveTo(0f, -220f)
        lineTo(78f, -58f)
        lineTo(30f, -58f)
        lineTo(30f, 36f)
        lineTo(-30f, 36f)
        lineTo(-30f, -58f)
        lineTo(-78f, -58f)
        close()
    }
    withTransform({
        translate(size.width / 2f, baseY)
        // Column-major 4x4: scaleX, skewY, skewX, scaleY. Squashes the arrow
        // onto the road plane and shears it so the tip leans toward the horizon.
        transform(
            Matrix().apply {
                values[0] = unit * 1.25f
                values[1] = 0.05f * unit
                values[4] = -0.22f * unit
                values[5] = unit * 0.46f
            },
        )
        rotate(degrees = headingDegrees, pivot = Offset.Zero)
    }) {
        drawPath(
            path = arrow,
            color = color.copy(alpha = 0.4f),
            style = Stroke(width = 14f, join = StrokeJoin.Round),
        )
        drawPath(path = arrow, color = color)
    }
}

private fun DrawScope.drawBadge(
    textMeasurer: TextMeasurer,
    text: String,
    anchor: Offset,
    color: Color,
    centered: Boolean = false,
    alignBelow: Boolean = false,
) {
    val layout = textMeasurer.measure(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val width = layout.size.width + padX * 2
    val height = layout.size.height + padY * 2
    val left = if (centered) anchor.x - width / 2f else anchor.x
    val top = if (alignBelow) anchor.y else anchor.y - height
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.55f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(left + padX, top + padY),
    )
}

/**
 * LANE_ARROW has no direction of its own, so left / right / straight comes from
 * [RouteState.action] unless [RouteState.ui] already names one.
 */
private fun headingDegrees(route: RouteState): Float {
    fun directionOf(raw: String): Float? {
        val value = raw.uppercase()
        return when {
            "LEFT" in value -> -62f
            "RIGHT" in value -> 62f
            "STRAIGHT" in value || "CONTINUE" in value -> 0f
            else -> null
        }
    }
    return directionOf(route.ui) ?: directionOf(route.action) ?: 0f
}

private fun arrowColor(route: RouteState): Color {
    val signal = "${route.ui} ${route.action}".uppercase()
    return when {
        "LEFT" in signal -> GlassCyan
        "RIGHT" in signal -> GlassAmber
        else -> GlassMint
    }
}

private data class ScreenBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

private fun FloatArray.toScreenBox(viewWidth: Float, viewHeight: Float): ScreenBox? {
    if (size < 4) return null
    val left = this[0] * viewWidth
    val top = this[1] * viewHeight
    val right = left + this[2] * viewWidth
    val bottom = top + this[3] * viewHeight
    if (right <= left || bottom <= top) return null
    return ScreenBox(left, top, right, bottom)
}
