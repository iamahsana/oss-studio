package com.iamahsana.ossstudio.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamahsana.ossstudio.BuildConfig
import com.iamahsana.ossstudio.logging.OssLogger
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(context: Context, repository: AppSettingsRepository) {
    val settings by repository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var diagnosticStatus by remember { mutableStateOf<String?>(null) }
    val storageBytes by produceState(initialValue = 0L, key1 = context.filesDir) {
        value = withContext(Dispatchers.IO) {
            runCatching { directorySize(context.filesDir) }.getOrDefault(0L)
        }
    }
    val diagnosticsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) {
            diagnosticStatus = "Menyiapkan log diagnostik…"
            scope.launch {
                val exported = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("File tujuan tidak dapat dibuka")
                        output.use { OssLogger.writeDiagnosticsTo(it) }
                    }.getOrDefault(false)
                }
                diagnosticStatus = if (exported) {
                    "Log diagnostik berhasil diekspor."
                } else {
                    "Ekspor gagal. Periksa ruang penyimpanan lalu coba lagi."
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard(title = "Tampilan") {
            Text("Tema aplikasi", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OssThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { scope.launch { repository.setThemeMode(mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }
            Text("Warna aksen", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppAccent.entries.forEach { accent ->
                    FilterChip(
                        selected = settings.accent == accent,
                        onClick = { scope.launch { repository.setAccent(accent) } },
                        label = { Text(accent.label) }
                    )
                }
            }
        }

        SettingsCard(title = "Penyimpanan") {
            Text("Penyimpanan aplikasi", style = MaterialTheme.typography.titleSmall)
            Text(formatBytes(storageBytes), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Perhitungan mencakup project, aset media, ekstensi, dan log di penyimpanan privat OSS.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SettingsCard(title = "Diagnostik") {
            Text(
                "Log berisi metadata aplikasi, versi Android, waktu, status komponen, dan kode error. OSS tidak merekam audio atau video.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "URL RTMP, stream key, token, dan password disamarkan sebelum disimpan.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { diagnosticsPicker.launch("oss-diagnostics.jsonl") }) {
                Text("Ekspor log diagnostik")
            }
            diagnosticStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        SettingsCard(title = "Tentang") {
            Text("OSS Studio ${BuildConfig.VERSION_NAME}")
            Text("Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        )
    }
}

private fun directorySize(directory: File): Long =
    directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024.0) return String.format(Locale.US, "%.1f KB", kilobytes)
    return String.format(Locale.US, "%.1f MB", kilobytes / 1024.0)
}
