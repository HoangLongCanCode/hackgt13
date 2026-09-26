package com.drivingassist.spatialcopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivingassist.spatialcopilot.nav.Instruction
import com.drivingassist.spatialcopilot.nav.NavGlyph
import com.drivingassist.spatialcopilot.session.StatusUi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// The chrome's colors (CopilotScreen).
private val Mint = Color(0xFF7DFFC3)
private val Ink = Color(0xCC101614)
private val Amber = Color(0xFFFFC56B)
private val Alert = Color(0xFFFF5A4E)

/** US-style speed-limit sign (top left), about 64 x 80 dp. Only called with a known limit. */
@Composable
internal fun SpeedLimitSign(mph: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 64.dp, height = 80.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(3.dp)
            .border(2.dp, Color.Black, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SPEED", color = Color.Black, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            Text("LIMIT", color = Color.Black, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = mph.toString(),
                color = Color.Black,
                fontSize = if (mph >= 100) 24.sp else 32.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/** Top-centre instruction: glyph, primary line, optional secondary line; amber when stale or off route. */
@Composable
internal fun InstructionBanner(instruction: Instruction, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val accent = if (instruction.dim) Amber else Mint
    Row(
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(Color.Black.copy(alpha = if (instruction.dim) 0.5f else 0.62f), shape)
            .border(1.dp, accent.copy(alpha = if (instruction.dim) 0.7f else 0.35f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(40.dp)) { drawGlyph(instruction.glyph, accent) }
        Column {
            Text(
                text = instruction.primary,
                color = if (instruction.dim) Amber else Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            instruction.secondary?.let {
                Text(
                    text = it,
                    color = if (instruction.dim) Amber.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Clean view's only control (bottom left): tap = [onClick], hold = [onLongClick]; the dot shows [level]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HudCornerButton(level: StatusUi.Level, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Ink, CircleShape)
            .combinedClickable(onClickLabel = "Settings", onLongClickLabel = "Debug view", onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.padding(top = 2.dp).size(width = 16.dp, height = 12.dp)) {
            for (f in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                val y = size.height * f
                drawLine(Color.White.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(8.dp).background(levelColor(level), CircleShape))
    }
}

/** One short status next to the corner button (only while the level is not OK); tap and hold as on the button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HudStatusNote(text: String, level: StatusUi.Level, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = text,
        color = levelColor(level),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .combinedClickable(onLongClickLabel = "Debug view", onClick = onClick, onLongClick = onLongClick)
            .background(Ink, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 300.dp),
    )
}

private fun levelColor(level: StatusUi.Level): Color = when (level) {
    StatusUi.Level.OK -> Mint
    StatusUi.Level.WARN -> Amber
    StatusUi.Level.ERROR -> Alert
}

/** The glyphs in a unit square; the left-hand forms are drawn and mirrored for the right. */
private fun DrawScope.drawGlyph(glyph: NavGlyph, color: Color) {
    val w = size.width
    val h = size.height
    val mirror = glyph == NavGlyph.TURN_RIGHT || glyph == NavGlyph.SLIGHT_RIGHT || glyph == NavGlyph.EXIT_RIGHT
    fun p(x: Float, y: Float) = Offset((if (mirror) 1f - x else x) * w, y * h)
    val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val headLength = w * 0.24f

    fun line(pts: List<Offset>, c: Color = color, style: Stroke = stroke) {
        drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }, c, style = style)
    }

    /** Filled arrow head whose base centre is [base], pointing away from [from]. */
    fun head(base: Offset, from: Offset) {
        val dx = base.x - from.x
        val dy = base.y - from.y
        val len = hypot(dx, dy).takeIf { it > 0f } ?: return
        val ux = dx / len
        val uy = dy / len
        val tip = Offset(base.x + ux * headLength, base.y + uy * headLength)
        val px = -uy * headLength * 0.8f
        val py = ux * headLength * 0.8f
        drawPath(
            Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + px, base.y + py); lineTo(base.x - px, base.y - py); close() },
            color,
        )
    }

    when (glyph) {
        NavGlyph.STRAIGHT -> {
            line(listOf(p(0.5f, 0.94f), p(0.5f, 0.32f)))
            head(p(0.5f, 0.3f), p(0.5f, 0.6f))
        }
        NavGlyph.TURN_LEFT, NavGlyph.TURN_RIGHT -> {
            val a = p(0.66f, 0.94f)
            val c = p(0.66f, 0.38f)
            val e = p(0.44f, 0.38f)
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(a.x, p(0.66f, 0.58f).y); quadraticTo(c.x, c.y, e.x, e.y) }, color, style = stroke)
            line(listOf(e, p(0.34f, 0.38f)))
            head(p(0.32f, 0.38f), e)
        }
        NavGlyph.SLIGHT_LEFT, NavGlyph.SLIGHT_RIGHT -> {
            val bend = p(0.62f, 0.6f)
            val end = p(0.42f, 0.38f)
            line(listOf(p(0.62f, 0.94f), bend, end))
            head(end, bend)
        }
        NavGlyph.EXIT_LEFT, NavGlyph.EXIT_RIGHT -> {
            // The road carries on (faint); the ramp leaves it towards the exit side.
            line(listOf(p(0.66f, 0.94f), p(0.66f, 0.06f)), c = color.copy(alpha = 0.35f), style = thin)
            val c = p(0.66f, 0.5f)
            val e = p(0.42f, 0.36f)
            drawPath(Path().apply { val a = p(0.66f, 0.94f); moveTo(a.x, a.y); lineTo(a.x, p(0.66f, 0.7f).y); quadraticTo(c.x, c.y, e.x, e.y) }, color, style = stroke)
            head(e, c)
        }
        NavGlyph.MERGE -> {
            val join = p(0.5f, 0.46f)
            val side = p(0.16f, 0.94f)
            val c = p(0.16f, 0.62f)
            drawPath(Path().apply { moveTo(side.x, side.y); quadraticTo(c.x, c.y, join.x, join.y) }, color.copy(alpha = 0.6f), style = thin)
            line(listOf(p(0.5f, 0.94f), p(0.5f, 0.32f)))
            head(p(0.5f, 0.3f), p(0.5f, 0.6f))
        }
        NavGlyph.STOP -> {
            val r = w * 0.42f
            val oct = Path().apply {
                for (i in 0 until 8) {
                    val a = (PI / 8 + i * PI / 4).toFloat()
                    val x = w / 2 + r * cos(a)
                    val y = h / 2 + r * sin(a)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(oct, color, style = thin)
            line(listOf(p(0.32f, 0.5f), p(0.68f, 0.5f)), style = thin)
        }
        NavGlyph.ARRIVE -> {
            // A map pin: ring, two sides meeting at the point, a dot in the middle.
            val centre = p(0.5f, 0.36f)
            drawCircle(color, radius = w * 0.22f, center = centre, style = thin)
            line(listOf(p(0.3f, 0.45f), p(0.5f, 0.92f), p(0.7f, 0.45f)), style = thin)
            drawCircle(color, radius = w * 0.07f, center = centre)
        }
        NavGlyph.OFF_ROUTE -> {
            drawPath(
                Path().apply {
                    val t = p(0.5f, 0.08f); val r = p(0.94f, 0.88f); val l = p(0.06f, 0.88f)
                    moveTo(t.x, t.y); lineTo(r.x, r.y); lineTo(l.x, l.y); close()
                },
                color,
                style = thin,
            )
            line(listOf(p(0.5f, 0.38f), p(0.5f, 0.6f)), style = thin)
            drawCircle(color, radius = w * 0.055f, center = p(0.5f, 0.74f))
        }
    }
}
