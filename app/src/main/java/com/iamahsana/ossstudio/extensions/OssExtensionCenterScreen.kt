package com.iamahsana.ossstudio.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private data class InstalledOssExtension(
    val id: String,
    val name: String,
    val version: String,
    val elements: List<OssPackElement>,
    val directory: File
)

@Composable
fun OssExtensionCenterScreen(context: Context) {
    val extensionsDirectory = remember(context) { File(context.filesDir, "extensions") }
    val executor = remember(context) { Executors.newSingleThreadExecutor() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var installed by remember { mutableStateOf(emptyList<InstalledOssExtension>()) }
    var status by remember { mutableStateOf("Paket ekstensi deklaratif dapat dipasang dari file .osspack.") }
    var busy by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<InstalledOssExtension?>(null) }

    fun refresh() {
        executor.execute {
            val result = runCatching { readInstalledExtensions(extensionsDirectory) }
            handler.post {
                result.onSuccess { installed = it }
                    .onFailure { status = it.message ?: "Gagal membaca daftar ekstensi" }
            }
        }
    }

    DisposableEffect(executor) {
        onDispose { executor.shutdownNow() }
    }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { readInstalledExtensions(extensionsDirectory) }
    }

    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !busy) {
            busy = true
            status = "Memvalidasi dan memasang paket…"
            executor.execute {
                val result = runCatching {
                    val archive = copyPackageToCache(context, uri)
                    try {
                        OssPackInstaller.install(archive, extensionsDirectory)
                    } finally {
                        archive.delete()
                    }
                }
                handler.post {
                    busy = false
                    result.fold(
                        onSuccess = { directory ->
                            status = "Ekstensi berhasil dipasang: ${directory.name}"
                            refresh()
                        },
                        onFailure = { error ->
                            status = error.message ?: "Paket ekstensi gagal dipasang"
                        }
                    )
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Extension Center", style = MaterialTheme.typography.titleLarge)
        Text(
            "OSS hanya memasang paket deklaratif berisi resource. Paket executable seperti APK, DEX, JAR, script, dan WebAssembly ditolak.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            enabled = !busy,
            onClick = { packagePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ) {
            Text(if (busy) "Memasang…" else "Pasang file .osspack")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Ekstensi terpasang (${installed.size})", style = MaterialTheme.typography.titleMedium)
        if (installed.isEmpty()) {
            Text("Belum ada ekstensi yang dipasang.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEach { extension ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(extension.name, style = MaterialTheme.typography.titleSmall)
                                Text(extension.id, style = MaterialTheme.typography.bodySmall)
                                Text("Versi ${extension.version}", style = MaterialTheme.typography.labelSmall)
                                if (extension.elements.isNotEmpty()) {
                                    Text("Elemen: " + extension.elements.joinToString { "${it.name} (${it.kind})" }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            OutlinedButton(onClick = { pendingRemoval = extension }) { Text("Hapus") }
                        }
                    }
                }
            }
        }
    }

    pendingRemoval?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Hapus ekstensi?") },
            text = { Text("Resource ${extension.name} akan dihapus dari penyimpanan OSS.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    busy = true
                    executor.execute {
                        val removed = runCatching { extension.directory.deleteRecursively() }
                        handler.post {
                            busy = false
                            removed.fold(
                                onSuccess = {
                                    status = if (it) "Ekstensi dihapus." else "Direktori ekstensi tidak dapat dihapus."
                                    refresh()
                                },
                                onFailure = { status = it.message ?: "Gagal menghapus ekstensi" }
                            )
                        }
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Batal") } }
        )
    }
}

private fun copyPackageToCache(context: Context, uri: Uri): File {
    val displayName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty()
    require(displayName.lowercase().endsWith(".osspack")) { "Pilih file dengan ekstensi .osspack" }
    val target = File.createTempFile("oss-extension-", ".osspack", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: error("File paket tidak dapat dibuka")
        var total = 0L
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= OssPackArchiveValidator.MAX_ARCHIVE_BYTES) { "Paket melebihi batas 64 MB" }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(total > 0L) { "File paket kosong" }
        return target
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

private fun readInstalledExtensions(root: File): List<InstalledOssExtension> {
    if (!root.exists()) return emptyList()
    return root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { directory ->
            runCatching {
                val manifest = OssPackArchiveValidator.readInstalledManifest(directory)
                InstalledOssExtension(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    elements = manifest.elements,
                    directory = directory
                )
            }.getOrNull()
        }
        .sortedBy { it.name.lowercase() }
}
