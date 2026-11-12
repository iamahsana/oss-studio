package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneProjectFactory
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectHomeScreen(
    context: Context,
    activeProjectId: String?,
    canDeleteActiveProject: () -> Boolean,
    onOpenProject: (SceneProject) -> Unit,
    onProjectDeleted: (String) -> Unit
) {
    val repository = remember(context) {
        SceneProjectRepository(SceneProjectStoreProvider.database(context))
    }
    val projects = remember { mutableStateListOf<ProjectSummary>() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var projectPendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(repository) {
        onDispose { repository.close() }
    }
    LaunchedEffect(repository) {
        repository.recent { result ->
            mainHandler.post {
                result.fold(
                    onSuccess = { projects.clear(); projects.addAll(it); loading = false },
                    onFailure = { errorMessage = it.message ?: "Tidak dapat memuat project"; loading = false }
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            onClick = { projectName = ""; errorMessage = null; showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text("＋   Buat project baru") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Project kamu", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${projects.size} project tersimpan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = {
                loading = true
                repository.recent { result ->
                    mainHandler.post {
                        result.fold(
                            onSuccess = { projects.clear(); projects.addAll(it); loading = false },
                            onFailure = { errorMessage = it.message ?: "Gagal memuat ulang"; loading = false }
                        )
                    }
                }
            }) { Text("Segarkan") }
        }
        if (loading) CircularProgressIndicator()
        if (!loading && projects.isEmpty()) {
            Text("Belum ada project. Buat project pertama untuk mulai menyusun scene.")
        }
        projects.forEach { project ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Card(
                    modifier = Modifier.weight(1f).clickable(enabled = !working) {
                        working = true
                        repository.load(project.id) { result ->
                            mainHandler.post {
                                working = false
                                result.fold(
                                    onSuccess = { loaded ->
                                        if (loaded != null) onOpenProject(loaded)
                                        else errorMessage = "Project tidak ditemukan."
                                    },
                                    onFailure = { errorMessage = it.message ?: "Gagal membuka project" }
                                )
                            }
                        }
                    }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text("Diperbarui ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.updatedAtEpochMillis))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(modifier = Modifier.testTag("delete-project-${project.id}"), enabled = !working, onClick = {
                    if (project.id == activeProjectId && !canDeleteActiveProject()) {
                        errorMessage = "Hentikan atau selesaikan cleanup LIVE sebelum menghapus project aktif."
                    } else {
                        projectPendingDelete = project
                    }
                }) { Text("Hapus") }
            }
        }
        if (working) Text("Membuka project…")
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (projectPendingDelete != null) {
        val target = projectPendingDelete!!
        AlertDialog(
            onDismissRequest = { if (!working) projectPendingDelete = null },
            title = { Text("Hapus project?") },
            text = { Text("Project ${target.name}, seluruh scene, dan file medianya akan dihapus dari perangkat ini.") },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("confirm-delete-project"), enabled = !working, onClick = {
                    if (target.id == activeProjectId && !canDeleteActiveProject()) {
                        working = false
                        projectPendingDelete = null
                        errorMessage = "Hentikan atau selesaikan cleanup LIVE sebelum menghapus project aktif."
                        return@TextButton
                    }
                    working = true
                    repository.load(target.id) { loadResult ->
                        val project = loadResult.getOrNull()
                        if (project == null) {
                            mainHandler.post {
                                working = false
                                errorMessage = loadResult.exceptionOrNull()?.message ?: "Project tidak dapat dibaca; file media tidak dihapus."
                            }
                            return@load
                        }
                        repository.delete(target.id) { deleteResult ->
                            deleteResult.onSuccess { deleted ->
                                if (deleted) {
                                    project.assets.forEach { asset ->
                                        runCatching { SceneAssetStorage.deleteAsset(context, asset) }
                                    }
                                }
                            }
                            mainHandler.post {
                                working = false
                                deleteResult.fold(
                                    onSuccess = { deleted ->
                                        if (deleted) {
                                            projects.removeAll { it.id == target.id }
                                            onProjectDeleted(target.id)
                                        } else errorMessage = "Project tidak ditemukan."
                                        projectPendingDelete = null
                                    },
                                    onFailure = { errorMessage = it.message ?: "Gagal menghapus project" }
                                )
                            }
                        }
                    }
                }) { Text("Ya, hapus") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { projectPendingDelete = null }) { Text("Batal") }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!working) showCreateDialog = false },
            title = { Text("Project baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it.take(60) },
                        label = { Text("Nama project") },
                        singleLine = true
                    )
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = projectName.isNotBlank() && !working, onClick = {
                    working = true
                    errorMessage = null
                    val project = runCatching {
                        SceneProjectFactory.create(projectName, System.currentTimeMillis())
                    }.getOrElse {
                        working = false
                        errorMessage = it.message ?: "Nama project tidak valid"
                        return@TextButton
                    }
                    repository.save(project) { result ->
                        mainHandler.post {
                            working = false
                            result.fold(
                                onSuccess = {
                                    projects.removeAll { it.id == project.id }
                                    projects.add(0, ProjectSummary(project.id, project.name, project.updatedAtEpochMillis))
                                    showCreateDialog = false
                                    onOpenProject(project)
                                },
                                onFailure = { errorMessage = it.message ?: "Gagal menyimpan project" }
                            )
                        }
                    }
                }) { Text("Buat") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { showCreateDialog = false }) { Text("Batal") }
            }
        )
    }
}
