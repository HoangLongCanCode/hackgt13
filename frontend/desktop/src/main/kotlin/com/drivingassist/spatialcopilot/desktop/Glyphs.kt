package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.spatialcopilot.nav.NavGlyph
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One piece of an instruction glyph: [shape] filled, or stroked with [stroke] (a fraction of the glyph size). */
data class GlyphPart(val shape: Shape, val stroke: Float?, val alpha: Float = 1f)

/**
 * The instruction banner's glyphs as Java2D shapes, point for point the app's Hud.drawGlyph: drawn in a [size] square,
 * the left-hand forms mirrored for the right. Strokes: 0.12 of the size (main), 0.08 (thin); arrow heads filled.
 */
object Glyphs {
    const val STROKE = 0.12f
    const val THIN = 0.08f

    fun parts(glyph: NavGlyph, size: Float): List<GlyphPart> {
        val w = size
        val h = size
        val mirror = glyph == NavGlyph.TURN_RIGHT || glyph == NavGlyph.SLIGHT_RIGHT || glyph == NavGlyph.EXIT_RIGHT
        fun px(x: Float) = (if (mirror) 1f - x else x) * w
        fun py(y: Float) = y * h
        val out = ArrayList<GlyphPart>()
        val headLength = w * 0.24f

        fun line(pts: List<Pair<Float, Float>>, stroke: Float = STROKE, alpha: Float = 1f) {
            val p = Path2D.Float()
            p.moveTo(px(pts[0].first), py(pts[0].second))
            for (i in 1 until pts.size) p.lineTo(px(pts[i].first), py(pts[i].second))
            out += GlyphPart(p, w * stroke, alpha)
        }

        /** Filled head whose base centre is [base] (unit coords), pointing away from [from]. */
        fun head(base: Pair<Float, Float>, from: Pair<Float, Float>) {
            val bx = px(base.first)
            val by = py(base.second)
            val dx = bx - px(from.first)
            val dy = by - py(from.second)
            val len = hypot(dx, dy).takeIf { it > 0f } ?: return
            val ux = dx / len
            val uy = dy / len
            val tipX = bx + ux * headLength
            val tipY = by + uy * headLength
            val nx = -uy * headLength * 0.8f
            val ny = ux * headLength * 0.8f
            val p = Path2D.Float()
            p.moveTo(tipX, tipY)
            p.lineTo(bx + nx, by + ny)
            p.lineTo(bx - nx, by - ny)
            p.closePath()
            out += GlyphPart(p, null)
        }

        /** a -> straight to [bendY] -> quadratic through c to e. */
        fun curve(a: Pair<Float, Float>, bendY: Float, c: Pair<Float, Float>, e: Pair<Float, Float>, stroke: Float = STROKE, alpha: Float = 1f) {
            val p = Path2D.Float()
            p.moveTo(px(a.first), py(a.second))
            p.lineTo(px(a.first), py(bendY))
            p.quadTo(px(c.first), py(c.second), px(e.first), py(e.second))
            out += GlyphPart(p, w * stroke, alpha)
        }

        fun circle(cx: Float, cy: Float, r: Float, stroke: Float?) {
            out += GlyphPart(Ellipse2D.Float(px(cx) - r, py(cy) - r, 2 * r, 2 * r), stroke?.let { w * it })
        }

        when (glyph) {
            NavGlyph.STRAIGHT -> {
                line(listOf(0.5f to 0.94f, 0.5f to 0.32f))
                head(0.5f to 0.3f, 0.5f to 0.6f)
            }
            NavGlyph.TURN_LEFT, NavGlyph.TURN_RIGHT -> {
                curve(0.66f to 0.94f, 0.58f, 0.66f to 0.38f, 0.44f to 0.38f)
                line(listOf(0.44f to 0.38f, 0.34f to 0.38f))
                head(0.32f to 0.38f, 0.44f to 0.38f)
            }
            NavGlyph.SLIGHT_LEFT, NavGlyph.SLIGHT_RIGHT -> {
                line(listOf(0.62f to 0.94f, 0.62f to 0.6f, 0.42f to 0.38f))
                head(0.42f to 0.38f, 0.62f to 0.6f)
            }
            NavGlyph.EXIT_LEFT, NavGlyph.EXIT_RIGHT -> {
                line(listOf(0.66f to 0.94f, 0.66f to 0.06f), stroke = THIN, alpha = 0.35f)
                curve(0.66f to 0.94f, 0.7f, 0.66f to 0.5f, 0.42f to 0.36f)
                head(0.42f to 0.36f, 0.66f to 0.5f)
            }
            NavGlyph.MERGE -> {
                val p = Path2D.Float()
                p.moveTo(px(0.16f), py(0.94f))
                p.quadTo(px(0.16f), py(0.62f), px(0.5f), py(0.46f))
                out += GlyphPart(p, w * THIN, 0.6f)
                line(listOf(0.5f to 0.94f, 0.5f to 0.32f))
                head(0.5f to 0.3f, 0.5f to 0.6f)
            }
            NavGlyph.STOP -> {
                val r = w * 0.42f
                val p = Path2D.Float()
                for (i in 0 until 8) {
                    val a = (PI / 8 + i * PI / 4).toFloat()
                    val x = w / 2 + r * cos(a)
                    val y = h / 2 + r * sin(a)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.closePath()
                out += GlyphPart(p, w * THIN)
                line(listOf(0.32f to 0.5f, 0.68f to 0.5f), stroke = THIN)
            }
            NavGlyph.ARRIVE -> {
                circle(0.5f, 0.36f, w * 0.22f, THIN)
                line(listOf(0.3f to 0.45f, 0.5f to 0.92f, 0.7f to 0.45f), stroke = THIN)
                circle(0.5f, 0.36f, w * 0.07f, null)
            }
            NavGlyph.OFF_ROUTE -> {
                val p = Path2D.Float()
                p.moveTo(px(0.5f), py(0.08f))
                p.lineTo(px(0.94f), py(0.88f))
                p.lineTo(px(0.06f), py(0.88f))
                p.closePath()
                out += GlyphPart(p, w * THIN)
                line(listOf(0.5f to 0.38f, 0.5f to 0.6f), stroke = THIN)
                circle(0.5f, 0.74f, w * 0.055f, null)
            }
        }
        return out
    }
}
