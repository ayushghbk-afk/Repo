package com.lenix.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.lenix.vm.launch.GuestRuntime
import com.lenix.vnc.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Built-in RFB viewer for the Openbox session (Phase 7 / ADR-003).

  * Handshake and Raw framebuffer reads run off the UI thread; decoded opaque
  * ARGB pixels are displayed as a Compose bitmap and input remains on the RFB
  * socket.
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
    var status by remember { mutableStateOf("Waiting for Openbox / Xvnc …") }
    var connected by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(vncPort, running, instanceId) {
        connected = false
        if (!running || vncPort == null) {
            status = if (!running) {
                "Start the instance with Auto-start desktop, or START then open Desktop."
            } else {
                "No VNC port — this session is a shell. Enable Auto-start desktop in Settings."
            }
            return@LaunchedEffect
        }

        // Verify the guest session is still alive before attempting connection
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
            while (session == null && attempt < 20) {
                // Re-check guest is alive during each retry — the session may have
                // died while we were waiting for Xvnc to start.
                val currentSession = guestRuntime.session(instanceId)
                if (currentSession == null || !currentSession.isAlive()) {
                    status = "Guest session died — press START on Home to launch it again."
                    return@LaunchedEffect
                }

                val client = RfbClient(port = vncPort)
                try {
                    withContext(Dispatchers.IO) { client.handshake() }
                    session = client
                    connected = true
                    status =
                        "Openbox • ${client.server.width}×${client.server.height} • 127.0.0.1:$vncPort (RFB 3.8)"
                } catch (e: Exception) {
                    // handshake() closes on failure; close again so a partial
                    // connect can never leak a socket per retry.
                    client.close()
                    attempt++
                    lastError = e.message ?: "VNC connection failed"
                    status = "Retry $attempt/20 — $lastError"
                    delay(500)
                }
            }
            if (session == null) {
                status = lastError
            } else {
                // Keep requesting updates and publish opaque ARGB pixels to Compose.
                // RFB has no server-push requirement; the request is the clock.
                val activeSession = session ?: return@LaunchedEffect
                while (true) {
                    withContext(Dispatchers.IO) { activeSession.readUpdate() }
                    val pixels = activeSession.snapshotPixels()
                    val bitmap = Bitmap.createBitmap(
                        pixels,
                        activeSession.server.width,
                        activeSession.server.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    frame = bitmap
                    withContext(Dispatchers.IO) { activeSession.requestUpdate(incremental = true) }
                }
            }
        } finally {
            session?.close()
        }
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
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1A1D23)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242A36), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                frame?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Linux desktop",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = Color(0xFF00C853),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(
                    text = if (connected) "Openbox desktop connected" else "Openbox desktop",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = status,
                    color = Color(0xFFB0B4BC),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onBack, modifier = Modifier.padding(top = 20.dp)) {
                    Text("Return to Home")
                }
            }
        }
    }
}
