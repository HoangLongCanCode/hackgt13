package com.drivingassist.spatialcopilot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import com.drivingassist.spatialcopilot.nav.RouteMap
import com.drivingassist.spatialcopilot.nav.MapPoint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivingassist.copilot.context.DrivingContext
import com.drivingassist.copilot.context.DrivingEventType
import com.drivingassist.copilot.context.FollowingState
import com.drivingassist.copilot.context.Maneuver
import com.drivingassist.copilot.context.Priority
import com.drivingassist.copilot.context.WorldSnapshot
import com.drivingassist.copilot.perception.LightState
import com.drivingassist.spatialcopilot.CopilotViewModel
import com.drivingassist.spatialcopilot.camera.DrivingCamera
import com.drivingassist.spatialcopilot.nav.Instruction
import com.drivingassist.spatialcopilot.nav.NavText
import com.drivingassist.spatialcopilot.nav.RouteGuide
import com.drivingassist.spatialcopilot.session.AppSettings
import com.drivingassist.spatialcopilot.session.CopilotSession
import com.drivingassist.spatialcopilot.session.SourceMode
import com.drivingassist.spatialcopilot.session.StatusUi
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Mint = Color(0xFF7DFFC3)
private val Ink = Color(0xCC101614)
private val Amber = Color(0xFFFFC56B)
private val Alert = Color(0xFFFF5A4E)

@Composable
fun CopilotScreen(viewModel: CopilotViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context by session.context.collectAsStateWithLifecycle()
    val route by session.route.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val appContext = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) session.startLocation()
    }
    // Granted in system Settings while we were away: pick it up on resume.
    LifecycleResumeEffect(session) {
        cameraGranted = granted(Manifest.permission.CAMERA)
        onPauseOrDispose { }
    }
    LaunchedEffect(session) {
        if (session.settings.mode != SourceMode.LIVE) return@LaunchedEffect
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filterNot(::granted)
        if (missing.isEmpty()) session.startLocation() else launcher.launch(missing.toTypedArray())
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (session.settings.mode) {
                SourceMode.LIVE -> if (cameraGranted) {
                    DrivingCamera(
                        analyzer = session.camera?.let { it::analyze },
                        onError = { session.reportProblem(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                SourceMode.SIM -> session.sim?.let { SimVideoBackground(it, Modifier.fillMaxSize()) }
                SourceMode.DEMO -> DemoBackdrop()
            }
            SpatialArEngine(session = session, debug = settings.debug, modifier = Modifier.fillMaxSize())
            // LIVE: "Where to?"; the laptop searches places and routes to the one tapped.
            val bridge = session.bridge?.takeIf { session.settings.mode == SourceMode.LIVE }
            val problem by session.problem.collectAsStateWithLifecycle()
            val searchPanel: (@Composable () -> Unit)? = if (bridge != null && showSearch) {
                {
                    val state by session.placeSearch.collectAsStateWithLifecycle()
                    val hello by bridge.serverHello.collectAsStateWithLifecycle()
                    PlaceSearchPanel(
                        state = state,
                        liveNavigation = hello?.let { it.navigationMode == "live" },
                        onSearch = viewModel::search,
                        onPick = { viewModel.goTo(it); showSearch = false },
                        onClose = { showSearch = false },
                    )
                }
            } else {
                null
            }
            Chrome(
                session = session,
                settings = settings,
                status = status,
                context = context,
                route = route,
                problem = problem,
                cameraMissing = session.settings.mode == SourceMode.LIVE && !cameraGranted,
                onAskCamera = { launcher.launch(arrayOf(Manifest.permission.CAMERA)) },
                onChipClick = { if (status.line1.contains("TAKEN OVER")) viewModel.reclaim() else showSettings = true },
                onChipLongClick = viewModel::toggleDebug,
                onWhereTo = bridge?.let { { showSearch = true } },
                searchPanel = searchPanel,
            )
            if (showSettings) {
                SettingsDialog(
                    initial = settings,
                    onDismiss = { showSettings = false },
                    onApply = { viewModel.apply(it); showSettings = false },
                    onSimToggle = session.sim?.let { sim -> { sim.togglePlay() } },
                    onWhereTo = bridge?.let { { showSettings = false; showSearch = true } },
                )
            }
        }
    }
}

/** DEMO has no picture: a plain night-road gradient under the scripted lanes. */
@Composable
private fun DemoBackdrop() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xFF0B1320), 0.46f to Color(0xFF1B2433), 0.47f to Color(0xFF2A2D31), 1f to Color(0xFF15171A)),
        ),
    )
}

/**
 * Clean view (default): speed-limit sign top left, instruction banner top centre, the critical pill bottom centre and
 * one corner button bottom left, nothing else over the road. Debug view: all of that plus the status chip, debug
 * numbers, maneuver card, route map, degraded-state banner and every alert.
 */
@Composable
private fun Chrome(
    session: CopilotSession,
    settings: AppSettings,
    status: StatusUi,
    context: DrivingContext,
    route: RouteGuide?,
    problem: String?,
    cameraMissing: Boolean,
    onAskCamera: () -> Unit,
    onChipClick: () -> Unit,
    onChipLongClick: () -> Unit,
    /** LIVE only: opens the "Where to?" search (null in the other modes). */
    onWhereTo: (() -> Unit)?,
    /** LIVE: the open search panel; null = closed. */
    searchPanel: (@Composable () -> Unit)?,
) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        // The clock a few times a second: the banner dims once packets stop, even when nothing else redraws.
        val hasRoute = route != null
        val nowNs by produceState(session.clockNs(), session, hasRoute) {
            while (hasRoute) {
                value = session.clockNs()
                delay(250)
            }
        }
        val instruction = route?.let { NavText.instruction(it, session.routeDistanceNow(it), nowNs, session.sim?.positionSeconds) }
        if (settings.debug) {
            DebugChrome(session, settings, status, context, route, instruction, onChipClick, onChipLongClick, onWhereTo, searchPanel)
        } else {
            Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                context.speedLimit?.let { SpeedLimitSign(it) }
                searchPanel?.invoke()
            }
            // The open "Where to?" panel (top left, 440 dp) reaches under the centred banner: no banner over its Close button.
            instruction?.takeIf { searchPanel == null }?.let { InstructionBanner(it, Modifier.align(Alignment.TopCenter)) }
            criticalAlertText(context)?.let { AlertPill(it, critical = true, modifier = Modifier.align(Alignment.BottomCenter)) }
            Row(
                Modifier.align(Alignment.BottomStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HudCornerButton(status.level, onClick = onChipClick, onLongClick = onChipLongClick)
                // Tapping the note does what the button does ("Taken over · tap to take back" reclaims).
                status.short?.takeIf { status.level != StatusUi.Level.OK }?.let {
                    HudStatusNote(it, status.level, onClick = onChipClick, onLongClick = onChipLongClick)
                }
                if (onWhereTo != null && searchPanel == null && route == null && settings.destination.isBlank()) {
                    WhereToButton(destination = "", onClick = onWhereTo)
                }
            }
            // Setup only: the SIM clip is not on the tablet (the debug view has it in the status chip).
            problem?.takeIf { session.sim != null && it.startsWith("No clip") }?.let {
                Text(
                    text = it,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(Ink, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (cameraMissing) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Allow the camera, then point it at the road or the driving video.", color = Color.White)
                TextButton(onClick = onAskCamera) { Text("Allow camera") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.DebugChrome(
    session: CopilotSession,
    settings: AppSettings,
    status: StatusUi,
    context: DrivingContext,
    route: RouteGuide?,
    instruction: Instruction?,
    onChipClick: () -> Unit,
    onChipLongClick: () -> Unit,
    onWhereTo: (() -> Unit)?,
    searchPanel: (@Composable () -> Unit)?,
) {
    Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        context.speedLimit?.let { SpeedLimitSign(it) }
        StatusChip(
            status = status,
            debug = true,
            modifier = Modifier.combinedClickable(onClick = onChipClick, onLongClick = onChipLongClick),
        )
        if (searchPanel != null) searchPanel() else if (onWhereTo != null) WhereToButton(settings.destination, onClick = onWhereTo)
        DebugPanel(status.debugLines)
    }
    route?.let { ManeuverCard(it, session.routeDistanceNow(it), Modifier.align(Alignment.TopEnd)) }
    route?.takeIf { !it.stale && !it.polyline.isNullOrEmpty() && it.carLocation != null }?.let {
        RouteMapCard(it, Modifier.align(Alignment.BottomEnd))
    }
    Column(
        Modifier.align(Alignment.TopCenter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        instruction?.takeIf { searchPanel == null }?.let { InstructionBanner(it) }
        status.banner?.let { Banner(it, Modifier.padding(top = 4.dp)) }
    }
    alertText(context, session.bridge?.world?.value)?.let { (text, critical) -> AlertPill(text, critical, Modifier.align(Alignment.BottomCenter)) }
}

/**
 * Clean view: the critical alert only, measurable wording: TOO CLOSE with the lead's measured distance, or a
 * pedestrian in the path at the engine's CRITICAL priority (within 12 m or TTC 3 s). Nothing while perception is stale.
 */
private fun criticalAlertText(ctx: DrivingContext): String? {
    if (ctx.perceptionStale) return null
    val f = ctx.following
    val d = f.distanceMeters
    if (d != null && f.state == FollowingState.CRITICAL) return "TOO CLOSE · Vehicle ahead: ${fmt1(d)} m"
    val ped = ctx.activeAlerts.firstOrNull { it.type == DrivingEventType.PEDESTRIAN_IN_PATH && it.priority == Priority.CRITICAL_SAFETY }
        ?: return null
    return ped.distanceMeters?.let { "Pedestrian ahead: ${it.roundToInt()} m" } ?: "Pedestrian ahead"
}

private fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)

/**
 * Debug view: the contextual alert under the road, measurable wording only: the lead vehicle in CLOSE / TOO CLOSE
 * with its measured distance, a red light or a pedestrian in the path. Nothing without a distance for the lead
 * (never invent a number); green and unknown lights are never shown as alerts.
 */
private fun alertText(ctx: DrivingContext, world: WorldSnapshot?): Pair<String, Boolean>? {
    if (ctx.perceptionStale) return null
    val f = ctx.following
    val d = f.distanceMeters
    if (d != null && f.state == FollowingState.CRITICAL) return "TOO CLOSE · Vehicle ahead: ${fmt1(d)} m" to true
    ctx.pedestriansInPath.firstOrNull()?.let { p ->
        return (p.distanceMeters?.let { "Pedestrian ahead: ${fmt1(it)} m" } ?: "Pedestrian ahead") to true
    }
    if (d != null && f.state == FollowingState.CLOSE) return "Vehicle ahead: ${fmt1(d)} m" to false
    // Lights: the same plausibility checks the voice uses (audio_cues.v1.json alert.red_light), so a misread
    // signal at the stop line or off to the side is not shown as an alert.
    ctx.trafficLight?.takeIf { it.state == LightState.RED || it.state == LightState.YELLOW }?.takeIf { l ->
        val o = world?.objects?.get(l.trackId)
        val d = l.distanceMeters
        d != null && d in 15.0..60.0 && o?.rawLightState == l.state &&
            (o.lightConfidence ?: 1.0) >= 0.6 && kotlin.math.abs(o.lateralMeters ?: 0.0) <= 6.0
    }?.let { l ->
        val name = if (l.state == LightState.RED) "Red light" else "Yellow light"
        return (l.distanceMeters?.let { "$name: ${it.roundToInt()} m" } ?: name) to false
    }
    return null
}

@Composable
private fun StatusChip(status: StatusUi, debug: Boolean, modifier: Modifier = Modifier) {
    val color = when (status.level) {
        StatusUi.Level.OK -> Mint
        StatusUi.Level.WARN -> Amber
        StatusUi.Level.ERROR -> Alert
    }
    Column(
        modifier = modifier
            .background(Ink, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 420.dp),
    ) {
        Text(text = status.line1 + if (debug) " · DEBUG" else "", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = status.line2 ?: "tap: settings · hold: debug",
            color = if (status.line2 != null) Amber else Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun DebugPanel(lines: List<String>) {
    Column(
        Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .widthIn(max = 520.dp),
    ) {
        lines.forEach { Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(Color(0xCC3A2A08), RoundedCornerShape(12.dp))
            .border(1.dp, Amber.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AlertPill(text: String, critical: Boolean, modifier: Modifier = Modifier) {
    val color = if (critical) Alert else Amber
    Text(
        text = text,
        color = Color.White,
        fontSize = if (critical) 22.sp else 18.sp,
        fontWeight = if (critical) FontWeight.Bold else FontWeight.SemiBold,
        modifier = modifier
            .background(color.copy(alpha = if (critical) 0.85f else 0.55f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Next maneuver as phase1 describes it (DEMO: the placeholder route, labelled). */
@Composable
private fun ManeuverCard(route: RouteGuide, distanceNow: Double?, modifier: Modifier = Modifier) {
    val dim = route.stale || route.offRoute
    Column(
        modifier = modifier
            .border(1.dp, (if (dim) Amber else Mint).copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.46f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = if (route.offRoute) "OFF ROUTE" else route.headline,
            color = if (dim) Amber else Mint,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        // "CONTINUE" has no maneuver point to count down to (phase1 reports 0 m at the start of a leg).
        distanceNow?.takeIf { route.maneuver != Maneuver.FOLLOW_ROAD }?.let {
            Text(text = RouteGuide.formatDistance(it), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
        }
        route.roadName?.takeIf { it.length <= 40 }?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
        val eta = route.etaSeconds?.let { "ETA ${RouteGuide.formatEta(it)}" }
        val remaining = route.remainingMeters?.let { RouteGuide.formatDistance(it) + " left" }
        listOfNotNull(remaining, eta).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        // Where phase1 routes to (packet destination.label: the typed destination or the picked place's label).
        route.destination?.takeIf { route.provider != "demo" }?.let {
            Text(
                text = "to $it",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
        when {
            route.provider == "demo" -> Text("DEMO ROUTE", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            route.stale -> Text("ROUTE HELD · NO UPDATES", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Heading-up route map (bottom right): phase1's route polyline around the car's matched position. Only the route line,
 * no map tiles, so no Maps SDK key is needed in the app.
 */
@Composable
private fun RouteMapCard(route: RouteGuide, modifier: Modifier = Modifier) {
    val car = route.carLocation ?: return
    val points = remember(route.polyline) { RouteMap.decode(route.polyline.orEmpty()) }
    if (points.size < 2) return
    // GPS course is only meaningful while moving; standing still (or indoors) the map turns with the route instead.
    val moving = (route.speedMps ?: 0.0) >= 2.0
    val heading = (if (moving) route.headingDegrees?.takeIf { it > 0.0 } else null)
        ?: RouteMap.routeHeadingNear(points, car) ?: 0.0
    val projected = RouteMap.project(points, car, heading)
    val destination = projected.last()
    Box(
        modifier
            .padding(bottom = 4.dp)
            .size(width = 240.dp, height = 170.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .border(1.dp, Mint.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val metresPerPx = RANGE_M / (size.height * 0.75f)
            val originX = size.width / 2f
            val originY = size.height * 0.78f
            fun at(p: MapPoint) = Offset(originX + (p.right / metresPerPx).toFloat(), originY - (p.up / metresPerPx).toFloat())
            clipRect {
                val path = Path().apply {
                    projected.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                }
                drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Mint, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(Color.White, radius = 5.dp.toPx(), center = at(destination))
                drawCircle(Mint, radius = 3.dp.toPx(), center = at(destination))
            }
            // The car: a chevron pointing up (heading-up map).
            val c = Offset(originX, originY)
            val r = 9.dp.toPx()
            val carShape = Path().apply {
                moveTo(c.x, c.y - r); lineTo(c.x + r * 0.75f, c.y + r * 0.7f); lineTo(c.x, c.y + r * 0.3f); lineTo(c.x - r * 0.75f, c.y + r * 0.7f); close()
            }
            drawPath(carShape, Color.White)
        }
        Text(
            text = "${RANGE_M.toInt()} m · ${route.provider ?: "route"}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private const val RANGE_M = 300.0

@Composable
private fun SettingsDialog(
    initial: AppSettings,
    onDismiss: () -> Unit,
    onApply: (AppSettings) -> Unit,
    onSimToggle: (() -> Unit)?,
    /** LIVE session: close the dialog and open the place search (null otherwise). */
    onWhereTo: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var url by remember(initial) { mutableStateOf(initial.serverUrl) }
    var video by remember(initial) { mutableStateOf(initial.simVideoId) }
    var destination by remember(initial) { mutableStateOf(initial.destination) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spatial Copilot") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceMode.entries.forEach { m ->
                        FilterChip(selected = draft.mode == m, onClick = { draft = draft.copy(mode = m) }, label = { Text(m.name) })
                    }
                }
                Text(
                    when (draft.mode) {
                        SourceMode.LIVE -> "Tablet camera to the laptop. Navigation follows this tablet's GPS."
                        SourceMode.SIM -> "Plays the clip on the tablet; the laptop analyses the same clip ahead of playback."
                        SourceMode.DEMO -> "No laptop: scripted scene and placeholder route (Exit 56)."
                    },
                    fontSize = 12.sp,
                )
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Laptop WebSocket URL") })
                Text("USB: adb reverse tcp:8765 tcp:8765, then 127.0.0.1. Wi-Fi: the laptop's LAN address.", fontSize = 12.sp)
                if (draft.mode == SourceMode.LIVE) {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it.take(200) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Destination (live navigation)") },
                        placeholder = { Text("e.g. Piedmont Park, Atlanta") },
                    )
                    Text("The laptop routes there from this tablet's GPS (Google Maps with a key in spatial/.env, else the mock route). Or tap Where to? to search.", fontSize = 12.sp)
                    onWhereTo?.let { TextButton(onClick = it) { Text("Where to?") } }
                }
                if (draft.mode == SourceMode.SIM) {
                    OutlinedTextField(value = video, onValueChange = { video = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Sim clip id") })
                    onSimToggle?.let { TextButton(onClick = it) { Text("Play / pause clip") } }
                }
                Toggle("Debug view (all boxes, lanes, fps, link)", draft.debug) { draft = draft.copy(debug = it) }
                Toggle("Voice", draft.voice) { draft = draft.copy(voice = it) }
                if (draft.mode == SourceMode.LIVE) {
                    Toggle("Camera films a monitor (demo set-up)", draft.cameraOnMonitor) { draft = draft.copy(cameraOnMonitor = it) }
                }
                Toggle("Hold TOO CLOSE at CLOSE while stopped (route speed)", draft.gateCriticalBySpeed) { draft = draft.copy(gateCriticalBySpeed = it) }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val u = url.trim()
                if (!AppSettings.isValidUrl(u)) { error = "Use a ws:// or wss:// URL"; return@TextButton }
                val d = destination.trim()
                // An edited destination is typed text (geocoded on the laptop); an unchanged one keeps a picked place's location.
                val picked = d == initial.destination
                onApply(
                    draft.copy(
                        serverUrl = u, simVideoId = video.trim().ifEmpty { AppSettings.DEFAULT_VIDEO }, destination = d,
                        destinationLocation = initial.destinationLocation.takeIf { picked },
                        destinationPlaceId = initial.destinationPlaceId.takeIf { picked },
                    ),
                )
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = value, onCheckedChange = onChange)
        Text(label, fontSize = 13.sp)
    }
}
