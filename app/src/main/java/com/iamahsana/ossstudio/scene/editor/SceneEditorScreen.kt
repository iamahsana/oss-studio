package com.iamahsana.ossstudio.scene.editor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.consume
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
import com.iamahsana.ossstudio.scene.persistence.SceneProjectAutosave
import com.iamahsana.ossstudio.scene.persistence.SceneProjectRepository
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import kotlin.math.roundToInt

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
    DisposableEffect(autosave, repository) {
        onDispose {
            autosave.close()
            repository.close()
        }
    }

    val latestProject = rememberUpdatedState(currentProject)
    val scene = currentProject.scenes.firstOrNull { it.id == currentProject.activeSceneId }
        ?: currentProject.scenes.firstOrNull()
    val selectedElement = scene?.elements?.firstOrNull { it.id == selectedElementId }
    val selectedSource = currentProject.sources.firstOrNull { it.id == selectedElement?.sourceId }
    var editingText by remember(selectedElementId) { mutableStateOf(selectedSource?.text.orEmpty()) }

    fun applyProject(updated: SceneProject) {
        currentProject = updated
        onProjectChange(updated)
        autosave.schedule(updated)
    }

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
                            .pointerInput(element.id, scale) {
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
                            },
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
                        Text(
                            label,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
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
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { addSource(SceneSourceType.TEXT, "Teks baru") }) { Text("Tambah teks") }
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
                            OutlinedButton(enabled = layerIndex > 0, onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, selectedElement.id, layerIndex - 1, System.currentTimeMillis()
                                ))
                            }) { Text("Turunkan layer") }
                            OutlinedButton(enabled = layerIndex in 0 until orderedElements.lastIndex, onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, selectedElement.id, layerIndex + 1, System.currentTimeMillis()
                                ))
                            }) { Text("Naikkan layer") }
                            OutlinedButton(onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(width = (transform.width + 40f).coerceAtMost(scene.canvas.width - transform.x)),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Lebarkan") }
                            OutlinedButton(onClick = {
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
}
