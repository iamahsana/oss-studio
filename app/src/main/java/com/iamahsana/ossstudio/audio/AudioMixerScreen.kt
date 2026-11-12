package com.iamahsana.ossstudio.audio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun AudioMixerScreen(context: Context) {
    var settings by remember { mutableStateOf(AudioMixerSettingsStore.load(context)) }
    var microphonePeak by remember { mutableStateOf(0f) }
    var internalPeak by remember { mutableStateOf(0f) }
    var captureSnapshot by remember { mutableStateOf(AudioMixerControlBus.captureSnapshot) }
    var mixerState by remember { mutableStateOf(AudioMixerControlBus.state) }

    fun update(next: AudioMixerSettings) {
        settings = next
        AudioMixerControlBus.apply(next)
    }

    LaunchedEffect(settings) {
        delay(300L)
        AudioMixerSettingsStore.persist(context, settings)
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            microphonePeak = AudioMixerControlBus.channelPeak(0)
            internalPeak = AudioMixerControlBus.channelPeak(1)
            captureSnapshot = AudioMixerControlBus.captureSnapshot
            mixerState = AudioMixerControlBus.state
            delay(100L)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mixer utama", style = MaterialTheme.typography.titleLarge)
                Text("LIVE bus dan MONITOR bus memiliki volume master terpisah.", style = MaterialTheme.typography.bodySmall)
                Text("LIVE master: ${(settings.liveMasterGain * 100).toInt()}%")
                Slider(
                    value = settings.liveMasterGain,
                    onValueChange = { update(settings.copy(liveMasterGain = it)) },
                    valueRange = 0f..2f,
                    steps = 39
                )
                Text("MONITOR master: ${(settings.monitorMasterGain * 100).toInt()}%")
                Slider(
                    value = settings.monitorMasterGain,
                    onValueChange = { update(settings.copy(monitorMasterGain = it)) },
                    valueRange = 0f..2f,
                    steps = 39
                )
                Text("Status mixer: ${mixerState?.name ?: "Belum ada sesi LIVE"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { update(AudioMixerSettings()) }) { Text("Reset mixer") }
            }
        }

        ChannelStrip(
            title = "Microphone",
            subtitle = "Suara mikrofon perangkat atau input headset yang dipilih Android",
            config = settings.microphone,
            peak = microphonePeak,
            status = captureSnapshot?.microphone?.state?.name ?: "Menunggu LIVE",
            onChange = { update(settings.copy(microphone = it)) }
        )
        ChannelStrip(
            title = "Internal / Game audio",
            subtitle = "Android playback capture; aplikasi tertentu dapat melarang perekaman audio",
            config = settings.internalAudio,
            peak = internalPeak,
            status = captureSnapshot?.internalAudio?.state?.name ?: "Menunggu izin Share Screen",
            onChange = { update(settings.copy(internalAudio = it)) }
        )
        Text(
            "OSS memulai semua kanal yang tersedia secara otomatis saat LIVE. Tidak ada pemilihan Mic/Internal sebelum LIVE. Kanal yang tidak didukung atau belum mendapat izin akan menampilkan statusnya sendiri.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ChannelStrip(
    title: String,
    subtitle: String,
    config: ChannelMixConfig,
    peak: Float,
    status: String,
    onChange: (ChannelMixConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            Text("Level: ${(config.gain * 100).toInt()}%")
            Slider(
                value = config.gain,
                onValueChange = { onChange(config.copy(gain = it)) },
                valueRange = 0f..2f,
                steps = 39
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Peak", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text("${(peak.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(peak.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Text("Pan / Balance: ${(config.pan * 100).toInt()}")
            Slider(
                value = config.pan,
                onValueChange = { onChange(config.copy(pan = it)) },
                valueRange = -1f..1f,
                steps = 39
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MixerToggle("Mute", config.muted) { onChange(config.copy(muted = it)) }
                MixerToggle("Solo", config.solo) { onChange(config.copy(solo = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MixerToggle("LIVE", config.routeToLive) { onChange(config.copy(routeToLive = it)) }
                MixerToggle("MONITOR", config.routeToMonitor) { onChange(config.copy(routeToMonitor = it)) }
            }
        }
    }
}

@Composable
private fun MixerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
