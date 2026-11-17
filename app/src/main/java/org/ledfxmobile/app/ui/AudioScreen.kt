package org.ledfxmobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ledfxmobile.app.audio.AudioSourceType
import org.ledfxmobile.app.effects.Palettes
import org.ledfxmobile.app.engine.LightEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(padding: PaddingValues, onRequestPhoneAudio: () -> Unit) {
    val source by LightEngine.audioSource.collectAsStateWithLifecycle()
    val audio by LightEngine.audio.collectAsStateWithLifecycle()
    val problem by LightEngine.audioProblem.collectAsStateWithLifecycle()
    val sensitivity by LightEngine.sensitivity.collectAsStateWithLifecycle()
    val running by LightEngine.running.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        SectionTitle("Listen to")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = source == AudioSourceType.MICROPHONE,
                onClick = { LightEngine.setAudioSource(AudioSourceType.MICROPHONE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.Mic, null) },
            ) { Text("Microphone") }
            SegmentedButton(
                selected = source == AudioSourceType.PHONE_AUDIO,
                onClick = {
                    LightEngine.setAudioSource(AudioSourceType.PHONE_AUDIO)
                    if (LightEngine.projection == null) onRequestPhoneAudio()
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.MusicNote, null) },
            ) { Text("Phone audio") }
        }
        Text(
            if (source == AudioSourceType.MICROPHONE) "Reacts to sound in the room."
            else "Reacts to music playing on this phone (Spotify, YouTube, …). Some apps block this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        val p = problem
        if (running && p != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, modifier = Modifier.weight(1f))
                    if (source == AudioSourceType.PHONE_AUDIO) {
                        Button(onClick = onRequestPhoneAudio) { Text("Allow") }
                    }
                }
            }
        }

        SectionTitle("Live")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) {
                Spectrum(audio.bands, Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f + 0.85f * audio.beatPulse)
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Beat", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            !running -> "Engine stopped"
                            audio.silent -> "Silence"
                            else -> "Listening"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Meter("Volume", audio.volume)
                Meter("Bass", audio.lows)
                Meter("Mids", audio.mids)
                Meter("Highs", audio.highs)
            }
        }

        SectionTitle("Sensitivity")
        LabeledSlider("How strongly lights react", sensitivity) { LightEngine.setSensitivity(it) }
        Text(
            "Raise it for quiet rooms or low phone volume; lower it if everything stays maxed out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Meter(label: String, value: Float) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape),
        )
    }
}

@Composable
private fun Spectrum(bands: FloatArray, modifier: Modifier) {
    val palette = Palettes.get("neon")
    Canvas(modifier) {
        val n = bands.size
        val slot = size.width / n
        val barW = slot * 0.7f
        drawRoundRect(Color(0xFF0B0B10), cornerRadius = CornerRadius(16f, 16f))
        for (i in 0 until n) {
            val v = bands[i].coerceIn(0f, 1f)
            val h = (size.height - 8f) * maxOf(0.02f, v)
            drawRoundRect(
                color = rgbColor(palette.clamped(i / (n - 1f))),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - 4f - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}
