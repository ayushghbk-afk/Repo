package com.lenix.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lenix.clipboard.ClipboardBridge
import com.lenix.vm.launch.GuestRuntime
import com.lenix.vnc.RfbClient
import com.lenix.vnc.input.InputMapper
import com.lenix.vnc.input.KeySym
import com.lenix.vnc.input.MouseButtons
import com.lenix.vnc.input.TouchMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in RFB viewer — improved using Stryker's proven VNC patterns.
 *
 * Stryker improvements ported:
 * - InputMapper for KeySym / MouseButtons (from Stryker's VncKeyListener / Video)
 * - TouchMapper for tap/longPress/doubleTap/drag/scroll
 * - ClipboardBridge for Android ↔ Linux clipboard sync
 * - Better error diagnostics with guest log hints
 * - Robust framebuffer handling (Raw encoding, byte order, pixel format)
 * - Status bar with server info (version, name, size)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    guestRuntime: GuestRuntime,
    instanceId: String,
    vncPort: Int?,
    running: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Waiting for Openbox / Xvnc …") }
    var connected by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var serverSize by remember { mutableStateOf<IntSize?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var clipboardText by remember { mutableStateOf<String?>(null) }

    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var shiftDown by remember { mutableStateOf(false) }

    var rfbClient by remember { mutableStateOf<RfbClient?>(null) }

    val clipboardBridge = remember { ClipboardBridge.get(context) }

    // Extra keys — Stryker's ExtraKeysView inspiration, using KeySym constants
    val extraKeys = remember {
        listOf(
            "Esc" to KeySym.ESCAPE,
            "Tab" to KeySym.TAB,
            "Ctrl" to -1,
            "Alt" to -2,
            "Shift" to -3,
            "Super" to KeySym.SUPER_L,
            "F1" to KeySym.F1, "F2" to KeySym.F2, "F3" to KeySym.F3, "F4" to KeySym.F4,
            "Del" to KeySym.DELETE, "Bksp" to KeySym.BACKSPACE, "Enter" to KeySym.RETURN,
        )
    }

    LaunchedEffect(vncPort, running, instanceId) {
        connected = false
        rfbClient = null
        frame = null
        if (!running || vncPort == null) {
            status = if (!running) {
                "Start the instance with Auto-start desktop, or START then open Desktop."
            } else {
                "No VNC port — this session is a shell. Enable Auto-start desktop in Settings."
            }
            return@LaunchedEffect
        }

        val guestSession = guestRuntime.session(instanceId)
        if (guestSession == null || !guestSession.isAlive()) {
            status = "Guest session is not running. Press START on Home to launch it."
            return@LaunchedEffect
        }

        var lastError = "Connecting to 127.0.0.1:$vncPort"
        status = lastError
        var session: RfbClient? = null
        try {
            var attempt = 0
            while (session == null && attempt < 30 && isActive) {
                val currentSession = guestRuntime.session(instanceId)
                if (currentSession == null || !currentSession.isAlive()) {
                    status = "Guest session died — press START on Home to launch it again.\nCheck /tmp/xvnc.log /tmp/session.log in guest."
                    return@LaunchedEffect
                }

                val client = RfbClient(
                    port = vncPort,
                    onClipboardText = { text ->
                        clipboardText = text
                        try {
                            clipboardBridge.setText(text)
                        } catch (_: Exception) {
                        }
                    },
                )
                try {
                    withContext(Dispatchers.IO) { client.handshake() }
                    session = client
                    rfbClient = client
                    connected = true
                    serverSize = IntSize(client.server.width, client.server.height)
                    status = "Openbox • ${client.server.width}×${client.server.height} • 127.0.0.1:$vncPort (RFB 3.8) • ${client.server.name}"
                } catch (e: Exception) {
                    client.close()
                    attempt++
                    lastError = e.message ?: "VNC connection failed"
                    status = "Retry $attempt/30 — $lastError"
                    if (attempt > 25) {
                        status += "\nCheck guest logs: cat /tmp/xvnc.log /tmp/xvfb.log /tmp/x11vnc.log /tmp/session.log"
                    }
                    delay(500)
                }
            }
            if (session == null) {
                status = "Failed after 30 attempts: $lastError\n\nDiagnostics:\n- Guest alive: ${guestRuntime.session(instanceId)?.isAlive()}\n- Port: $vncPort\n- Check: cat /tmp/xvnc.log /tmp/session.log inside guest\n- Ensure rootfs has tigervnc-standalone-server or xvfb+x11vnc"
            } else {
                val activeSession = session
                while (isActive) {
                    val updated = try {
                        withContext(Dispatchers.IO) { activeSession.readUpdate() }
                    } catch (e: Exception) {
                        status = "VNC connection lost: ${e.message}\nRetrying..."
                        break
                    }
                    if (updated) {
                        val pixels = activeSession.snapshotPixels()
                        val bitmap = Bitmap.createBitmap(
                            pixels,
                            activeSession.server.width,
                            activeSession.server.height,
                            Bitmap.Config.ARGB_8888,
                        )
                        frame = bitmap
                        serverSize = IntSize(activeSession.server.width, activeSession.server.height)
                    }
                    withContext(Dispatchers.IO) { activeSession.requestUpdate(incremental = true) }
                    if (!updated) delay(50)
                }
            }
        } finally {
            session?.close()
            rfbClient = null
        }
    }

    fun viewToFb(x: Float, y: Float): Pair<Int, Int>? {
        val srv = serverSize ?: return null
        if (viewSize.width == 0 || viewSize.height == 0) return null
        val scaleX = srv.width.toFloat() / viewSize.width
        val scaleY = srv.height.toFloat() / viewSize.height
        val s = maxOf(scaleX, scaleY) / scale
        val fbX = (x * s).toInt()
        val fbY = (y * s).toInt()
        return fbX to fbY
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Desktop")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val clip = clipboardBridge.getText()
                            if (!clip.isNullOrEmpty()) {
                                rfbClient?.sendClipboardText(clip)
                                Toast.makeText(context, "Pasted to Linux: ${clip.take(50)}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Android clipboard empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste to Linux")
                    }
                    IconButton(onClick = {
                        clipboardText?.let {
                            clipboardBridge.setText(it)
                            Toast.makeText(context, "Copied from Linux", Toast.LENGTH_SHORT).show()
                        } ?: Toast.makeText(context, "No Linux clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy from Linux")
                    }
                    IconButton(onClick = { scale = (scale * 1.25f).coerceAtMost(4f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = { scale = (scale / 1.25f).coerceAtLeast(0.25f) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1A1D23)),
        ) {
            // Toolbar for special keys — using InputMapper KeySym
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242A36))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                extraKeys.forEach { (label, keysym) ->
                    val isToggle = keysym < 0
                    val isActive = when (keysym) {
                        -1 -> ctrlDown
                        -2 -> altDown
                        -3 -> shiftDown
                        else -> false
                    }
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                when (keysym) {
                                    -1 -> {
                                        ctrlDown = !ctrlDown
                                        rfbClient?.key(ctrlDown, KeySym.CONTROL_L)
                                    }
                                    -2 -> {
                                        altDown = !altDown
                                        rfbClient?.key(altDown, KeySym.ALT_L)
                                    }
                                    -3 -> {
                                        shiftDown = !shiftDown
                                        rfbClient?.key(shiftDown, KeySym.SHIFT_L)
                                    }
                                    else -> {
                                        rfbClient?.key(true, keysym)
                                        delay(50)
                                        rfbClient?.key(false, keysym)
                                    }
                                }
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0F1117))
                    .onSizeChanged { viewSize = it }
                    .pointerInput(serverSize, scale) {
                        detectTapGestures(
                            onTap = { offset ->
                                val fb = viewToFb(offset.x, offset.y) ?: return@detectTapGestures
                                scope.launch(Dispatchers.IO) {
                                    val tap = TouchMapper.tap(fb.first, fb.second)
                                    for (ev in tap) {
                                        rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                        delay(20)
                                    }
                                }
                            },
                            onLongPress = { offset ->
                                val fb = viewToFb(offset.x, offset.y) ?: return@detectTapGestures
                                scope.launch(Dispatchers.IO) {
                                    val ev = TouchMapper.longPress(fb.first, fb.second)
                                    rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                    delay(50)
                                    rfbClient?.pointer(0, ev.x, ev.y)
                                }
                            },
                            onDoubleTap = { offset ->
                                val fb = viewToFb(offset.x, offset.y) ?: return@detectTapGestures
                                scope.launch(Dispatchers.IO) {
                                    val taps = TouchMapper.doubleTap(fb.first, fb.second)
                                    for (ev in taps) {
                                        rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                        delay(30)
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(serverSize, scale) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val fb = viewToFb(offset.x, offset.y) ?: return@detectDragGestures
                                scope.launch(Dispatchers.IO) {
                                    val ev = TouchMapper.dragStart(fb.first, fb.second)
                                    rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                }
                            },
                            onDragEnd = {
                                scope.launch(Dispatchers.IO) {
                                    val ev = TouchMapper.dragEnd()
                                    rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                }
                            },
                            onDrag = { change, _ ->
                                val fb = viewToFb(change.position.x, change.position.y) ?: return@detectDragGestures
                                scope.launch(Dispatchers.IO) {
                                    val ev = TouchMapper.dragMove(fb.first, fb.second)
                                    rfbClient?.pointer(ev.buttonMask, ev.x, ev.y)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                frame?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Linux desktop",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } ?: run {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF242A36), RoundedCornerShape(16.dp))
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = if (connected) Color(0xFF00C853) else Color(0xFFB0B4BC),
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(
                            text = if (connected) "Openbox desktop connected" else "Openbox desktop",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = status,
                            color = Color(0xFFB0B4BC),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        clipboardText?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Clipboard: ${it.take(100)}",
                                color = Color(0xFF7A8699),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onBack) {
                                Text("Return to Home")
                            }
                            if (connected) {
                                Button(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        rfbClient?.requestUpdate(incremental = false)
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }
            }

            // Status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242A36))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = status.lines().firstOrNull() ?: "",
                    color = Color(0xFFB0B4BC),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (serverSize != null) {
                    Text(
                        text = "${serverSize!!.width}×${serverSize!!.height} @ ${\"%.2f\".format(scale)}x",
                        color = Color(0xFF7A8699),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
