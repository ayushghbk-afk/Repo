package com.lenix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lenix.data.LenixSettings

/**
 * App settings screen.
 *
 * Every toggle is backed by the persisted [LenixSettings] handed in from
 * [com.lenix.ui.HomeViewModel] (filesDir/settings.json) — the screen itself keeps
 * no local state, so values survive navigation, process death, and app restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LenixSettings,
    onUpdate: ((LenixSettings) -> LenixSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingRow(
                icon = Icons.Default.Storage,
                title = "Storage care",
                subtitle = "Check free space before every install",
                value = settings.smartStorage,
                onValueChange = { enabled ->
                    onUpdate { it.copy(smartStorage = enabled) }
                },
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Default.Autorenew,
                title = "Foreground service",
                subtitle = "Keep the runtime alive in the background",
                value = settings.allowBackground,
                onValueChange = { enabled ->
                    onUpdate { it.copy(allowBackground = enabled) }
                },
            )
            HorizontalDivider()
            SettingRow(
                icon = Icons.Default.DesktopWindows,
                title = "Auto-start desktop",
                subtitle = "Open Desktop screen after START",
                value = settings.autoStartDesktop,
                onValueChange = { enabled ->
                    onUpdate { it.copy(autoStartDesktop = enabled) }
                },
            )

            Spacer(Modifier.padding(4.dp))
            Text(
                text = "Settings are saved automatically on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Architecture: arm64-v8a only (v0.1)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Distro scope: Debian only (v0.1)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}
