package org.ledfxmobile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ledfxmobile.app.effects.Effects
import org.ledfxmobile.app.effects.Palettes
import org.ledfxmobile.app.engine.LightEngine
import org.ledfxmobile.app.model.DeviceConfig
import org.ledfxmobile.app.model.DeviceKind
import org.ledfxmobile.app.net.WledApi
import org.ledfxmobile.app.net.WledDiscovery

@Composable
fun DevicesScreen(
    padding: PaddingValues,
    running: Boolean,
    onStart: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenWled: (String) -> Unit,
) {
    val devices by LightEngine.devices.collectAsStateWithLifecycle()
    val previews by LightEngine.previews.collectAsStateWithLifecycle()
    val master by LightEngine.masterBrightness.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!running) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lights are off", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Start the engine to react to music.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onStart) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Text(" Start")
                        }
                    }
                }
            }
        }
        item(key = "master") { MasterBrightnessCard(master) }
        if (devices.isEmpty()) {
            item {
                Text(
                    "No devices yet. Tap \"Add device\" to find WLED lights on your Wi-Fi, " +
                        "or add a virtual strip to preview effects on the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                pixels = previews[device.id],
                onClick = { onOpen(device.id) },
                onToggle = { on -> LightEngine.updateDevice(device.id) { it.copy(enabled = on) } },
                onOpenWled = { onOpenWled(device.id) },
            )
        }
    }
}

@Composable
private fun MasterBrightnessCard(value: Float) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BrightnessLow, "All lights dimmer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = local,
                onValueChange = { local = it; LightEngine.setMasterBrightness(it) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Icon(Icons.Rounded.BrightnessHigh, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "${(local * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(48.dp).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceConfig,
    pixels: IntArray?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenWled: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (device.kind == DeviceKind.WLED) Icons.Rounded.Router else Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (device.kind == DeviceKind.WLED) "WLED · ${device.host} · ${device.ledCount} LEDs"
                        else "On-screen preview · ${device.ledCount} LEDs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (device.kind == DeviceKind.WLED) {
                    IconButton(onClick = onOpenWled) { Icon(Icons.Rounded.Language, "Open WLED") }
                }
                Switch(checked = device.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(14.dp))
            LedStrip(pixels = if (device.enabled) pixels else null, ledCount = device.ledCount)
            var bright by remember(device.settings.brightness) { mutableFloatStateOf(device.settings.brightness) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BrightnessMedium, "Brightness", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = bright,
                    onValueChange = { v -> bright = v; LightEngine.updateSettings(device.id) { it.copy(brightness = v) } },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("${(bright * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onClick, label = { Text(Effects.info(device.settings.effectId).name) })
                AssistChip(onClick = onClick, label = {
                    Text(if (device.settings.customGradient != null) "Preset colours" else Palettes.get(device.settings.paletteId).name)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val discovery = remember { WledDiscovery(context) }
    val found by discovery.found.collectAsStateWithLifecycle()
    val scanning by discovery.scanning.collectAsStateWithLifecycle()
    val devices by LightEngine.devices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var manualHost by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text("Add a device", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("WLED on your Wi-Fi", style = MaterialTheme.typography.titleSmall)
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (found.isEmpty()) {
                Text(
                    if (scanning) "Searching…" else "None found yet. Make sure the phone and the lights are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            found.forEach { info ->
                val added = devices.any { it.host == info.host }
                ListItem(
                    headlineContent = { Text(info.name) },
                    supportingContent = { Text("${info.host} · ${info.ledCount} LEDs") },
                    leadingContent = { Icon(Icons.Rounded.Router, null) },
                    trailingContent = {
                        TextButton(enabled = !added, onClick = {
                            LightEngine.addDevice(
                                DeviceConfig(name = info.name, kind = DeviceKind.WLED, host = info.host, ledCount = info.ledCount)
                            )
                            onDismiss()
                        }) { Text(if (added) "Added" else "Add") }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Add by IP address", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it.trim(); manualError = null },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    isError = manualError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = manualHost.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val info = WledApi.fetchInfo(manualHost)
                            checking = false
                            if (info == null) {
                                manualError = "No WLED device answered at that address"
                            } else {
                                LightEngine.addDevice(
                                    DeviceConfig(name = info.name, kind = DeviceKind.WLED, host = info.host, ledCount = info.ledCount)
                                )
                                onDismiss()
                            }
                        }
                    },
                ) { Text(if (checking) "…" else "Connect") }
            }
            manualError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedButton(
                onClick = {
                    LightEngine.addDevice(DeviceConfig(name = "Virtual strip", kind = DeviceKind.VIRTUAL, ledCount = 60))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.PhoneAndroid, null)
                Text("  Add a virtual strip (preview on phone)")
            }
        }
    }
}
