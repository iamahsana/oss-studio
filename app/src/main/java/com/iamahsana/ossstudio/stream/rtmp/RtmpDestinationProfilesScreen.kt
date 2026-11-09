package com.iamahsana.ossstudio.stream.rtmp

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RtmpDestinationProfilesScreen(context: Context) {
    val initialLoad = remember(context) { runCatching { RtmpDestinationProfileStore.load(context) } }
    var profiles by remember { mutableStateOf(initialLoad.getOrDefault(emptyList())) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RtmpDestinationProfile?>(null) }
    var confirmVaultReset by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var streamKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var storeError by remember { mutableStateOf(initialLoad.exceptionOrNull()?.message) }

    fun saveProfiles(updated: List<RtmpDestinationProfile>) {
        runCatching { RtmpDestinationProfileStore.save(context, updated) }
            .onSuccess { profiles = updated; storeError = null; errorMessage = null }
            .onFailure { storeError = it.message ?: "Gagal menyimpan profil RTMP" }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tujuan RTMP", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(enabled = storeError == null, onClick = {
                name = ""
                serverUrl = ""
                streamKey = ""
                errorMessage = null
                showAddDialog = true
            }) { Text("Tambah") }
        }
        Text(
            "Stream key dienkripsi dengan AES-GCM dan kunci yang disimpan Android Keystore. Kunci tidak ditampilkan pada daftar tujuan.",
            style = MaterialTheme.typography.bodySmall
        )
        storeError?.let {
            Text("Vault tidak dapat dibuka: $it. Profil lama tidak akan ditimpa otomatis.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { confirmVaultReset = true }) { Text("Reset vault") }
        }
        if (profiles.isEmpty()) Text("Belum ada tujuan RTMP yang disimpan.")
        profiles.forEach { profile ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = profile.enabled,
                            enabled = storeError == null,
                            onCheckedChange = { enabled ->
                                saveProfiles(profiles.map { if (it.id == profile.id) it.copy(enabled = enabled) else it })
                            }
                        )
                    }
                    Text(profile.serverUrl, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (profile.enabled) "Aktif" else "Dinonaktifkan", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        TextButton(enabled = storeError == null, onClick = { pendingDelete = profile }) { Text("Hapus") }
                    }
                }
            }
        }
        Text(
            "Profil tersimpan dan siap dipakai oleh pipeline RTMP. Kontrol LIVE multi-destinasi akan dihubungkan setelah jalur fan-out encoder dan compositor selesai.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah tujuan RTMP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Nama tujuan") }, singleLine = true)
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it.take(512) }, label = { Text("Server URL (rtmp:// atau rtmps://)") }, singleLine = true)
                    OutlinedTextField(
                        value = streamKey,
                        onValueChange = { streamKey = it.take(1024) },
                        label = { Text("Stream key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && serverUrl.isNotBlank() && streamKey.isNotBlank(), onClick = {
                    runCatching {
                        RtmpDestinationProfile(name = name, serverUrl = serverUrl, streamKey = streamKey)
                    }.onSuccess { profile ->
                        val updated = profiles + profile
                        runCatching { RtmpDestinationProfileStore.save(context, updated) }
                            .onSuccess {
                                profiles = updated
                                showAddDialog = false
                                errorMessage = null
                                storeError = null
                            }
                            .onFailure { errorMessage = it.message ?: "Gagal menyimpan profil" }
                    }.onFailure { errorMessage = it.message ?: "Data tujuan tidak valid" }
                }) { Text("Simpan terenkripsi") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Batal") } }
        )
    }


    if (confirmVaultReset) {
        AlertDialog(
            onDismissRequest = { confirmVaultReset = false },
            title = { Text("Reset vault RTMP?") },
            text = { Text("Semua profil RTMP terenkripsi yang tersimpan akan dihapus. Tindakan ini tidak dapat dibatalkan.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { RtmpDestinationProfileStore.clear(context) }
                        .onSuccess {
                            profiles = emptyList()
                            storeError = null
                            errorMessage = null
                        }
                        .onFailure { storeError = it.message ?: "Vault gagal direset" }
                    confirmVaultReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmVaultReset = false }) { Text("Batal") } }
        )
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus tujuan?") },
            text = { Text("Profil ${target.name} dan stream key terenkripsinya akan dihapus dari perangkat.") },
            confirmButton = {
                TextButton(onClick = {
                    saveProfiles(profiles.filterNot { it.id == target.id })
                    pendingDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } }
        )
    }
}
