package com.drivingassist.spatialcopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivingassist.spatialcopilot.ar.ArInput
import com.drivingassist.spatialcopilot.ar.ArScene
import com.drivingassist.spatialcopilot.ar.ArSceneBuilder
import com.drivingassist.spatialcopilot.ar.DebugLayer
import com.drivingassist.spatialcopilot.ar.LaneArrowStyle
import com.drivingassist.spatialcopilot.ar.LeadHighlight
import com.drivingassist.spatialcopilot.ar.Vec2
import com.drivingassist.spatialcopilot.ar.ViewRect
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.spatialcopilot.ar.FillCenter
import com.drivingassist.spatialcopilot.session.CopilotSession
import com.drivingassist.spatialcopilot.session.SourceMode
import kotlin.math.min
import kotlin.math.sin

private val Mint = Color(0xFF7DFFC3)
private val Cyan = Color(0xFF9BE7FF)
private val Amber = Color(0xFFFFC56B)
private val Alert = Color(0xFFFF5A4E)
private val LaneGreen = Color(0xFF46E27A)
private val LaneRed = Color(0xFFFF4B3E)

/** Debug-only top / bottom shade. */
private val Vignette = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.28f),
    0.18f to Color.Transparent,
    0.72f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.40f),
)

/** Paths reused every frame (the Tab S9 draws at 60-120 Hz). */
private class ArPaths {
    val arrow = Path()
    val clip = Path()
}

/**
 * Spatial AR Engine: draws, every display frame, the [ArScene] built from the session's world (moved to
 * display time), Driving Context and route. It decides nothing itself: lane arrows come from the lane
 * model and the Driving Context's lane guidance, the maneuver from phase1's route, the highlighted
 * vehicle from the Driving Context.
 * Clean view: flat lane arrows on the road, the destination pin, and red brackets on the lead vehicle
 * in TOO CLOSE. Debug adds the shade, the chevron path, the CLOSE highlight and badges, every box, lane
 * lines, the fitted ego lane, anchors, the horizon and the lane state.
 */
@Composable
fun SpatialArEngine(session: CopilotSession, debug: Boolean, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val builder = remember(session) { ArSceneBuilder() }
    val paths = remember { ArPaths() }
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session) {
        while (true) withFrameNanos { frameNs = it }
    }

    Canvas(modifier.fillMaxSize()) {
        val t = frameNs / 1e9f // read every frame: the canvas redraws at display rate
        if (debug) drawRect(brush = Vignette)
        val route = session.route.value
        val input = ArInput(
            world = session.displayWorld(),
            context = session.context.value,
            route = route,
            routeDistanceMeters = session.routeDistanceNow(route),
            debug = debug,
        )
        val scene = builder.build(input, size.width, size.height, session.clockNs())
        // DEMO has no camera picture: sketch the scripted road (lane lines, the car ahead) under the overlay.
        if (session.settings.mode == SourceMode.DEMO) drawDemoSketch(input.world)
        scene.debug?.let { drawDebug(it, textMeasurer) }
        drawLaneArrows(scene, paths)
        drawArrows(scene)
        scene.lead?.let { drawLead(it, textMeasurer, t) }
    }
}

/** Filled arrows with a thin dark edge, never painted over the road users in [ArScene.occluders]. */
private fun DrawScope.drawLaneArrows(scene: ArScene, paths: ArPaths) {
    if (scene.laneArrows.isEmpty()) return
    if (scene.occluders.isEmpty()) {
        laneArrowFills(scene, paths.arrow)
        return
    }
    val clip = paths.clip
    clip.reset()
    scene.occluders.forEach { clip.addRect(Rect(it.left, it.top, it.right, it.bottom)) }
    clipPath(clip, ClipOp.Difference) { laneArrowFills(scene, paths.arrow) }
}

private fun DrawScope.laneArrowFills(scene: ArScene, path: Path) {
    val edge = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
    for (a in scene.laneArrows) {
        val pts = a.outline
        if (pts.size < 3) continue
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        val color = when (a.style) {
            LaneArrowStyle.TARGET, LaneArrowStyle.TARGET_BLINK -> LaneGreen
            LaneArrowStyle.WRONG -> LaneRed
            LaneArrowStyle.OTHER -> Color.White
        }
        drawPath(path, color, alpha = a.alpha)
        drawPath(path, Color.Black, alpha = 0.35f * a.alpha, style = edge)
    }
}

private fun DrawScope.drawArrows(scene: ArScene) {
    if (scene.ribbon.size >= 3 && scene.ribbonAlpha > 0.01f) {
        val path = Path().apply {
            moveTo(scene.ribbon[0].x, scene.ribbon[0].y)
            for (i in 1 until scene.ribbon.size) lineTo(scene.ribbon[i].x, scene.ribbon[i].y)
            close()
        }
        drawPath(path, Mint.copy(alpha = scene.ribbonAlpha))
    }
    scene.chevrons.forEach { c ->
        val path = Path().apply {
            moveTo(c.left.x, c.left.y)
            lineTo(c.tip.x, c.tip.y)
            lineTo(c.right.x, c.right.y)
        }
        // Dark under-stroke keeps the chevrons readable on bright road and sky.
        drawPath(path, Color.Black.copy(alpha = 0.35f * c.alpha), style = Stroke(width = c.strokePx * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Mint.copy(alpha = c.alpha), style = Stroke(width = c.strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    scene.pin?.let { p ->
        val r = 14.dp.toPx()
        drawCircle(Mint.copy(alpha = 0.25f), radius = r * 2f, center = Offset(p.x, p.y))
        drawCircle(Mint, radius = r * 0.6f, center = Offset(p.x, p.y))
    }
}

private fun DrawScope.drawLead(lead: LeadHighlight, textMeasurer: TextMeasurer, time: Float) {
    val rect = lead.rect
    if (rect.width < 4f || rect.height < 4f) return
    val base = if (lead.critical) Alert else Amber
    val pulse = if (lead.critical) 0.7f + 0.3f * ((sin(time * 6.0f) + 1f) * 0.5f) else 0.95f
    val color = base.copy(alpha = pulse)
    val arm = min(rect.width, rect.height) * 0.28f
    val stroke = (if (lead.critical) 4.dp else 3.dp).toPx()
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, cap = StrokeCap.Round)
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    seg(l, t + arm, l, t); seg(l, t, l + arm, t)
    seg(r - arm, t, r, t); seg(r, t, r, t + arm)
    seg(r, b - arm, r, b); seg(r, b, r - arm, b)
    seg(l + arm, b, l, b); seg(l, b, l, b - arm)
    if (!lead.badge) return
    val label = if (lead.critical) "TOO CLOSE · ${lead.label}" else lead.label
    drawBadge(textMeasurer, label, Offset(rect.centerX, t - 8.dp.toPx()), color, centered = true, bold = lead.critical)
}

private fun DrawScope.drawDebug(d: DebugLayer, textMeasurer: TextMeasurer) {
    d.horizonY?.let { y ->
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
    }
    d.laneLines.forEach { line -> polyline(line, Color.White.copy(alpha = 0.7f), 2.dp.toPx()) }
    polyline(d.egoLane, Cyan.copy(alpha = 0.9f), 2.dp.toPx(), dashed = true)
    d.anchors.forEach { drawCircle(Amber, radius = 4.dp.toPx(), center = Offset(it.x, it.y)) }
    d.boxes.forEach { (r, tag) -> debugBox(r, tag, textMeasurer) }
    d.egoLaneSource?.let { src ->
        drawBadge(textMeasurer, "ego lane: ${src.name.lowercase()}", Offset(12.dp.toPx(), size.height - 60.dp.toPx()), Cyan, centered = false, bold = false)
    }
    d.laneStatus?.let { drawBadge(textMeasurer, it, Offset(12.dp.toPx(), size.height - 96.dp.toPx()), Cyan, centered = false, bold = false) }
}

private fun DrawScope.debugBox(r: ViewRect, tag: String, textMeasurer: TextMeasurer) {
    drawRect(Cyan.copy(alpha = 0.8f), topLeft = Offset(r.left, r.top), size = Size(r.width, r.height), style = Stroke(width = 1.5.dp.toPx()))
    drawBadge(textMeasurer, tag, Offset(r.left, r.top - 2.dp.toPx()), Cyan, centered = false, bold = false, small = true)
}

private fun DrawScope.polyline(points: List<Vec2>, color: Color, width: Float, dashed: Boolean = false) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18f, 12f)) else null))
}

private fun DrawScope.drawBadge(
    textMeasurer: TextMeasurer,
    text: String,
    anchor: Offset,
    color: Color,
    centered: Boolean,
    bold: Boolean,
    small: Boolean = false,
) {
    val layout = textMeasurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = if (small) 11.sp else 16.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold),
    )
    val padX = (if (small) 5.dp else 9.dp).toPx()
    val padY = (if (small) 2.dp else 5.dp).toPx()
    val width = layout.size.width + padX * 2
    val height = layout.size.height + padY * 2
    val left = (if (centered) anchor.x - width / 2f else anchor.x).coerceIn(8f, (size.width - width - 8f).coerceAtLeast(8f))
    val top = (anchor.y - height).coerceIn(8f, (size.height - height - 8f).coerceAtLeast(8f))
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.6f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

private fun DrawScope.drawDemoSketch(world: WorldSnapshot) {
    val image = world.image ?: return
    val map = FillCenter(image.width, image.height, size.width, size.height)
    world.lanes?.lanes?.laneBoundaries.orEmpty().forEachIndexed { i, line ->
        val pts = line.filter { it.size >= 2 }.map { map.point(it[0], it[1]) }
        val edge = i == 0 || i == world.lanes!!.lanes.laneBoundaries.lastIndex
        polyline(pts, Color.White.copy(alpha = if (edge) 0.55f else 0.35f), (if (edge) 3.dp else 2.dp).toPx(), dashed = !edge)
    }
    world.objects.values.forEach { o ->
        val r = map.box(o.bbox) ?: return@forEach
        drawRoundRect(Color(0xFF3A4150), topLeft = Offset(r.left, r.top + r.height * 0.25f), size = Size(r.width, r.height * 0.75f), cornerRadius = CornerRadius(r.width * 0.08f))
        drawRoundRect(Color(0xFF4B5466), topLeft = Offset(r.left + r.width * 0.15f, r.top), size = Size(r.width * 0.7f, r.height * 0.4f), cornerRadius = CornerRadius(r.width * 0.08f))
        drawRect(Color(0xFFB3261E), topLeft = Offset(r.left + r.width * 0.06f, r.top + r.height * 0.45f), size = Size(r.width * 0.16f, r.height * 0.1f))
        drawRect(Color(0xFFB3261E), topLeft = Offset(r.right - r.width * 0.22f, r.top + r.height * 0.45f), size = Size(r.width * 0.16f, r.height * 0.1f))
    }
}
