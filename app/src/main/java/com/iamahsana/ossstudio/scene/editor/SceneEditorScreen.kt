package com.iamahsana.ossstudio.scene.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.CanvasSize
import com.iamahsana.ossstudio.scene.model.SceneProjectEditor
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.iamahsana.ossstudio.scene.persistence.SceneAssetStorage
import com.iamahsana.ossstudio.scene.persistence.SceneProjectAutosave
import com.iamahsana.ossstudio.scene.persistence.SceneProjectRepository
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SceneEditorScreen(
    context: Context,
    project: SceneProject,
    onProjectChange: (SceneProject) -> Unit
) {
    var currentProject by remember(project.id) { mutableStateOf(project) }
    var selectedElementId by remember(project.id) { mutableStateOf<String?>(null) }
    val repository = remember(project.id) {
        SceneProjectRepository(SceneProjectStoreProvider.database(context))
    }
    val autosave = remember(repository) { SceneProjectAutosave(repository) }
    val latestProject = rememberUpdatedState(currentProject)
    fun applyProject(updated: SceneProject) {
        currentProject = updated
        onProjectChange(updated)
        autosave.schedule(updated)
    }
    val importExecutor = remember(repository) { Executors.newSingleThreadExecutor() }
    val importActive = remember(repository) { AtomicBoolean(true) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var mediaStatus by remember(project.id) { mutableStateOf<String?>(null) }
    DisposableEffect(autosave, repository, importExecutor) {
        onDispose {
            importActive.set(false)
            importExecutor.shutdownNow()
            autosave.close()
            repository.close()
        }
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            mediaStatus = "Mengimpor media…"
            val projectId = currentProject.id
            runCatching {
                importExecutor.execute {
                    val result = runCatching { SceneAssetStorage.importUri(context, projectId, uri) }
                    if (importActive.get()) {
                        mainHandler.post {
                            if (importActive.get()) {
                                result.fold(
                                    onSuccess = { imported ->
                                        runCatching {
                                            val base = latestProject.value
                                            val timestamp = System.currentTimeMillis()
                                            val withAsset = SceneProjectEditor.addAsset(base, imported.asset, timestamp)
                                            val source = SceneSource(
                                                type = imported.sourceType,
                                                displayName = imported.displayName,
                                                assetId = imported.asset.id
                                            )
                                            val withSource = SceneProjectEditor.addSource(withAsset, source, timestamp)
                                            val activeSceneId = withSource.activeSceneId ?: withSource.scenes.firstOrNull()?.id
                                                ?: error("Project belum memiliki scene")
                                            val updated = SceneProjectEditor.addElement(withSource, activeSceneId, source.id, timestamp)
                                            selectedElementId = updated.scenes.first { it.id == activeSceneId }.elements.last().id
                                            applyProject(updated)
                                            mediaStatus = "Media berhasil ditambahkan: ${imported.displayName}"
                                        }.onFailure {
                                            SceneAssetStorage.deleteAsset(context, imported.asset)
                                            mediaStatus = it.message ?: "Media gagal ditambahkan ke scene"
                                        }
                                    },
                                    onFailure = { mediaStatus = it.message ?: "Gagal mengimpor media" }
                                )
                            } else {
                                result.getOrNull()?.let { imported ->
                                    runCatching { SceneAssetStorage.deleteAsset(context, imported.asset) }
                                }
                            }
                        }
                    } else {
                        result.getOrNull()?.let { imported ->
                            runCatching { SceneAssetStorage.deleteAsset(context, imported.asset) }
                        }
                    }
                }
            }.onFailure { mediaStatus = it.message ?: "Tidak dapat memulai impor media" }
        }
    }
    val scene = currentProject.scenes.firstOrNull { it.id == currentProject.activeSceneId }
        ?: currentProject.scenes.firstOrNull()
    val selectedElement = scene?.elements?.firstOrNull { it.id == selectedElementId }
    val selectedSource = currentProject.sources.firstOrNull { it.id == selectedElement?.sourceId }
    var editingText by remember(selectedElementId) { mutableStateOf(selectedSource?.text.orEmpty()) }
    var confirmRemoveSource by remember(selectedElementId) { mutableStateOf(false) }

    fun addSource(type: SceneSourceType, text: String? = null) {
        val activeScene = scene ?: return
        val source = SceneSource(
            type = type,
            displayName = when (type) {
                SceneSourceType.TEXT -> "Text"
                SceneSourceType.SHARE_SCREEN -> "Share Screen"
                else -> type.name
            },
            text = text
        )
        val timestamp = System.currentTimeMillis()
        val withSource = SceneProjectEditor.addSource(currentProject, source, timestamp)
        val updated = SceneProjectEditor.addElement(withSource, activeScene.id, source.id, timestamp)
        selectedElementId = updated.scenes.first { it.id == activeScene.id }.elements.last().id
        editingText = text.orEmpty()
        applyProject(updated)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(currentProject.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("Autosave", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (scene == null) {
            Text("Project ini belum memiliki scene.")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentProject.scenes.forEach { item ->
                    OutlinedButton(onClick = {
                        applyProject(SceneProjectEditor.setActiveScene(currentProject, item.id, System.currentTimeMillis()))
                        selectedElementId = null
                    }) { Text(if (item.id == scene.id) "✓ ${item.name}" else item.name) }
                }
                OutlinedButton(onClick = {
                    val newScene = Scene(
                        projectId = currentProject.id,
                        name = "Scene ${currentProject.scenes.size + 1}",
                        canvas = scene.canvas
                    )
                    val withScene = SceneProjectEditor.addScene(currentProject, newScene, System.currentTimeMillis())
                    applyProject(SceneProjectEditor.setActiveScene(withScene, newScene.id, System.currentTimeMillis()))
                    selectedElementId = null
                }) { Text("＋ Scene") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    applyProject(SceneProjectEditor.updateSceneCanvas(
                        currentProject, scene.id, CanvasSize.DEFAULT_PORTRAIT, System.currentTimeMillis()
                    ))
                }) { Text("Portrait 9:16") }
                OutlinedButton(onClick = {
                    applyProject(SceneProjectEditor.updateSceneCanvas(
                        currentProject, scene.id, CanvasSize.DEFAULT_LANDSCAPE, System.currentTimeMillis()
                    ))
                }) { Text("Landscape 16:9") }
            }
            Text(scene.name, style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(scene.canvas.width.toFloat() / scene.canvas.height.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF17191D))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            ) {
                val scale = constraints.maxWidth.toFloat() / scene.canvas.width.toFloat()
                val density = LocalDensity.current
                scene.elements.sortedBy { it.zIndex }.filter { it.visible }.forEach { element ->
                    val source = currentProject.sources.firstOrNull { it.id == element.sourceId } ?: return@forEach
                    val transform = element.transform
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (transform.x * scale).roundToInt(),
                                    (transform.y * scale).roundToInt()
                                )
                            }
                            .size(
                                with(density) { (transform.width * scale).toDp() },
                                with(density) { (transform.height * scale).toDp() }
                            )
                            .then(
                                if (element.id == selectedElementId) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                                else Modifier.border(1.dp, Color(0xFF777777))
                            )
                            .background(if (element.id == selectedElementId) Color(0x553B82F6) else Color(0x33222222))
                            .clickable { selectedElementId = element.id }
                            .then(
                                if (element.locked) Modifier else Modifier.pointerInput(element.id, scale) {
                                    var dragX = transform.x
                                    var dragY = transform.y
                                    detectDragGestures(
                                        onDragStart = { selectedElementId = element.id },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragX = (dragX + amount.x / scale).coerceIn(0f, (scene.canvas.width - transform.width).coerceAtLeast(0f))
                                            dragY = (dragY + amount.y / scale).coerceIn(0f, (scene.canvas.height - transform.height).coerceAtLeast(0f))
                                            val updated = SceneProjectEditor.updateTransform(
                                                latestProject.value,
                                                scene.id,
                                                element.id,
                                                transform.copy(x = dragX, y = dragY),
                                                System.currentTimeMillis()
                                            )
                                            applyProject(updated)
                                        }
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val label = when (source.type) {
                            SceneSourceType.TEXT -> source.text.orEmpty()
                            SceneSourceType.SHARE_SCREEN -> "Share Screen"
                            SceneSourceType.IMAGE -> "Image"
                            SceneSourceType.GIF -> "GIF"
                            SceneSourceType.VIDEO -> "Video"
                            SceneSourceType.CAMERA -> "Camera"
                            SceneSourceType.USB_VIDEO -> "USB Video"
                        }
                        if (source.type == SceneSourceType.IMAGE || source.type == SceneSourceType.GIF || source.type == SceneSourceType.VIDEO) {
                            val asset = currentProject.assets.firstOrNull { it.id == source.assetId }
                            if (asset != null) {
                                SceneAssetImage(
                                    filePath = File(context.filesDir, asset.relativePath).absolutePath,
                                    contentDescription = source.displayName,
                                    isVideo = source.type == SceneSourceType.VIDEO
                                )
                            } else {
                                Text(label, color = Color.White, textAlign = TextAlign.Center)
                            }
                        } else {
                            Text(
                                label,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (scene.elements.none { it.visible }) {
                    Text("Canvas kosong", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            Text(
                "Geser elemen untuk mengatur posisi. Canvas ini adalah area komposisi scene, bukan preview LIVE terpisah.",
                style = MaterialTheme.typography.bodySmall
            )
            mediaStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { addSource(SceneSourceType.TEXT, "Teks baru") }) { Text("Tambah teks") }
                OutlinedButton(onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) }) { Text("Tambah media") }
                OutlinedButton(onClick = { addSource(SceneSourceType.SHARE_SCREEN) }) { Text("Share Screen") }
            }
            if (selectedElement != null && selectedSource != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Elemen dipilih: ${selectedSource.displayName}", style = MaterialTheme.typography.titleSmall)
                        if (selectedSource.type == SceneSourceType.TEXT) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Isi teks") }
                            )
                            Button(onClick = {
                                val updatedSource = selectedSource.copy(text = editingText)
                                applyProject(SceneProjectEditor.updateSource(currentProject, updatedSource, System.currentTimeMillis()))
                            }) { Text("Terapkan teks") }
                        }
                        val orderedElements = scene.elements.sortedBy { it.zIndex }
                        val layerIndex = orderedElements.indexOfFirst { it.id == selectedElement.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(enabled = !selectedElement.locked && layerIndex > 0, onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, selectedElement.id, layerIndex - 1, System.currentTimeMillis()
                                ))
                            }) { Text("Turunkan layer") }
                            OutlinedButton(enabled = !selectedElement.locked && layerIndex in 0 until orderedElements.lastIndex, onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, selectedElement.id, layerIndex + 1, System.currentTimeMillis()
                                ))
                            }) { Text("Naikkan layer") }
                            OutlinedButton(enabled = !selectedElement.locked, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(width = (transform.width + 40f).coerceAtMost(scene.canvas.width - transform.x)),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Lebarkan") }
                            OutlinedButton(enabled = !selectedElement.locked, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(height = (transform.height + 40f).coerceAtMost(scene.canvas.height - transform.y)),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Tinggikan") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                applyProject(SceneProjectEditor.setElementLocked(
                                    currentProject, scene.id, selectedElement.id, !selectedElement.locked, System.currentTimeMillis()
                                ))
                            }) { Text(if (selectedElement.locked) "Buka kunci" else "Kunci") }
                            OutlinedButton(onClick = {
                                applyProject(SceneProjectEditor.setElementVisibility(
                                    currentProject, scene.id, selectedElement.id, !selectedElement.visible, System.currentTimeMillis()
                                ))
                            }) { Text(if (selectedElement.visible) "Sembunyikan" else "Tampilkan") }
                            OutlinedButton(onClick = {
                                applyProject(SceneProjectEditor.removeElement(
                                    currentProject, scene.id, selectedElement.id, System.currentTimeMillis()
                                ))
                                selectedElementId = null
                            }) { Text("Hapus elemen") }
                            OutlinedButton(onClick = { confirmRemoveSource = true }) { Text("Hapus sumber") }
                        }
                    }
                }
            }
            Text("Elemen scene", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scene.elements.sortedBy { it.zIndex }.forEach { element ->
                    val source = currentProject.sources.firstOrNull { it.id == element.sourceId }
                    OutlinedButton(onClick = {
                        selectedElementId = element.id
                        editingText = source?.text.orEmpty()
                    }) { Text(source?.displayName ?: "Element") }
                }
            }
        }
    }

    if (confirmRemoveSource && selectedSource != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSource = false },
            title = { Text("Hapus sumber?") },
            text = { Text("Sumber ini dan semua elemen yang menggunakannya akan dihapus dari seluruh scene.") },
            confirmButton = {
                TextButton(onClick = {
                    val previousAssets = currentProject.assets
                    val updated = SceneProjectEditor.removeSource(
                        currentProject, selectedSource.id, System.currentTimeMillis()
                    )
                    val retainedAssetIds = updated.assets.mapTo(mutableSetOf()) { it.id }
                    val removedAssets = previousAssets.filterNot { it.id in retainedAssetIds }
                    applyProject(updated)
                    selectedElementId = null
                    confirmRemoveSource = false
                    removedAssets.forEach { asset ->
                        runCatching {
                            importExecutor.execute { runCatching { SceneAssetStorage.deleteAsset(context, asset) } }
                        }
                    }
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSource = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun SceneAssetImage(filePath: String, contentDescription: String, isVideo: Boolean = false) {
    val bitmap = produceState<Bitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            if (isVideo) decodeVideoPreview(filePath) else decodeEditorBitmap(filePath)
        }
    }.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Text("Memuat media…", color = Color.White, textAlign = TextAlign.Center)
    }
}

private fun decodeEditorBitmap(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

private fun decodeVideoPreview(path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            1280,
            1280
        )
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}
