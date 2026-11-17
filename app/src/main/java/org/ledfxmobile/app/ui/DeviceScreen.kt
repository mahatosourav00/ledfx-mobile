package org.ledfxmobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ledfxmobile.app.effects.EffectCategory
import org.ledfxmobile.app.effects.EffectInfo
import org.ledfxmobile.app.effects.Effects
import org.ledfxmobile.app.effects.Palettes
import org.ledfxmobile.app.effects.ParamValues
import org.ledfxmobile.app.effects.Preset
import org.ledfxmobile.app.engine.LightEngine
import org.ledfxmobile.app.model.DeviceKind
import org.ledfxmobile.app.net.WledApi
import kotlin.math.roundToInt

private val LAYOUT_KEYS = setOf("blur", "mirror", "flip")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(deviceId: String, onBack: () -> Unit, onOpenWled: (String) -> Unit) {
    val devices by LightEngine.devices.collectAsStateWithLifecycle()
    val previews by LightEngine.previews.collectAsStateWithLifecycle()
    val userPresets by LightEngine.userPresets.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == deviceId }
    LaunchedEffect(device == null) { if (device == null) onBack() }
    if (device == null) return

    val s = device.settings
    val info = Effects.info(s.effectId)
    val values = remember(info.id) { ParamValues(info.params) }.also { it.values = s.config(info.id) }

    var confirmDelete by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (device.kind == DeviceKind.WLED) {
                        IconButton(onClick = { onOpenWled(device.id) }) { Icon(Icons.Rounded.Language, "Open WLED") }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Remove") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp)
        ) {
            // ---- preview, on/off, brightness
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    LedStrip(
                        pixels = if (device.enabled) previews[device.id] else null,
                        ledCount = device.ledCount,
                        height = 34.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (device.kind == DeviceKind.WLED) "Send to lights" else "Show preview",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = device.enabled,
                            onCheckedChange = { on -> LightEngine.updateDevice(deviceId) { it.copy(enabled = on) } },
                        )
                    }
                    SettingSlider("Brightness", "${(s.brightness * 100).roundToInt()}%", s.brightness, 0f..1f) { v ->
                        LightEngine.updateSettings(deviceId) { it.copy(brightness = v) }
                    }
                }
            }

            // ---- effect
            SectionTitle("Effect")
            Card(
                onClick = { showPicker = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${info.category.title} · ${info.description}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Rounded.SwapHoriz, "Change effect")
                }
            }

            // ---- presets
            val builtIn = remember(info.id) { Preset.builtInFor(info.id) }
            val mine = userPresets.filter { it.effectId == info.id }
            SectionTitle("Presets")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(builtIn, key = { it.id }) { p ->
                    AssistChip(
                        onClick = { LightEngine.updateSettings(deviceId) { p.applyTo(it) } },
                        label = { Text(p.name.replaceFirstChar { it.uppercase() }) },
                        leadingIcon = p.gradient?.let { g -> { GradientDot(g) } },
                    )
                }
                items(mine, key = { it.id }) { p ->
                    InputChip(
                        selected = false,
                        onClick = { LightEngine.updateSettings(deviceId) { p.applyTo(it) } },
                        label = { Text(p.name) },
                        leadingIcon = p.gradient?.let { g -> { GradientDot(g) } },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.Close, "Delete preset",
                                Modifier.size(18.dp).clickable { LightEngine.deletePreset(p.id) },
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { showSave = true },
                        label = { Text("Save current") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (builtIn.isEmpty() && mine.isEmpty()) {
                Text(
                    "No presets for this effect yet. Tune it below and tap \"Save current\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---- colours
            if (info.usesGradient) {
                SectionTitle("Colours")
                LazyRow {
                    s.customGradient?.let { g ->
                        item(key = "custom") { CustomGradientSwatch(g) }
                    }
                    items(Palettes.all, key = { it.id }) { p ->
                        PaletteSwatch(p, selected = s.customGradient == null && p.id == s.paletteId) {
                            LightEngine.updateSettings(deviceId) { it.copy(paletteId = p.id, customGradient = null) }
                        }
                    }
                }
                info.params.firstOrNull { it.key == "gradient_roll" }?.let { roll ->
                    ParamControl(roll, values) { v -> LightEngine.updateSettings(deviceId) { it.withValue(roll.key, v) } }
                }
            }

            // ---- effect settings
            val specific = info.params.filter { it.key !in LAYOUT_KEYS && it.key != "gradient_roll" }
            if (specific.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("${info.name} settings", Modifier.weight(1f))
                    TextButton(onClick = {
                        LightEngine.updateSettings(deviceId) { it.copy(configs = it.configs - info.id) }
                    }) {
                        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                        Text(" Reset")
                    }
                }
                specific.forEach { param ->
                    ParamControl(param, values) { v -> LightEngine.updateSettings(deviceId) { it.withValue(param.key, v) } }
                }
            }

            SectionTitle("Layout")
            info.params.filter { it.key in LAYOUT_KEYS }.forEach { param ->
                ParamControl(param, values) { v -> LightEngine.updateSettings(deviceId) { it.withValue(param.key, v) } }
            }

            // ---- strip
            SectionTitle("Strip")
            var countText by remember(device.ledCount) { mutableStateOf(device.ledCount.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = countText,
                    onValueChange = { t ->
                        countText = t.filter { it.isDigit() }.take(4)
                        countText.toIntOrNull()?.takeIf { it in 1..4096 }?.let { n ->
                            LightEngine.updateDevice(deviceId) { it.copy(ledCount = n) }
                        }
                    },
                    label = { Text("Number of LEDs") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (device.kind == DeviceKind.WLED) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            WledApi.fetchInfo(device.host)?.let { info ->
                                LightEngine.updateDevice(deviceId) { it.copy(name = info.name, ledCount = info.ledCount) }
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Refresh, null)
                        Text(" From WLED")
                    }
                }
            }
            if (device.kind == DeviceKind.WLED) {
                Text(
                    "Address: ${device.host} · streamed with DDP at 60 fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { onOpenWled(device.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Language, null)
                    Text("  Open WLED settings")
                }
            }
        }
    }

    if (showPicker) {
        EffectPickerSheet(current = info.id, onDismiss = { showPicker = false }) { picked ->
            LightEngine.updateSettings(deviceId) { it.copy(effectId = picked.id) }
            showPicker = false
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                Column {
                    Text("Saves the current ${info.name} settings and colours.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(32) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    LightEngine.savePreset(name.trim(), device.settings); showSave = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("Its effect settings will be lost. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; LightEngine.removeDevice(deviceId) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EffectPickerSheet(current: String, onDismiss: () -> Unit, onPick: (EffectInfo) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Choose an effect", style = MaterialTheme.typography.titleLarge)
            Text(
                "${Effects.all.size} effects · most come from LedFx",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EffectCategory.entries.forEach { cat ->
                val list = Effects.all.filter { it.category == cat }
                if (list.isEmpty()) return@forEach
                SectionTitle(cat.title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { e ->
                        FilterChip(
                            selected = e.id == current,
                            onClick = { onPick(e) },
                            label = { Text(e.name) },
                            leadingIcon = if (e.audioReactive) {
                                { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                "2D matrix effects from LedFx (Flame, Plasma, Digital Rain…) need an LED panel layout and aren't included yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun GradientDot(colors: List<Int>) {
    val c = colors.map { rgbColor(it) }
    Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
            .background(if (c.size > 1) Brush.horizontalGradient(c) else Brush.horizontalGradient(listOf(c[0], c[0])))
    )
}

@Composable
private fun CustomGradientSwatch(colors: List<Int>) {
    val c = colors.map { rgbColor(it) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp).padding(4.dp),
    ) {
        Box(
            Modifier.size(68.dp, 36.dp).clip(RoundedCornerShape(10.dp))
                .background(if (c.size > 1) Brush.horizontalGradient(c) else Brush.horizontalGradient(listOf(c[0], c[0])))
                .border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(4.dp))
        Text("Preset", style = MaterialTheme.typography.labelSmall, color = Color.Unspecified)
    }
}
