package com.drivingassist.spatialcopilot.perception

import android.os.Build
import android.os.SystemClock
import com.drivingassist.spatialcopilot.model.UplinkHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

enum class LinkState {
    DEMO,
    CONNECTING,
    LIVE,
    RECONNECTING,
    TAKEN_OVER,
}

/**
 * One WebSocket to the perception laptop.
 *
 * Text frames are JSON (`client.hello` up, perception and navigation messages down,
 * or a ready-made `spatial.instruction`). Binary frames are `SDC1` + JPEG.
 * At most [maxInFlight] camera frames are outstanding; extras are dropped.
 */
class PerceptionClient(
    private val scope: CoroutineScope,
    private val onEvent: (ServerEvent) -> Unit,
    private val onStatus: (LinkState, String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var closedByUser = false
    private var socketOpen = false
    private var watcher = false
    private var inFlight = 0
    private var maxInFlight = 2
    private var frameId = 0
    private var url = ""
    private var backoffMs = 1_000L
    private var uprightW = 960
    private var uprightH = 540
    private var helloW = -1
    private var helloH = -1
    private var reconnectJob: Job? = null
    private val inflightSince = ArrayDeque<Long>()

    fun connect(url: String) {
        try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            onStatus(LinkState.RECONNECTING, "URL must start with ws:// or wss://")
            return
        }
        val previous = synchronized(lock) {
            closedByUser = false
            this.url = url
            backoffMs = 1_000L
            socketOpen = false
            inFlight = 0
            inflightSince.clear()
            webSocket.also { webSocket = null }
        }
        reconnectJob?.cancel()
        previous?.cancel()
        openNow()
    }

    fun close() {
        val socket = synchronized(lock) {
            closedByUser = true
            socketOpen = false
            inFlight = 0
            inflightSince.clear()
            webSocket.also { webSocket = null }
        }
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        http.dispatcher.executorService.shutdown()
    }

    fun canUplink(): Boolean = synchronized(lock) {
        expireCreditsLocked()
        socketOpen && !watcher && inFlight < maxInFlight
    }

    fun noteUprightSize(bufferWidth: Int, bufferHeight: Int, rotation: Int) {
        val swap = rotation == 90 || rotation == 270
        val width = if (swap) bufferHeight else bufferWidth
        val height = if (swap) bufferWidth else bufferHeight
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == uprightW && height == uprightH) return
            uprightW = width
            uprightH = height
        }
        sendHello()
    }

    fun offerFrame(jpeg: ByteArray, rotation: Int, captureTimeNs: Long): Boolean {
        val socket: WebSocket
        val id: Int
        synchronized(lock) {
            expireCreditsLocked()
            if (!socketOpen || watcher || inFlight >= maxInFlight) return false
            inFlight += 1
            inflightSince.addLast(SystemClock.elapsedRealtime())
            id = frameId
            frameId = (frameId + 1) and 0x7fffffff
            socket = webSocket ?: run {
                inFlight -= 1
                if (inflightSince.isNotEmpty()) inflightSince.removeLast()
                return false
            }
        }
        val payload = UplinkHeader.wrap(id.toLong(), captureTimeNs, rotation, jpeg)
        val sent = socket.send(payload.toByteString())
        if (!sent) releaseCredit()
        return sent
    }

    private fun openNow() {
        val target = synchronized(lock) { url }
        if (target.isBlank()) return
        onStatus(LinkState.CONNECTING, "")
        val request = Request.Builder().url(target).build()
        val socket = http.newWebSocket(request, listener)
        synchronized(lock) {
            if (closedByUser || url != target) {
                socket.cancel()
                return
            }
            webSocket = socket
        }
    }

    private fun scheduleReconnect(detail: String) {
        if (closedByUser) return
        val wait = synchronized(lock) {
            val current = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(8_000L)
            current
        }
        onStatus(LinkState.RECONNECTING, detail)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (!closedByUser) openNow()
        }
    }

    private fun releaseCredit() {
        synchronized(lock) {
            if (inFlight > 0) inFlight -= 1
            if (inflightSince.isNotEmpty()) inflightSince.removeFirst()
        }
    }

    private fun expireCreditsLocked() {
        val now = SystemClock.elapsedRealtime()
        while (inflightSince.isNotEmpty() && now - inflightSince.first() > CREDIT_TIMEOUT_MS) {
            inflightSince.removeFirst()
            if (inFlight > 0) inFlight -= 1
        }
    }

    private fun sendHello() {
        val socket: WebSocket
        val width: Int
        val height: Int
        synchronized(lock) {
            if (!socketOpen) return
            if (helloW == uprightW && helloH == uprightH) return
            width = uprightW
            height = uprightH
            helloW = width
            helloH = height
            socket = webSocket ?: return
        }
        socket.send(helloJson(width, height))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (this@PerceptionClient.webSocket !== webSocket) return
                socketOpen = true
                inFlight = 0
                inflightSince.clear()
                backoffMs = 1_000L
                helloW = -1
                helloH = -1
            }
            sendHello()
            onStatus(LinkState.LIVE, "")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = ProtocolDecoder.decode(text)
            when (event) {
                is ServerEvent.Hello -> {
                    val takenOver = synchronized(lock) {
                        maxInFlight = event.maxInFlight.coerceAtLeast(1)
                        watcher = event.role == "watcher"
                        watcher
                    }
                    onStatus(
                        if (takenOver) LinkState.TAKEN_OVER else LinkState.LIVE,
                        if (takenOver) "Another client owns this session" else "",
                    )
                }
                is ServerEvent.Frame,
                is ServerEvent.Skip,
                is ServerEvent.Direct,
                -> releaseCredit()
                is ServerEvent.Failure -> {
                    val open = synchronized(lock) { socketOpen }
                    if (open) {
                        val taken = synchronized(lock) { watcher }
                        onStatus(
                            if (taken) LinkState.TAKEN_OVER else LinkState.LIVE,
                            event.message,
                        )
                    }
                }
                else -> Unit
            }
            onEvent(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            drop(webSocket, reason.ifBlank { "disconnected" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            drop(webSocket, t.message ?: "connection failed")
        }
    }

    private fun drop(webSocket: WebSocket, detail: String) {
        val mine = synchronized(lock) {
            if (this.webSocket !== webSocket) return
            socketOpen = false
            inFlight = 0
            inflightSince.clear()
            this.webSocket = null
            true
        }
        if (!mine || closedByUser) return
        scheduleReconnect(detail)
    }

    private fun helloJson(width: Int, height: Int): String = buildJsonObject {
        put("type", "client.hello")
        put("protocolVersion", 2)
        put("clientId", "spatial-copilot")
        put("device", buildJsonObject {
            put("manufacturer", Build.MANUFACTURER ?: "unknown")
            put("model", Build.MODEL ?: "unknown")
            put("osVersion", Build.VERSION.RELEASE ?: "")
        })
        put("mode", "live")
        put("camera", buildJsonObject {
            put("imageWidth", width)
            put("imageHeight", height)
            put("focalPx", width * 0.9)
            put("principalPoint", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(width / 2.0))
                add(kotlinx.serialization.json.JsonPrimitive(height / 2.0))
            })
            put("mountHeightMeters", 1.25)
            put("pitchDegrees", JsonNull)
            put("lensFacing", "back")
            put("stabilization", false)
        })
        put("sim", JsonNull)
    }.toString()

    private companion object {
        const val CREDIT_TIMEOUT_MS = 1_000L
    }
}
