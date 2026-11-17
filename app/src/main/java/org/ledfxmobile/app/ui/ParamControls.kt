package org.ledfxmobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ledfxmobile.app.effects.BoolParam
import org.ledfxmobile.app.effects.ChoiceParam
import org.ledfxmobile.app.effects.ColorParam
import org.ledfxmobile.app.effects.FloatParam
import org.ledfxmobile.app.effects.IntParam
import org.ledfxmobile.app.effects.Param
import org.ledfxmobile.app.effects.ParamValues
import org.ledfxmobile.app.effects.colorToHex
import org.ledfxmobile.app.effects.parseColor
import kotlin.math.roundToInt

/** Renders the right control for an effect setting. */
@Composable
fun ParamControl(param: Param, values: ParamValues, onChange: (Any) -> Unit) {
    when (param) {
        is FloatParam -> {
            val v = values.f(param.key).coerceIn(param.min, param.max)
            val span = param.max - param.min
            val text = when {
                span >= 20f -> v.roundToInt().toString()
                span >= 2f -> "%.1f".format(v)
                else -> "%.2f".format(v)
            }
            SettingSlider(param.label, text, v, param.min..param.max, steps = 0) { onChange(it) }
        }
        is IntParam -> {
            val v = values.i(param.key).coerceIn(param.min, param.max)
            val span = param.max - param.min
            SettingSlider(
                param.label, v.toString(), v.toFloat(), param.min.toFloat()..param.max.toFloat(),
                steps = if (span in 2..40) span - 1 else 0,
            ) { onChange(it.roundToInt()) }
        }
        is BoolParam -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(param.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = values.b(param.key), onCheckedChange = { onChange(it) })
        }
        is ChoiceParam -> ChoiceRow(param, values.s(param.key)) { onChange(it) }
        is ColorParam -> ColorRow(param.label, values.color(param.key)) { onChange(it) }
    }
}

@Composable
fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    // Local state keeps dragging smooth; the engine gets every change.
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = { local = it; onChange(it) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(),
        )
    }
}

fun prettyOption(s: String): String =
    s.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun ChoiceRow(param: ChoiceParam, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(prettyOption(current))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                param.options.forEach { o ->
                    DropdownMenuItem(text = { Text(prettyOption(o)) }, onClick = { open = false; onPick(o) })
                }
            }
        }
    }
}

@Composable
fun ColorRow(label: String, color: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(colorToHex(color), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(rgbColor(color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
    if (open) ColorPickerDialog(color, onDismiss = { open = false }) { onPick(it); open = false }
}

private val SWATCHES = listOf(
    0xFFFFFF, 0xFF0000, 0xFF2800, 0xFF7800, 0xFFC800, 0xFFFF00, 0x00FF00, 0x00FF32,
    0x00C78C, 0x00FFFF, 0x4AA3FF, 0x0000FF, 0x800080, 0xFF00FF, 0xFF00B2, 0x000000,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(colorToHex(initial)) }
    fun current() = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF
    fun setFrom(c: Int) {
        val a = FloatArray(3); android.graphics.Color.colorToHSV(c or 0xFF000000.toInt(), a)
        hue = a[0]; sat = a[1]; value = a[2]; hex = colorToHex(c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a colour") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.medium)
                        .background(rgbColor(current()))
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SWATCHES.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(rgbColor(c))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { setFrom(c) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(
                        Brush.horizontalGradient(List(7) { Color.hsv(it * 60f % 360f, 1f, 1f) })
                    )
                )
                Slider(value = hue, onValueChange = { hue = it; hex = colorToHex(current()) }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it; hex = colorToHex(current()) })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { value = it; hex = colorToHex(current()) })
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t -> hex = t; parseColor(t)?.let { c -> setFrom(c); hex = t } },
                    label = { Text("Hex") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current()) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
