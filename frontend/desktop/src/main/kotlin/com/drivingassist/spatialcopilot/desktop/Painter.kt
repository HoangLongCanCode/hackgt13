package com.drivingassist.spatialcopilot.desktop

import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.spatialcopilot.ar.ArScene
import com.drivingassist.spatialcopilot.ar.DebugLayer
import com.drivingassist.spatialcopilot.ar.FillCenter
import com.drivingassist.spatialcopilot.ar.LaneArrowStyle
import com.drivingassist.spatialcopilot.ar.LeadHighlight
import com.drivingassist.spatialcopilot.ar.Vec2
import com.drivingassist.spatialcopilot.ar.ViewRect
import com.drivingassist.spatialcopilot.nav.Instruction
import com.drivingassist.spatialcopilot.nav.MapPoint
import com.drivingassist.spatialcopilot.nav.RouteGuide
import com.drivingassist.spatialcopilot.nav.RouteMap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a [FrameState] with Java2D the way the app's Compose code draws it: the clip scaled like Media3
 * RESIZE_MODE_ZOOM (FILL_CENTER), SpatialArEngine's layers (debug shade and overlays, lane arrows clipped to the
 * drivable road minus the road users, the debug chevrons, the arrival pin, the lead brackets), then CopilotScreen's
 * chrome: clean view = speed-limit sign, instruction banner, critical pill, corner button (+ status note); debug view =
 * status chip, numbers, maneuver card, route map, banner, alert pill. Sizes are the app's dp / sp on the tablet's pixels.
 */
object Painter {
    private val Mint = Color(0x7D, 0xFF, 0xC3)
    private val Cyan = Color(0x9B, 0xE7, 0xFF)
    private val Amber = Color(0xFF, 0xC5, 0x6B)
    private val AlertRed = Color(0xFF, 0x5A, 0x4E)
    private val LaneGreen = Color(0x46, 0xE2, 0x7A)
    private val LaneRed = Color(0xFF, 0x4B, 0x3E)
    private val LayoutViolet = Color(0xD6, 0x9C, 0xFF)
    private val Ink = Color(0x10, 0x16, 0x14, 0xCC)
    private val BannerAmberBg = Color(0x3A, 0x2A, 0x08, 0xCC)

    private const val UI = "Segoe UI"
    private const val MONO = "Consolas"

    fun paint(g: Graphics2D, f: FrameState, stamp: Boolean = false) {
        val fit = f.fit
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val saved = g.transform
        g.scale(fit.scale.toDouble(), fit.scale.toDouble())
        val c = Canvas(g, fit)
        c.video(f)
        c.scene(f)
        c.chrome(f)
        if (stamp) c.stamp(f)
        g.transform = saved
    }

    /** Colour with [alpha] (0-1) times its own alpha. */
    private fun Color.a(alpha: Float): Color = Color(red, green, blue, (this.alpha * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255))

    private class Canvas(val g: Graphics2D, val fit: ViewFit) {
        val w = fit.width
        val h = fit.height
        fun dp(v: Float) = fit.dp(v)
        fun sp(v: Float) = fit.dp(v)

        // ------------------------------------------------------------------------------------------ video

        fun video(f: FrameState) {
            g.color = Color.BLACK
            g.fill(Rectangle2D.Float(0f, 0f, w, h))
            val v = f.video
            if (v == null) {
                val msg = f.problem ?: if (f.playing) "Waiting for video..." else "Paused"
                text(msg, font(UI, Font.PLAIN, sp(16f)), Color.WHITE.a(0.7f), w / 2f, h / 2f, center = true)
                return
            }
            val map = FillCenter(v.image.width, v.image.height, w, h)
            g.drawImage(v.image, Math.round(map.dx), Math.round(map.dy), Math.round(v.image.width * map.scale), Math.round(v.image.height * map.scale), null)
        }

        // ------------------------------------------------------------------------------------ AR scene

        fun scene(f: FrameState) {
            val scene = f.scene
            if (f.debug) vignette()
            scene.debug?.let { debugLayer(it) }
            laneArrows(scene)
            arrows(scene)
            scene.lead?.let { lead(it, f.nowNs / 1e9f) }
        }

        private fun vignette() {
            val paint = LinearGradientPaint(
                0f, 0f, 0f, h,
                floatArrayOf(0f, 0.18f, 0.72f, 1f),
                arrayOf(Color(0, 0, 0, (0.28f * 255).toInt()), Color(0, 0, 0, 0), Color(0, 0, 0, 0), Color(0, 0, 0, (0.40f * 255).toInt())),
            )
            g.paint = paint
            g.fill(Rectangle2D.Float(0f, 0f, w, h))
        }

        /** Filled arrows, only on the drivable road and never over the road users (SpatialArEngine.drawLaneArrows). */
        private fun laneArrows(scene: ArScene) {
            if (scene.laneArrows.isEmpty()) return
            val clip = Area(Rectangle2D.Float(0f, 0f, w, h))
            if (scene.drivable.size >= 3) clip.intersect(Area(polygon(scene.drivable, close = true)))
            scene.occluders.forEach { clip.subtract(Area(Rectangle2D.Float(it.left, it.top, it.width, it.height))) }
            val old = g.clip
            g.clip(clip)
            val edge = BasicStroke(dp(2f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            for (a in scene.laneArrows) {
                if (a.outline.size < 3) continue
                val path = polygon(a.outline, close = true)
                val color = when (a.style) {
                    LaneArrowStyle.TARGET, LaneArrowStyle.TARGET_BLINK -> LaneGreen
                    LaneArrowStyle.WRONG -> LaneRed
                    LaneArrowStyle.OTHER -> Color.WHITE
                }
                g.color = color.a(a.alpha)
                g.fill(path)
                g.color = Color.BLACK.a(0.35f * a.alpha)
                g.stroke = edge
                g.draw(path)
            }
            g.clip = old
        }

        private fun arrows(scene: ArScene) {
            if (scene.ribbon.size >= 3 && scene.ribbonAlpha > 0.01f) {
                g.color = Mint.a(scene.ribbonAlpha)
                g.fill(polygon(scene.ribbon, close = true))
            }
            scene.chevrons.forEach { c ->
                val path = polygon(listOf(c.left, c.tip, c.right), close = false)
                g.color = Color.BLACK.a(0.35f * c.alpha)
                g.stroke = BasicStroke(c.strokePx * 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
                g.color = Mint.a(c.alpha)
                g.stroke = BasicStroke(c.strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
            scene.pin?.let { p ->
                val r = dp(14f)
                g.color = Mint.a(0.25f)
                g.fill(circle(p.x, p.y, r * 2f))
                g.color = Mint
                g.fill(circle(p.x, p.y, r * 0.6f))
            }
        }

        private fun lead(lead: LeadHighlight, time: Float) {
            val r = lead.rect
            if (r.width < 4f || r.height < 4f) return
            val base = if (lead.critical) AlertRed else Amber
            val pulse = if (lead.critical) 0.7f + 0.3f * ((sin(time * 6.0f) + 1f) * 0.5f) else 0.95f
            val color = base.a(pulse)
            val arm = min(r.width, r.height) * 0.28f
            g.color = color
            g.stroke = BasicStroke(dp(if (lead.critical) 4f else 3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = g.draw(Line2D.Float(x1, y1, x2, y2))
            val l = r.left; val t = r.top; val rr = r.right; val b = r.bottom
            seg(l, t + arm, l, t); seg(l, t, l + arm, t)
            seg(rr - arm, t, rr, t); seg(rr, t, rr, t + arm)
            seg(rr, b - arm, rr, b); seg(rr, b, rr - arm, b)
            seg(l + arm, b, l, b); seg(l, b, l, b - arm)
            if (!lead.badge) return
            val label = if (lead.critical) "TOO CLOSE · ${lead.label}" else lead.label
            badge(label, r.centerX, t - dp(8f), color, centered = true, bold = lead.critical)
        }

        private fun debugLayer(d: DebugLayer) {
            d.horizonY?.let { y ->
                g.color = Color.WHITE.a(0.35f)
                g.stroke = BasicStroke(dp(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(12f, 10f), 0f)
                g.draw(Line2D.Float(0f, y, w, y))
            }
            d.laneLines.forEach { polyline(it, Color.WHITE.a(0.7f), dp(2f)) }
            val layoutColor = LayoutViolet.a(if (d.layoutUsed) 0.9f else 0.4f)
            d.layoutLines.forEach { polyline(it.points, layoutColor, dp(2f), dashed = !it.detected) }
            d.vanishingPoint?.let { vp ->
                val r = dp(7f)
                g.color = layoutColor
                g.stroke = BasicStroke(dp(2f))
                g.draw(circle(vp.x, vp.y, r))
                g.stroke = BasicStroke(dp(1.5f))
                g.draw(Line2D.Float(vp.x - 1.8f * r, vp.y, vp.x + 1.8f * r, vp.y))
                g.draw(Line2D.Float(vp.x, vp.y - 1.8f * r, vp.x, vp.y + 1.8f * r))
            }
            polyline(d.egoLane, Cyan.a(0.9f), dp(2f), dashed = true)
            g.color = Amber
            d.anchors.forEach { g.fill(circle(it.x, it.y, dp(4f))) }
            d.boxes.forEach { (r, tag) -> debugBox(r, tag) }
            d.egoLaneSource?.let { badge("ego lane: ${it.name.lowercase()}", dp(12f), h - dp(60f), Cyan, centered = false, bold = false) }
            d.laneStatus?.let { badge(it, dp(12f), h - dp(96f), Cyan, centered = false, bold = false) }
        }

        private fun debugBox(r: ViewRect, tag: String) {
            g.color = Cyan.a(0.8f)
            g.stroke = BasicStroke(dp(1.5f))
            g.draw(Rectangle2D.Float(r.left, r.top, r.width, r.height))
            badge(tag, r.left, r.top - dp(2f), Cyan, centered = false, bold = false, small = true)
        }

        private fun polyline(points: List<Vec2>, color: Color, width: Float, dashed: Boolean = false) {
            if (points.size < 2) return
            g.color = color
            g.stroke = if (dashed) BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(18f, 12f), 0f)
            else BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(polygon(points, close = false))
        }

        /** SpatialArEngine.drawBadge: a dark pill with the text, kept 8 px inside the view. */
        private fun badge(text: String, x: Float, y: Float, color: Color, centered: Boolean, bold: Boolean, small: Boolean = false) {
            val font = font(UI, if (bold) Font.BOLD else SEMIBOLD, sp(if (small) 11f else 16f))
            val fm = g.getFontMetrics(font)
            val padX = dp(if (small) 5f else 9f)
            val padY = dp(if (small) 2f else 5f)
            val lineH = (fm.ascent + fm.descent).toFloat()
            val width = fm.stringWidth(text) + padX * 2
            val height = lineH + padY * 2
            val left = (if (centered) x - width / 2f else x).coerceIn(8f, max(8f, w - width - 8f))
            val top = (y - height).coerceIn(8f, max(8f, h - height - 8f))
            g.color = Color.BLACK.a(0.6f)
            g.fill(RoundRectangle2D.Float(left, top, width, height, height, height))
            g.color = color
            g.font = font
            g.drawString(text, left + padX, top + padY + fm.ascent)
        }

        // --------------------------------------------------------------------------------------- chrome

        fun chrome(f: FrameState) {
            val pad = dp(16f)
            if (f.debug) debugChrome(f, pad) else cleanChrome(f, pad)
        }

        private fun cleanChrome(f: FrameState, pad: Float) {
            f.context.speedLimit?.let { speedLimit(it, pad, pad) }
            f.instruction?.let { instructionBanner(it, pad) }
            HudText.criticalAlert(f.context)?.let { alertPill(it, critical = true, bottom = h - pad) }
            val size = dp(44f)
            val bx = pad
            val by = h - pad - size
            cornerButton(bx, by, f.status.level)
            f.status.short?.takeIf { f.status.level != DesktopStatus.Level.OK }?.let { statusNote(it, f.status.level, bx + size + dp(8f), by + size / 2f) }
            f.problem?.takeIf { it.startsWith("No clip") }?.let { centerNote(it) }
        }

        private fun debugChrome(f: FrameState, pad: Float) {
            var y = pad
            f.context.speedLimit?.let { speedLimit(it, pad, y); y += dp(80f) + dp(8f) }
            y += statusChip(f.status, pad, y) + dp(8f)
            debugPanel(f.status.debugLines + f.desktopLines + cueLines(f), pad, y)
            f.route?.let { maneuverCard(it, f.routeDistance, w - pad, pad) }
            f.route?.takeIf { !it.stale && !it.polyline.isNullOrEmpty() && it.carLocation != null }?.let { routeMap(it, w - pad, h - pad - dp(4f)) }
            var top = pad
            f.instruction?.let { top += instructionBanner(it, top) + dp(8f) }
            f.status.banner?.let { statusBanner(it, top + dp(4f)) }
            HudText.debugAlert(f.context, f.merged)?.let { (text, critical) -> alertPill(text, critical, bottom = h - pad) }
        }

        private fun cueLines(f: FrameState): List<String> = f.cues.takeLast(4).map { c ->
            "cue ${Options.fmt(c.atPts, 1)} s ${c.cueId} ${c.outcome}" + (c.text?.let { " \"$it\"" } ?: "")
        }

        /** Hud.SpeedLimitSign: 64 x 80 dp, white with a black inner border. */
        private fun speedLimit(mph: Int, x: Float, y: Float) {
            val bw = dp(64f)
            val bh = dp(80f)
            g.color = Color.WHITE
            g.fill(RoundRectangle2D.Float(x, y, bw, bh, dp(16f), dp(16f)))
            val inset = dp(3f)
            val bs = dp(2f)
            g.color = Color.BLACK
            g.stroke = BasicStroke(bs)
            g.draw(RoundRectangle2D.Float(x + inset + bs / 2, y + inset + bs / 2, bw - 2 * inset - bs, bh - 2 * inset - bs, dp(10f), dp(10f)))
            val small = font(UI, Font.BOLD, sp(11f))
            val big = font(UI, BLACK_WEIGHT, sp(if (mph >= 100) 24f else 32f))
            val total = sp(12f) * 2 + sp(34f)
            var top = y + (bh - total) / 2f
            text("SPEED", small, Color.BLACK, x + bw / 2f, top, lineHeight = sp(12f), center = true); top += sp(12f)
            text("LIMIT", small, Color.BLACK, x + bw / 2f, top, lineHeight = sp(12f), center = true); top += sp(12f)
            text(mph.toString(), big, Color.BLACK, x + bw / 2f, top, lineHeight = sp(34f), center = true)
        }

        /** Hud.InstructionBanner at the top centre; returns its height. */
        private fun instructionBanner(ins: Instruction, top: Float): Float {
            val accent = if (ins.dim) Amber else Mint
            val primaryFont = font(UI, Font.BOLD, sp(24f))
            val secondaryFont = font(UI, Font.PLAIN, sp(14f))
            val padH = dp(16f)
            val padV = dp(10f)
            val glyph = dp(40f)
            val gap = dp(14f)
            val maxText = dp(560f) - 2 * padH - glyph - gap
            val pm = g.getFontMetrics(primaryFont)
            val sm = g.getFontMetrics(secondaryFont)
            val primary = HudText.ellipsize(ins.primary, maxText) { pm.stringWidth(it).toFloat() }
            val secondary = ins.secondary?.let { s -> HudText.ellipsize(s, maxText) { sm.stringWidth(it).toFloat() } }
            val textW = max(pm.stringWidth(primary), secondary?.let { sm.stringWidth(it) } ?: 0).toFloat()
            val primaryH = sp(30f)
            val secondaryH = if (secondary != null) sp(20f) else 0f
            val contentH = max(glyph, primaryH + secondaryH)
            val bw = 2 * padH + glyph + gap + textW
            val bh = 2 * padV + contentH
            val left = (w - bw) / 2f
            val shape = RoundRectangle2D.Float(left, top, bw, bh, dp(36f), dp(36f))
            g.color = Color.BLACK.a(if (ins.dim) 0.5f else 0.62f)
            g.fill(shape)
            g.color = accent.a(if (ins.dim) 0.7f else 0.35f)
            g.stroke = BasicStroke(dp(1f))
            g.draw(shape)
            glyph(ins, left + padH, top + (bh - glyph) / 2f, glyph, accent)
            val tx = left + padH + glyph + gap
            val ty = top + (bh - primaryH - secondaryH) / 2f
            text(primary, primaryFont, if (ins.dim) Amber else Color.WHITE, tx, ty, lineHeight = primaryH)
            secondary?.let { text(it, secondaryFont, if (ins.dim) Amber.a(0.85f) else Color.WHITE.a(0.75f), tx, ty + primaryH, lineHeight = secondaryH) }
            return bh
        }

        private fun glyph(ins: Instruction, x: Float, y: Float, size: Float, color: Color) {
            val saved = g.transform
            g.translate(x.toDouble(), y.toDouble())
            for (p in Glyphs.parts(ins.glyph, size)) {
                g.color = color.a(p.alpha)
                if (p.stroke == null) g.fill(p.shape) else {
                    g.stroke = BasicStroke(p.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(p.shape)
                }
            }
            g.transform = saved
        }

        /** CopilotScreen.AlertPill at the bottom centre. */
        private fun alertPill(text: String, critical: Boolean, bottom: Float) {
            val font = font(UI, if (critical) Font.BOLD else SEMIBOLD, sp(if (critical) 22f else 18f))
            val fm = g.getFontMetrics(font)
            val lineH = sp(if (critical) 28f else 24f)
            val bw = fm.stringWidth(text) + 2 * dp(20f)
            val bh = lineH + 2 * dp(10f)
            val left = (w - bw) / 2f
            val top = bottom - bh
            g.color = (if (critical) AlertRed else Amber).a(if (critical) 0.85f else 0.55f)
            g.fill(RoundRectangle2D.Float(left, top, bw, bh, dp(48f), dp(48f)))
            text(text, font, Color.WHITE, left + dp(20f), top + dp(10f), lineHeight = lineH)
        }

        /** Hud.HudCornerButton: the round settings button with the status dot. */
        private fun cornerButton(x: Float, y: Float, level: DesktopStatus.Level) {
            val s = dp(44f)
            g.color = Ink
            g.fill(Ellipse2D.Float(x, y, s, s))
            g.color = Color.WHITE.a(0.9f)
            g.stroke = BasicStroke(dp(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val lx = x + (s - dp(16f)) / 2f
            val ly = y + (s - dp(14f)) / 2f + dp(2f)
            for (fr in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                val yy = ly + dp(12f) * fr
                g.draw(Line2D.Float(lx, yy, lx + dp(16f), yy))
            }
            g.color = levelColor(level)
            g.fill(Ellipse2D.Float(x + s - dp(7f) - dp(8f), y + dp(7f), dp(8f), dp(8f)))
        }

        private fun statusNote(text: String, level: DesktopStatus.Level, x: Float, centerY: Float) {
            val font = font(UI, SEMIBOLD, sp(14f))
            val fm = g.getFontMetrics(font)
            val t = HudText.ellipsize(text, dp(300f)) { fm.stringWidth(it).toFloat() }
            val lineH = sp(20f)
            val bw = fm.stringWidth(t) + 2 * dp(12f)
            val bh = lineH + 2 * dp(8f)
            val top = centerY - bh / 2f
            g.color = Ink
            g.fill(RoundRectangle2D.Float(x, top, bw, bh, dp(28f), dp(28f)))
            text(t, font, levelColor(level), x + dp(12f), top + dp(8f), lineHeight = lineH)
        }

        private fun centerNote(text: String) {
            val font = font(UI, Font.PLAIN, sp(14f))
            val lines = wrap(text, font, dp(560f) - dp(28f))
            val fm = g.getFontMetrics(font)
            val lineH = sp(20f)
            val bw = lines.maxOf { fm.stringWidth(it) } + dp(28f)
            val bh = lines.size * lineH + dp(20f)
            val left = (w - bw) / 2f
            val top = (h - bh) / 2f
            g.color = Ink
            g.fill(RoundRectangle2D.Float(left, top, bw, bh, dp(24f), dp(24f)))
            lines.forEachIndexed { i, l -> text(l, font, Color.WHITE, left + dp(14f), top + dp(10f) + i * lineH, lineHeight = lineH) }
        }

        /** CopilotScreen.StatusChip (debug); returns its height. */
        private fun statusChip(s: DesktopStatus, x: Float, y: Float): Float {
            val f1 = font(UI, SEMIBOLD, sp(13f))
            val f2 = font(UI, Font.PLAIN, sp(11f))
            val line1 = s.line1 + " · DEBUG"
            val line2 = s.line2 ?: "D: clean view · Space: pause · Left/Right: seek · N/P/O: clip"
            val maxW = dp(420f)
            val m1 = g.getFontMetrics(f1)
            val m2 = g.getFontMetrics(f2)
            val t1 = HudText.ellipsize(line1, maxW) { m1.stringWidth(it).toFloat() }
            val t2 = HudText.ellipsize(line2, maxW) { m2.stringWidth(it).toFloat() }
            val bw = max(m1.stringWidth(t1), m2.stringWidth(t2)) + 2 * dp(12f)
            val h1 = sp(18f)
            val h2 = sp(15f)
            val bh = h1 + h2 + 2 * dp(8f)
            g.color = Ink
            g.fill(RoundRectangle2D.Float(x, y, bw, bh, dp(28f), dp(28f)))
            text(t1, f1, levelColor(s.level), x + dp(12f), y + dp(8f), lineHeight = h1)
            text(t2, f2, if (s.line2 != null) Amber else Color.WHITE.a(0.65f), x + dp(12f), y + dp(8f) + h1, lineHeight = h2)
            return bh
        }

        private fun debugPanel(lines: List<String>, x: Float, y: Float) {
            if (lines.isEmpty()) return
            val font = font(MONO, Font.PLAIN, sp(11f))
            val fm = g.getFontMetrics(font)
            val maxW = dp(520f)
            val shown = lines.map { l -> HudText.ellipsize(l, maxW) { fm.stringWidth(it).toFloat() } }
            val lineH = sp(14f)
            val bw = shown.maxOf { fm.stringWidth(it) } + 2 * dp(10f)
            val bh = shown.size * lineH + 2 * dp(6f)
            g.color = Color.BLACK.a(0.6f)
            g.fill(RoundRectangle2D.Float(x, y, bw, bh, dp(20f), dp(20f)))
            shown.forEachIndexed { i, l -> text(l, font, Color.WHITE.a(0.9f), x + dp(10f), y + dp(6f) + i * lineH, lineHeight = lineH) }
        }

        /** CopilotScreen.ManeuverCard (debug, top right, [right] = its right edge). */
        private fun maneuverCard(route: RouteGuide, distanceNow: Double?, right: Float, top: Float) {
            val dim = route.stale || route.offRoute
            data class Row(val text: String, val font: Font, val color: Color, val lineH: Float, val tracking: Float = 0f)
            val rows = ArrayList<Row>()
            rows += Row(if (route.offRoute) "OFF ROUTE" else route.headline, font(UI, Font.BOLD, sp(22f)), if (dim) Amber else Mint, sp(28f), tracking = sp(1.5f))
            distanceNow?.takeIf { route.maneuver != Maneuver.FOLLOW_ROAD }?.let {
                rows += Row(RouteGuide.formatDistance(it), font(LIGHT, Font.PLAIN, sp(40f)), Color.WHITE, sp(48f))
            }
            route.roadName?.takeIf { it.length <= 40 }?.let { rows += Row(it, font(UI, Font.PLAIN, sp(13f)), Color.WHITE.a(0.85f), sp(18f)) }
            val eta = route.etaSeconds?.let { "ETA ${RouteGuide.formatEta(it)}" }
            val remaining = route.remainingMeters?.let { RouteGuide.formatDistance(it) + " left" }
            listOfNotNull(remaining, eta).takeIf { it.isNotEmpty() }?.let { rows += Row(it.joinToString(" · "), font(UI, Font.PLAIN, sp(12f)), Color.WHITE.a(0.7f), sp(17f)) }
            route.destination?.takeIf { route.provider != "demo" }?.let { d ->
                val fnt = font(UI, Font.PLAIN, sp(12f))
                val fm = g.getFontMetrics(fnt)
                rows += Row(HudText.ellipsize("to $d", dp(300f)) { fm.stringWidth(it).toFloat() }, fnt, Color.WHITE.a(0.7f), sp(17f))
            }
            when {
                route.provider == "demo" -> rows += Row("DEMO ROUTE", font(UI, SEMIBOLD, sp(11f)), Amber, sp(15f))
                route.stale -> rows += Row("ROUTE HELD · NO UPDATES", font(UI, SEMIBOLD, sp(11f)), Amber, sp(15f))
            }
            fun width(r: Row) = g.getFontMetrics(r.font).stringWidth(r.text) + r.tracking * r.text.length
            val contentW = rows.maxOf { width(it) }
            val bw = contentW + 2 * dp(18f)
            val bh = rows.sumOf { it.lineH.toDouble() }.toFloat() + 2 * dp(12f)
            val left = right - bw
            val shape = RoundRectangle2D.Float(left, top, bw, bh, dp(36f), dp(36f))
            g.color = Color.BLACK.a(0.46f)
            g.fill(shape)
            g.color = (if (dim) Amber else Mint).a(0.85f)
            g.stroke = BasicStroke(dp(1f))
            g.draw(shape)
            var y = top + dp(12f)
            for (r in rows) {
                val rw = width(r)
                text(r.text, r.font, r.color, right - dp(18f) - rw, y, lineHeight = r.lineH, tracking = r.tracking)
                y += r.lineH
            }
        }

        /** CopilotScreen.RouteMapCard (debug, bottom right): heading-up route line around the car. */
        private fun routeMap(route: RouteGuide, right: Float, bottom: Float) {
            val car = route.carLocation ?: return
            val points = RouteMap.decode(route.polyline.orEmpty())
            if (points.size < 2) return
            val moving = (route.speedMps ?: 0.0) >= 2.0
            val heading = (if (moving) route.headingDegrees?.takeIf { it > 0.0 } else null) ?: RouteMap.routeHeadingNear(points, car) ?: 0.0
            val projected = RouteMap.project(points, car, heading)
            val bw = dp(240f)
            val bh = dp(170f)
            val left = right - bw
            val top = bottom - bh
            val shape = RoundRectangle2D.Float(left, top, bw, bh, dp(32f), dp(32f))
            g.color = Color.BLACK.a(0.55f)
            g.fill(shape)
            g.color = Mint.a(0.5f)
            g.stroke = BasicStroke(dp(1f))
            g.draw(shape)
            val cx0 = left + dp(8f)
            val cy0 = top + dp(8f)
            val cw = bw - dp(16f)
            val ch = bh - dp(16f)
            val metresPerPx = RANGE_M / (ch * 0.75)
            val originX = cx0 + cw / 2f
            val originY = cy0 + ch * 0.78f
            fun at(p: MapPoint) = Vec2(originX + (p.right / metresPerPx).toFloat(), originY - (p.up / metresPerPx).toFloat())
            val old = g.clip
            g.clip(Rectangle2D.Float(cx0, cy0, cw, ch))
            val path = polygon(projected.map(::at), close = false)
            g.color = Color.BLACK.a(0.6f)
            g.stroke = BasicStroke(dp(9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
            g.color = Mint
            g.stroke = BasicStroke(dp(5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
            val d = at(projected.last())
            g.color = Color.WHITE
            g.fill(circle(d.x, d.y, dp(5f)))
            g.color = Mint
            g.fill(circle(d.x, d.y, dp(3f)))
            g.clip = old
            val r = dp(9f)
            val carShape = Path2D.Float().apply {
                moveTo(originX, originY - r); lineTo(originX + r * 0.75f, originY + r * 0.7f); lineTo(originX, originY + r * 0.3f)
                lineTo(originX - r * 0.75f, originY + r * 0.7f); closePath()
            }
            g.color = Color.WHITE
            g.fill(carShape)
            text("${RANGE_M.toInt()} m · ${route.provider ?: "route"}", font(UI, Font.PLAIN, sp(10f)), Color.WHITE.a(0.7f), left + dp(10f), top + dp(6f), lineHeight = sp(13f))
        }

        private fun statusBanner(text: String, top: Float) {
            val font = font(UI, Font.PLAIN, sp(14f))
            val lines = wrap(text, font, dp(560f) - dp(28f))
            val fm = g.getFontMetrics(font)
            val lineH = sp(20f)
            val bw = lines.maxOf { fm.stringWidth(it) } + dp(28f)
            val bh = lines.size * lineH + dp(16f)
            val left = (w - bw) / 2f
            val shape = RoundRectangle2D.Float(left, top, bw, bh, dp(24f), dp(24f))
            g.color = BannerAmberBg
            g.fill(shape)
            g.color = Amber.a(0.8f)
            g.stroke = BasicStroke(dp(1f))
            g.draw(shape)
            lines.forEachIndexed { i, l -> text(l, font, Color.WHITE, left + dp(14f), top + dp(8f) + i * lineH, lineHeight = lineH) }
        }

        /** Viewer-only (offscreen PNGs): clip and media time in the bottom-right corner. */
        fun stamp(f: FrameState) {
            val font = font(MONO, Font.PLAIN, sp(11f))
            val label = "${f.videoId} ${Options.fmt(f.pts, 2)} s"
            val fm = g.getFontMetrics(font)
            text(label, font, Color.WHITE.a(0.55f), w - dp(8f) - fm.stringWidth(label), h - dp(8f) - sp(14f), lineHeight = sp(14f))
        }

        // -------------------------------------------------------------------------------------- helpers

        private fun levelColor(level: DesktopStatus.Level): Color = when (level) {
            DesktopStatus.Level.OK -> Mint
            DesktopStatus.Level.WARN -> Amber
            DesktopStatus.Level.ERROR -> AlertRed
        }

        /** One line of text in a box of [lineHeight] starting at [top] ([center]: [x] is the centre). */
        fun text(s: String, font: Font, color: Color, x: Float, top: Float, lineHeight: Float? = null, center: Boolean = false, tracking: Float = 0f) {
            val fm = g.getFontMetrics(font)
            val lh = lineHeight ?: (fm.ascent + fm.descent).toFloat()
            val baseline = top + (lh - (fm.ascent + fm.descent)) / 2f + fm.ascent
            val width = fm.stringWidth(s) + tracking * s.length
            val left = if (center) x - width / 2f else x
            g.font = font
            g.color = color
            if (tracking == 0f) g.drawString(s, left, baseline) else {
                var cx = left
                for (ch in s) {
                    g.drawString(ch.toString(), cx, baseline)
                    cx += fm.charWidth(ch) + tracking
                }
            }
        }

        private fun wrap(text: String, font: Font, maxWidth: Float): List<String> {
            val fm = g.getFontMetrics(font)
            val out = ArrayList<String>()
            var line = ""
            for (word in text.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && fm.stringWidth(next) > maxWidth) { out += line; line = word } else line = next
            }
            if (line.isNotEmpty()) out += line
            return out.ifEmpty { listOf("") }
        }

        private fun polygon(points: List<Vec2>, close: Boolean): Path2D.Float {
            val p = Path2D.Float()
            p.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
            if (close) p.closePath()
            return p
        }

        private fun circle(x: Float, y: Float, r: Float): Shape = Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r)
    }

    private const val RANGE_M = 300.0

    /** Font styles beyond Java's PLAIN / BOLD: Windows families of the same typeface. */
    private const val SEMIBOLD = -1
    private const val BLACK_WEIGHT = -2
    private const val LIGHT = "Segoe UI Light"

    private val fonts = HashMap<Triple<String, Int, Float>, Font>()

    private fun font(family: String, style: Int, size: Float): Font = synchronized(fonts) {
        fonts.getOrPut(Triple(family, style, size)) {
            val (name, javaStyle) = when (style) {
                SEMIBOLD -> (if (family == UI) "Segoe UI Semibold" else family) to Font.PLAIN
                BLACK_WEIGHT -> (if (family == UI) "Segoe UI Black" else family) to Font.PLAIN
                else -> family to style
            }
            val base = Font(name, javaStyle, 1)
            // Fall back to the logical fonts off Windows (the family then maps to Dialog).
            val usable = if (base.family == Font.DIALOG && name != Font.DIALOG) Font(if (family == MONO) Font.MONOSPACED else Font.SANS_SERIF, if (style < 0) Font.BOLD else javaStyle, 1) else base
            usable.deriveFont(size)
        }
    }

    @Suppress("unused")
    private val identity = AffineTransform()
}
