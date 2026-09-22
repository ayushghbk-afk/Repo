package com.lenix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lenix.ui.HomeUiState
import com.lenix.vm.VmError
import com.lenix.vm.VmState

/**
 * Lenix home screen. Shows the Linux environment manager with an explicit
 * INSTALL -> READY -> START flow and PRoot engine preinstall / autofix support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onAutofixEngine: () -> Unit = {},
    onOpenInstance: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenDesktop: () -> Unit = {},
) {
    val instance = state.selectedInstance

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("LENIX", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Linux Environment",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.instances.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No instances yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Create a Linux instance to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenInstance, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Storage, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("OPEN INSTANCE MANAGER")
                        }
                    }
                }
            } else {
                if (!state.isEngineAvailable) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "PRoot engine unavailable",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "The ${state.selectedInstance.architecture} engine payload is " +
                                    "missing or invalid, so Linux cannot start. Tap AUTOFIX ENGINE " +
                                    "to re-check the signed APK payload.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onAutofixEngine,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("AUTOFIX ENGINE")
                            }
                        }
                    }
                }

                VmCard(
                    state = state,
                    instance = instance,
                    onInstall = onInstall,
                    onCancelInstall = onCancelInstall,
                    onStart = onStart,
                    onStop = onStop,
                    onOpenInstance = onOpenInstance,
                    onReset = onReset,
                    onAutofixEngine = onAutofixEngine,
                    onOpenTerminal = onOpenTerminal,
                    onOpenDesktop = onOpenDesktop,
                )

                OutlinedButton(
                    onClick = onOpenInstance,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("INSTANCE MANAGER (${state.instances.size})")
                }
            }

            state.message?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Lenix v0.1.0 • ARM64 only",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun VmCard(
    state: HomeUiState,
    instance: com.lenix.vm.VmInstance,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInstance: () -> Unit,
    onReset: () -> Unit,
    onAutofixEngine: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${instance.distro} ${instance.version} (${instance.codename}) • ${instance.architecture}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(instance.state)
            }

            when (instance.state) {
                VmState.NOT_INSTALLED -> {
                    Text(
                        text = "Status: Not Installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("INSTALL")
                    }
                }

                VmState.DOWNLOADING, VmState.VERIFYING, VmState.EXTRACTING, VmState.INSTALLING -> {
                    val progress = state.installProgress
                    Text(
                        text = progress.message.ifBlank { "Installing Debian RootFS …" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = onCancelInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("CANCEL INSTALL")
                    }
                }

                VmState.READY -> {
                    Text(
                        text = "Status: Installed — ready to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("START")
                    }
                }

                VmState.STARTING, VmState.RUNNING -> {
                    Text(
                        text = if (instance.state == VmState.STARTING) {
                            "Starting Linux environment …"
                        } else {
                            "Running. Stop it when you are done."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenTerminal,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = instance.state == VmState.RUNNING,
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("TERMINAL")
                    }
                    Button(
                        onClick = onOpenDesktop,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = instance.state == VmState.RUNNING,
                    ) {
                        Text("DESKTOP (OPENBOX)")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("STOP")
                    }
                }

                VmState.STOPPING -> {
                    Text("Stopping Linux environment …", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                VmState.ERROR -> {
                    val error = instance.lastError ?: VmError.UNKNOWN
                    Text(
                        text = when (error) {
                            VmError.INSTALL_INTERRUPTED ->
                                "Install interrupted — verified layers stay cached and " +
                                    "the download resumes where it stopped."
                            VmError.SIGNATURE_FAILED ->
                                "This RootFS manifest is not signed by a key this build " +
                                    "trusts, so nothing was downloaded. Re-pin the layer and " +
                                    "sign it (scripts/sign-rootfs-manifest.sh)."
                            VmError.UNSUPPORTED_COMPRESSION ->
                                "This RootFS layer is compressed in a format the app cannot " +
                                    "read yet — zstd needs libpvmnative. xz and gz layers work today."
                            VmError.NATIVE_ENGINE_FAILED ->
                                state.message?.takeIf { it.isNotBlank() }
                                    ?: ("The PRoot engine payload is missing from this build. " +
                                        "Run scripts/fetch-engine.sh, rebuild, then tap AUTOFIX " +
                                        "ENGINE to re-check.")
                            VmError.VNC_CONNECTION_FAILED ->
                                "The built-in viewer could not reach Xvnc on loopback."
                            VmError.CHECKSUM_FAILED ->
                                "A downloaded layer did not hash to the digest the signed " +
                                    "manifest pins, so it was discarded. Retry the install."
                            VmError.INSUFFICIENT_STORAGE ->
                                "Not enough free space to extract the RootFS. Free some " +
                                    "space, then retry — the download is already cached."
                            VmError.ROOTFS_EXTRACTION_FAILED ->
                                state.message?.takeIf { it.isNotBlank() }
                                    ?: ("The RootFS is empty or corrupted. Reset the instance " +
                                        "and reinstall from the Home screen.")
                            else -> "Error: ${error.name}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (error == VmError.INSTALL_INTERRUPTED) {
                        val progress = state.installProgress
                        if (progress.message.isNotBlank()) {
                            Text(
                                text = progress.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (progress.fraction > 0f) {
                            LinearProgressIndicator(
                                progress = { progress.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Button(
                            onClick = onInstall,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("RESUME INSTALL")
                        }
                    }
                    if (error == VmError.NATIVE_ENGINE_FAILED) {
                        Button(
                            onClick = onAutofixEngine,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("AUTOFIX ENGINE")
                        }
                    }
                    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("RESET")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: VmState) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = statusColor(state).copy(alpha = 0.15f),
    ) {
        Text(
            text = state.name.replaceFirstChar { it.uppercase() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(state),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun statusColor(state: VmState): Color = when (state) {
    VmState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
    VmState.DOWNLOADING, VmState.VERIFYING, VmState.EXTRACTING, VmState.INSTALLING,
    VmState.STARTING, VmState.STOPPING,
    -> MaterialTheme.colorScheme.primary
    VmState.READY, VmState.RUNNING -> MaterialTheme.colorScheme.primary
    VmState.ERROR -> MaterialTheme.colorScheme.error
}
