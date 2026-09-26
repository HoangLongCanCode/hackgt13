package com.drivingassist.spatialcopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivingassist.spatialcopilot.model.ArrowHeading
import com.drivingassist.spatialcopilot.model.ImageBox
import com.drivingassist.spatialcopilot.model.LaneSlot
import com.drivingassist.spatialcopilot.model.Px
import com.drivingassist.spatialcopilot.model.SpatialInstruction
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Mint = Color(0xFF7DFFC3)
private val Cyan = Color(0xFF9BE7FF)
private val Amber = Color(0xFFFFC56B)

/**
 * Spatial AR Engine. Draws lane arrows, lane edges, lead-vehicle distances,
 * and sign markers. It does not choose a lane; [SpatialInstruction] already did.
 */
@Composable
fun SpatialArEngine(
    instruction: SpatialInstruction,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val started = System.nanoTime()
        while (true) {
            pulse = ((System.nanoTime() - started) / 1_000_000_000.0).toFloat()
            delay(32)
        }
    }

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.28f),
                0.18f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.45f),
            ),
        )
        val map = Mapper(instruction.imageWidth, instruction.imageHeight, size.width, size.height)
        instruction.lanes.forEach { lane ->
            if (lane.recommended) drawLaneFill(lane, map)
        }
        instruction.lanes.forEachIndexed { index, lane ->
            drawLaneEdges(lane, map, last = index == instruction.lanes.lastIndex)
        }
        instruction.lanes.forEach { lane ->
            val anchor = map.point(lane.arrow.anchorX, lane.arrow.anchorY)
            drawLaneArrow(
                anchor = anchor,
                heading = lane.arrow.heading,
                highlighted = lane.arrow.highlighted,
                time = pulse,
            )
        }
        instruction.signs.forEach { sign ->
            drawSign(sign.label, sign.box, map, textMeasurer)
        }
        instruction.vehicles.forEach { vehicle ->
            val label = vehicle.distanceLabel ?: return@forEach
            drawVehicle(vehicle.box, label, map, textMeasurer, pulse)
        }
    }
}

private class Mapper(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float,
) {
    private val scale: Float = if (imageWidth <= 0 || imageHeight <= 0) {
        1f
    } else {
        max(viewWidth / imageWidth, viewHeight / imageHeight)
    }
    private val dx: Float = (viewWidth - imageWidth * scale) / 2f
    private val dy: Float = (viewHeight - imageHeight * scale) / 2f

    fun point(x: Float, y: Float): Offset = Offset(dx + x * scale, dy + y * scale)

    fun point(px: Px): Offset = point(px.x, px.y)

    fun box(box: ImageBox): OffsetRect = OffsetRect(point(box.x1, box.y1), point(box.x2, box.y2))
}

private data class OffsetRect(val topLeft: Offset, val bottomRight: Offset) {
    val width: Float get() = bottomRight.x - topLeft.x
    val height: Float get() = bottomRight.y - topLeft.y
    val center: Offset get() = Offset((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f)
}

private fun DrawScope.drawLaneFill(lane: LaneSlot, map: Mapper) {
    if (lane.boundaries.size < 2) return
    val left = lane.boundaries[0].map(map::point)
    val right = lane.boundaries[1].map(map::point)
    if (left.size < 2 || right.size < 2) return
    val rightPath = if (abs(left.first().y - right.first().y) < abs(left.first().y - right.last().y)) {
        right.asReversed()
    } else {
        right
    }
    val path = Path().apply {
        moveTo(left.first().x, left.first().y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        rightPath.forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, Mint.copy(alpha = 0.16f))
}

private fun DrawScope.drawLaneEdges(lane: LaneSlot, map: Mapper, last: Boolean) {
    val color = if (lane.recommended) Mint.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.55f)
    val width = if (lane.recommended) 3.5.dp.toPx() else 2.dp.toPx()
    val edges = if (last) lane.boundaries else lane.boundaries.take(1)
    edges.forEach { polyline ->
        if (polyline.size < 2) return@forEach
        val path = Path()
        polyline.forEachIndexed { index, point ->
            val mapped = map.point(point)
            if (index == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Reusable road-plane lane arrow: three chevrons aimed along [heading].
 * Highlighted arrows are larger and pulse. Dim arrows stay on the other lanes.
 */
private fun DrawScope.drawLaneArrow(
    anchor: Offset,
    heading: ArrowHeading,
    highlighted: Boolean,
    time: Float,
) {
    val direction = when (heading) {
        ArrowHeading.LEFT -> Offset(-0.42f, -1f)
        ArrowHeading.RIGHT -> Offset(0.42f, -1f)
        ArrowHeading.STRAIGHT -> Offset(0f, -1f)
    }.normalized()
    val pulse = if (highlighted) 0.72f + 0.28f * ((sin(time * 3.4f) + 1f) * 0.5f) else 0.9f
    val unit = if (highlighted) 28.dp.toPx() else 18.dp.toPx()
    val color = if (highlighted) Mint.copy(alpha = pulse) else Color.White.copy(alpha = 0.42f)
    if (highlighted) {
        drawCircle(color = Mint.copy(alpha = 0.14f * pulse), radius = unit * 2.6f, center = anchor)
    }
    repeat(3) { index ->
        val along = unit * (0.15f + index * 1.05f)
        val center = Offset(
            anchor.x + direction.x * along,
            anchor.y + direction.y * along * 0.62f,
        )
        val chevronSize = unit * (1.05f - index * 0.14f)
        drawChevron(center, direction, chevronSize, color.copy(alpha = color.alpha * (1f - index * 0.12f)))
    }
}

private fun DrawScope.drawChevron(
    center: Offset,
    direction: Offset,
    size: Float,
    color: Color,
) {
    val perpendicular = Offset(-direction.y, direction.x)
    val tip = center + direction * size
    val left = center - direction * (size * 0.2f) + perpendicular * size
    val right = center - direction * (size * 0.2f) - perpendicular * size
    val path = Path().apply {
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
    }
    drawPath(
        path,
        color,
        style = Stroke(width = max(3.dp.toPx(), size * 0.16f), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawVehicle(
    box: ImageBox,
    label: String,
    map: Mapper,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    time: Float,
) {
    val rect = map.box(box)
    if (rect.width < 4f || rect.height < 4f) return
    val pulse = 0.75f + 0.25f * ((sin(time * 3.2f) + 1f) * 0.5f)
    val color = Cyan.copy(alpha = pulse)
    val arm = min(rect.width, rect.height) * 0.28f
    val stroke = 2.5.dp.toPx()
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, cap = StrokeCap.Round)
    }
    val l = rect.topLeft.x
    val t = rect.topLeft.y
    val r = rect.bottomRight.x
    val b = rect.bottomRight.y
    seg(l, t + arm, l, t)
    seg(l, t, l + arm, t)
    seg(r - arm, t, r, t)
    seg(r, t, r, t + arm)
    seg(r, b - arm, r, b)
    seg(r, b, r - arm, b)
    seg(l + arm, b, l, b)
    seg(l, b, l, b - arm)
    drawBadge(textMeasurer, label, Offset(rect.center.x, t - 8.dp.toPx()), color, centered = true)
}

private fun DrawScope.drawSign(
    label: String,
    box: ImageBox,
    map: Mapper,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val rect = map.box(box)
    drawRoundRect(
        color = Amber,
        topLeft = rect.topLeft,
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
    )
    drawBadge(
        textMeasurer,
        label,
        Offset(rect.topLeft.x, rect.topLeft.y - 6.dp.toPx()),
        Amber,
        centered = false,
    )
}

private fun DrawScope.drawBadge(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    anchor: Offset,
    color: Color,
    centered: Boolean,
) {
    val layout = textMeasurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val width = layout.size.width + padX * 2
    val height = layout.size.height + padY * 2
    val left = (if (centered) anchor.x - width / 2f else anchor.x).coerceIn(8f, size.width - width - 8f)
    val top = (anchor.y - height).coerceIn(8f, size.height - height - 8f)
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.55f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

private fun Offset.normalized(): Offset {
    val length = hypot(x, y)
    if (length < 1e-3f) return Offset(0f, -1f)
    return Offset(x / length, y / length)
}
