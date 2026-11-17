package org.ledfxmobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ledfxmobile.app.effects.Palette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun rgbColor(c: Int) = Color(0xFF000000.toInt() or c)

/** Live LED strip preview. Pixels are 0xRRGGBB; null shows an unlit strip. */
@Composable
fun LedStrip(pixels: IntArray?, ledCount: Int, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0xFF08080B))
    ) {
        val n = max(1, pixels?.size ?: ledCount)
        // Draw at most one LED per ~3 px so long strips stay readable.
        val cells = min(n, max(1, (size.width / 3f).toInt()))
        val cellW = size.width / cells
        val gap = if (cellW > 6f) 1.5f else 0f
        for (c in 0 until cells) {
            val idx = (c.toLong() * n / cells).toInt()
            val px = pixels?.getOrNull(idx) ?: 0
            val color = if (px == 0) Color(0xFF1B1B22) else rgbColor(px)
            // Soft glow behind lit LEDs.
            if (px != 0) {
                drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(c * cellW - cellW * 0.3f, 0f),
                    size = Size(cellW * 1.6f, size.height),
                )
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(c * cellW + gap / 2, size.height * 0.2f),
                size = Size(cellW - gap, size.height * 0.6f),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

@Composable
fun PaletteSwatch(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    val colors = palette.previewColors.map { rgbColor(it) }
    val brush = if (colors.size == 1) Brush.horizontalGradient(listOf(colors[0], colors[0]))
    else Brush.horizontalGradient(colors)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .size(68.dp, 36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brush)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            palette.name,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange)
    }
}
