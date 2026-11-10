package com.iamahsana.ossstudio.scene.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.media.MediaMetadataRetriever
import java.io.File
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.CanvasSize
import com.iamahsana.ossstudio.scene.model.CropRect
import com.iamahsana.ossstudio.scene.model.SceneProjectEditor
import com.iamahsana.ossstudio.scene.model.SceneProjectHistory
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
    var multiSelectMode by remember(project.id) { mutableStateOf(false) }
    var selectedElementIds by remember(project.id) { mutableStateOf<Set<String>>(emptySet()) }
    val history = remember(project.id) { SceneProjectHistory(project) }
    var historyVersion by remember(project.id) { mutableStateOf(0) }
    var showCanvasSettings by remember(project.id) { mutableStateOf(false) }
    var snapEnabled by remember(project.id) { mutableStateOf(true) }
    var gridVisible by remember(project.id) { mutableStateOf(true) }
    var safeAreaVisible by remember(project.id) { mutableStateOf(true) }
    val repository = remember(project.id) {
        SceneProjectRepository(SceneProjectStoreProvider.database(context))
    }
    val autosave = remember(repository) { SceneProjectAutosave(repository) }
    val latestProject = rememberUpdatedState(currentProject)
    fun applyProject(updated: SceneProject, historyGroup: String? = null) {
        history.record(updated, historyGroup)
        historyVersion++
        currentProject = updated
        onProjectChange(updated)
        autosave.schedule(updated)
    }
    fun restoreHistory(updated: SceneProject?) {
        if (updated == null) return
        currentProject = updated
        onProjectChange(updated)
        autosave.schedule(updated)
        selectedElementId = selectedElementId?.takeIf { selectedId ->
            updated.scenes.any { sceneItem -> sceneItem.elements.any { it.id == selectedId } }
        }
        historyVersion++
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
    var viewportZoom by remember(project.id, scene?.id) { mutableStateOf(1f) }
    var viewportPanX by remember(project.id, scene?.id) { mutableStateOf(0f) }
    var viewportPanY by remember(project.id, scene?.id) { mutableStateOf(0f) }
    val selectedElement = scene?.elements?.firstOrNull { it.id == selectedElementId }
    val selectedSource = currentProject.sources.firstOrNull { it.id == selectedElement?.sourceId }
    var editingText by remember(selectedElementId) { mutableStateOf(selectedSource?.text.orEmpty()) }
    var confirmRemoveSource by remember(selectedElementId) { mutableStateOf(false) }

    fun updateTextSetting(key: String, value: String) {
        val source = selectedSource ?: return
        val updatedSource = source.copy(settings = source.settings + (key to value))
        applyProject(SceneProjectEditor.updateSource(currentProject, updatedSource, System.currentTimeMillis()))
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = historyVersion >= 0 && history.canUndo, onClick = {
                history.endGroup()
                restoreHistory(history.undo())
            }) { Text("Undo") }
            OutlinedButton(enabled = historyVersion >= 0 && history.canRedo, onClick = {
                history.endGroup()
                restoreHistory(history.redo())
            }) { Text("Redo") }
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
                OutlinedButton(onClick = { showCanvasSettings = true }) {
                    Text("Pengaturan canvas")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Snap grid")
                    Switch(checked = snapEnabled, onCheckedChange = { snapEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Grid")
                    Switch(checked = gridVisible, onCheckedChange = { gridVisible = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Safe area")
                    Switch(checked = safeAreaVisible, onCheckedChange = { safeAreaVisible = it })
                }
            }
            Text(scene.name, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    viewportZoom = (viewportZoom - 0.25f).coerceAtLeast(1f)
                    if (viewportZoom == 1f) { viewportPanX = 0f; viewportPanY = 0f }
                }, enabled = viewportZoom > 1f) { Text("−") }
                Text("${(viewportZoom * 100).roundToInt()}%", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { viewportZoom = (viewportZoom + 0.25f).coerceAtMost(4f) }, enabled = viewportZoom < 4f) { Text("＋") }
                TextButton(onClick = { viewportZoom = 1f; viewportPanX = 0f; viewportPanY = 0f }) { Text("Reset") }
            }
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(scene.canvas.width.toFloat() / scene.canvas.height.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF17191D))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clipToBounds()
                    .pointerInput(scene.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val pointers = event.changes.filter { it.pressed }.take(2)
                                    if (pointers.size < 2) return@awaitEachGesture
                                    val first = pointers[0]
                                    val second = pointers[1]
                                    val previousCenter = Offset(
                                        (first.previousPosition.x + second.previousPosition.x) / 2f,
                                        (first.previousPosition.y + second.previousPosition.y) / 2f
                                    )
                                    val currentCenter = Offset(
                                        (first.position.x + second.position.x) / 2f,
                                        (first.position.y + second.position.y) / 2f
                                    )
                                    val previousDx = first.previousPosition.x - second.previousPosition.x
                                    val previousDy = first.previousPosition.y - second.previousPosition.y
                                    val currentDx = first.position.x - second.position.x
                                    val currentDy = first.position.y - second.position.y
                                    val previousDistance = kotlin.math.sqrt(previousDx * previousDx + previousDy * previousDy)
                                    val currentDistance = kotlin.math.sqrt(currentDx * currentDx + currentDy * currentDy)
                                    val zoomChange = if (previousDistance > 0.001f) currentDistance / previousDistance else 1f
                                    val pan = currentCenter - previousCenter
                                    val nextZoom = (viewportZoom * zoomChange).coerceIn(1f, 4f)
                                    viewportZoom = nextZoom
                                    if (nextZoom <= 1f) {
                                        viewportPanX = 0f
                                        viewportPanY = 0f
                                    } else {
                                        val maxX = size.width * (nextZoom - 1f) / 2f
                                        val maxY = size.height * (nextZoom - 1f) / 2f
                                        viewportPanX = (viewportPanX + pan.x).coerceIn(-maxX, maxX)
                                        viewportPanY = (viewportPanY + pan.y).coerceIn(-maxY, maxY)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = viewportZoom
                        scaleY = viewportZoom
                        translationX = viewportPanX
                        translationY = viewportPanY
                        transformOrigin = TransformOrigin.Center
                    }
                    .testTag("scene-canvas")
            ) {
                val scale = constraints.maxWidth.toFloat() / scene.canvas.width.toFloat()
                val density = LocalDensity.current
                if (gridVisible || safeAreaVisible) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        if (gridVisible) {
                            val step = DEFAULT_GRID_SIZE * scale
                            var x = step
                            while (x < size.width) {
                                drawLine(
                                    color = Color.White.copy(alpha = 0.12f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                                x += step
                            }
                            var y = step
                            while (y < size.height) {
                                drawLine(
                                    color = Color.White.copy(alpha = 0.12f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1.dp.toPx()
                                )
                                y += step
                            }
                        }
                        if (safeAreaVisible) {
                            val insetX = size.width * 0.05f
                            val insetY = size.height * 0.05f
                            val guide = Color(0xFFFFD54F).copy(alpha = 0.7f)
                            drawLine(guide, Offset(insetX, insetY), Offset(size.width - insetX, insetY), 1.5.dp.toPx())
                            drawLine(guide, Offset(insetX, size.height - insetY), Offset(size.width - insetX, size.height - insetY), 1.5.dp.toPx())
                            drawLine(guide, Offset(insetX, insetY), Offset(insetX, size.height - insetY), 1.5.dp.toPx())
                            drawLine(guide, Offset(size.width - insetX, insetY), Offset(size.width - insetX, size.height - insetY), 1.5.dp.toPx())
                        }
                    }
                }
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
                            .clipToBounds()
                            .graphicsLayer { rotationZ = transform.rotationDegrees }
                            .then(
                                if (element.id == selectedElementId) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                                else Modifier.border(1.dp, Color(0xFF777777))
                            )
                            .background(if (element.id == selectedElementId) Color(0x553B82F6) else Color(0x33222222))
                            .clickable { selectedElementId = element.id }
                            .testTag("scene-canvas-element")
                            .then(
                                if (element.locked) Modifier else Modifier.pointerInput(element.id, scale, snapEnabled) {
                                    var dragX = transform.x
                                    var dragY = transform.y
                                    detectDragGestures(
                                        onDragStart = { selectedElementId = element.id },
                                        onDragEnd = { history.endGroup(); historyVersion++ },
                                        onDragCancel = { history.endGroup(); historyVersion++ },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val nextX = dragX + amount.x / scale
                                            val nextY = dragY + amount.y / scale
                                            dragX = clampSceneCoordinate(
                                                if (snapEnabled) snapSceneCoordinate(nextX) else nextX,
                                                scene.canvas.width.toFloat(),
                                                transform.width
                                            )
                                            dragY = clampSceneCoordinate(
                                                if (snapEnabled) snapSceneCoordinate(nextY) else nextY,
                                                scene.canvas.height.toFloat(),
                                                transform.height
                                            )
                                            val latest = latestProject.value
                                            val latestTransform = latest.scenes
                                                .firstOrNull { it.id == scene.id }
                                                ?.elements?.firstOrNull { it.id == element.id }?.transform
                                                ?: transform
                                            val updated = if (element.groupId != null) {
                                                SceneProjectEditor.translateGroup(
                                                    latest, scene.id, element.groupId,
                                                    dragX - latestTransform.x, dragY - latestTransform.y,
                                                    System.currentTimeMillis()
                                                )
                                            } else {
                                                SceneProjectEditor.updateTransform(
                                                    latest, scene.id, element.id,
                                                    transform.copy(x = dragX, y = dragY),
                                                    System.currentTimeMillis()
                                                )
                                            }
                                            applyProject(updated, "move:" + (element.groupId ?: element.id))
                                        }
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = element.opacity }, contentAlignment = Alignment.Center) {
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
                                    isVideo = source.type == SceneSourceType.VIDEO,
                                    isGif = source.type == SceneSourceType.GIF,
                                    crop = element.crop
                                )
                            } else {
                                Text(label, color = Color.White, textAlign = TextAlign.Center)
                            }
                        } else if (source.type == SceneSourceType.TEXT) {
                            val textColor = runCatching {
                                Color(android.graphics.Color.parseColor(source.settings["color"] ?: "#FFFFFFFF"))
                            }.getOrDefault(Color.White)
                            val textSize = source.settings["fontSize"]?.toFloatOrNull() ?: 48f
                            val alignment = when (source.settings["align"]) {
                                "left" -> TextAlign.Start
                                "right" -> TextAlign.End
                                else -> TextAlign.Center
                            }
                            Text(
                                label.ifBlank { "Teks" },
                                color = textColor,
                                textAlign = alignment,
                                fontWeight = if (source.settings["bold"] == "true") FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (source.settings["italic"] == "true") FontStyle.Italic else FontStyle.Normal,
                                fontSize = ((textSize * scale) / density.density).sp
                            )
                        } else {
                            Text(
                                label,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        }
                        if (element.id == selectedElementId && !element.locked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .testTag("scene-resize-handle")
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(element.id, scale, scene.id) {
                                        var resizeWidth = transform.width
                                        var resizeHeight = transform.height
                                        detectDragGestures(
                                            onDragEnd = { history.endGroup(); historyVersion++ },
                                            onDragCancel = { history.endGroup(); historyVersion++ }
                                        ) { change, amount ->
                                            change.consume()
                                            resizeWidth = (resizeWidth + amount.x / scale)
                                                .coerceIn(16f, (scene.canvas.width - transform.x).coerceAtLeast(16f))
                                            resizeHeight = (resizeHeight + amount.y / scale)
                                                .coerceIn(16f, (scene.canvas.height - transform.y).coerceAtLeast(16f))
                                            val updated = SceneProjectEditor.updateTransform(
                                                latestProject.value,
                                                scene.id,
                                                element.id,
                                                transform.copy(width = resizeWidth, height = resizeHeight),
                                                System.currentTimeMillis()
                                            )
                                            applyProject(updated, "resize:${element.id}")
                                        }
                                    }
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
                        Text("Opacity: ${(selectedElement.opacity * 100f).toInt()}%")
                        Slider(
                            value = selectedElement.opacity,
                            onValueChange = {
                                applyProject(SceneProjectEditor.setElementOpacity(
                                    currentProject, scene.id, selectedElement.id, it, System.currentTimeMillis()
                                ))
                            },
                            valueRange = 0f..1f
                        )
                        if (selectedSource.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF, SceneSourceType.VIDEO)) {
                            val crop = selectedElement.crop
                            Text("Crop media", style = MaterialTheme.typography.labelLarge)
                            Text("Kiri: ${(crop.left * 100f).toInt()}%")
                            val minGapX = minOf(0.01f, (crop.right - crop.left) / 2f)
                            val maxLeft = crop.right - minGapX
                            Slider(
                                value = crop.left.coerceIn(0f, maxLeft),
                                onValueChange = { value ->
                                    applyProject(SceneProjectEditor.updateElementCrop(
                                        currentProject, scene.id, selectedElement.id,
                                        crop.copy(left = value.coerceAtMost(maxLeft)), System.currentTimeMillis()
                                    ))
                                },
                                valueRange = 0f..maxLeft
                            )
                            Text("Kanan: ${(crop.right * 100f).toInt()}%")
                            val minRight = crop.left + minGapX
                            Slider(
                                value = crop.right.coerceIn(minRight, 1f),
                                onValueChange = { value ->
                                    applyProject(SceneProjectEditor.updateElementCrop(
                                        currentProject, scene.id, selectedElement.id,
                                        crop.copy(right = value.coerceAtLeast(minRight)), System.currentTimeMillis()
                                    ))
                                },
                                valueRange = minRight..1f
                            )
                            Text("Atas: ${(crop.top * 100f).toInt()}%")
                            val minGapY = minOf(0.01f, (crop.bottom - crop.top) / 2f)
                            val maxTop = crop.bottom - minGapY
                            Slider(
                                value = crop.top.coerceIn(0f, maxTop),
                                onValueChange = { value ->
                                    applyProject(SceneProjectEditor.updateElementCrop(
                                        currentProject, scene.id, selectedElement.id,
                                        crop.copy(top = value.coerceAtMost(maxTop)), System.currentTimeMillis()
                                    ))
                                },
                                valueRange = 0f..maxTop
                            )
                            Text("Bawah: ${(crop.bottom * 100f).toInt()}%")
                            val minBottom = crop.top + minGapY
                            Slider(
                                value = crop.bottom.coerceIn(minBottom, 1f),
                                onValueChange = { value ->
                                    applyProject(SceneProjectEditor.updateElementCrop(
                                        currentProject, scene.id, selectedElement.id,
                                        crop.copy(bottom = value.coerceAtLeast(minBottom)), System.currentTimeMillis()
                                    ))
                                },
                                valueRange = minBottom..1f
                            )
                            OutlinedButton(onClick = {
                                applyProject(SceneProjectEditor.updateElementCrop(
                                    currentProject, scene.id, selectedElement.id, CropRect(), System.currentTimeMillis()
                                ))
                            }) { Text("Reset crop") }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Sumber aktif di semua scene", modifier = Modifier.weight(1f))
                            Switch(
                                checked = selectedSource.enabled,
                                onCheckedChange = { enabled ->
                                    applyProject(SceneProjectEditor.updateSource(
                                        currentProject, selectedSource.copy(enabled = enabled), System.currentTimeMillis()
                                    ))
                                }
                            )
                        }
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
                            val fontSize = selectedSource.settings["fontSize"]?.toFloatOrNull() ?: 48f
                            Text("Ukuran font: ${fontSize.toInt()} px")
                            Slider(
                                value = fontSize.coerceIn(16f, 128f),
                                onValueChange = { updateTextSetting("fontSize", it.toInt().toString()) },
                                valueRange = 16f..128f,
                                steps = 55
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { updateTextSetting("color", "#FFFFFFFF") }) { Text("Putih") }
                                OutlinedButton(onClick = { updateTextSetting("color", "#FFFFD54F") }) { Text("Kuning") }
                                OutlinedButton(onClick = { updateTextSetting("color", "#FF4DD0E1") }) { Text("Cyan") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { updateTextSetting("align", "left") }) { Text("Kiri") }
                                OutlinedButton(onClick = { updateTextSetting("align", "center") }) { Text("Tengah") }
                                OutlinedButton(onClick = { updateTextSetting("align", "right") }) { Text("Kanan") }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Tebal", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = selectedSource.settings["bold"] == "true",
                                    onCheckedChange = { updateTextSetting("bold", it.toString()) }
                                )
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Miring", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = selectedSource.settings["italic"] == "true",
                                    onCheckedChange = { updateTextSetting("italic", it.toString()) }
                                )
                            }
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
                            OutlinedButton(enabled = !selectedElement.locked && selectedElement.transform.width > 16f, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(width = (transform.width - 40f).coerceAtLeast(16f)),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Persempit") }
                            OutlinedButton(enabled = !selectedElement.locked && selectedElement.transform.height > 16f, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(height = (transform.height - 40f).coerceAtLeast(16f)),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Pendekkan") }
                            OutlinedButton(enabled = !selectedElement.locked, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(rotationDegrees = transform.rotationDegrees - 15f),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Putar ↺") }
                            OutlinedButton(enabled = !selectedElement.locked, onClick = {
                                val transform = selectedElement.transform
                                applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    transform.copy(rotationDegrees = transform.rotationDegrees + 15f),
                                    System.currentTimeMillis()
                                ))
                            }) { Text("Putar ↻") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val duplicated = SceneProjectEditor.duplicateElement(
                                    currentProject, scene.id, selectedElement.id, System.currentTimeMillis()
                                )
                                selectedElementId = duplicated.scenes.first { it.id == scene.id }.elements.last().id
                                applyProject(duplicated)
                            }) { Text("Duplikat") }
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
            // Keep the mode toggle outside the horizontal action scroller.
            // A horizontally scrollable parent was preventing reliable taps in
            // the emulator accessibility test and is also harder to use on phones.
            OutlinedButton(
                modifier = Modifier.testTag("scene-multiselect-toggle"),
                onClick = {
                    val enteringMultiSelect = !multiSelectMode
                    multiSelectMode = enteringMultiSelect
                    selectedElementIds = if (enteringMultiSelect) emptySet() else selectedElementIds
                }
            ) { Text(if (multiSelectMode) "Selesai memilih" else "Pilih beberapa") }
            val selectedGroupIds = scene.elements
                .filter { it.id in selectedElementIds }
                .mapNotNull { it.groupId }
                .distinct()
            val canGroup = selectedElementIds.size >= 2 &&
                scene.elements.filter { it.id in selectedElementIds }.all { it.groupId == null }
            if (canGroup || selectedGroupIds.size == 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canGroup) {
                        OutlinedButton(onClick = {
                            val updated = SceneProjectEditor.groupElements(
                                currentProject, scene.id, selectedElementIds, System.currentTimeMillis()
                            )
                            applyProject(updated)
                            multiSelectMode = false
                            selectedElementId = selectedElementIds.firstOrNull()
                            selectedElementIds = selectedElementId?.let(::setOf) ?: emptySet()
                        }) { Text("Kelompokkan (" + selectedElementIds.size + ")") }
                    }
                    if (selectedGroupIds.size == 1) {
                        OutlinedButton(onClick = {
                            applyProject(SceneProjectEditor.ungroupElements(
                                currentProject, scene.id, selectedGroupIds.single(), System.currentTimeMillis()
                            ))
                            multiSelectMode = false
                            selectedElementIds = selectedElementId?.let(::setOf) ?: emptySet()
                        }) { Text("Pisahkan grup") }
                    }
                }
            }
            if (multiSelectMode) {
                Text(
                    "Mode pemilihan aktif · Dipilih: ${selectedElementIds.size}",
                    modifier = Modifier.testTag("scene-multiselect-status")
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scene.elements.sortedBy { it.zIndex }.forEach { element ->
                    val source = currentProject.sources.firstOrNull { it.id == element.sourceId }
                    val chosen = element.id in selectedElementIds
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().testTag("scene-element-item"),
                        onClick = {
                            if (multiSelectMode) {
                                selectedElementIds = if (chosen) {
                                    selectedElementIds - element.id
                                } else {
                                    selectedElementIds + element.id
                                }
                                selectedElementId = element.id
                            } else {
                                selectedElementId = element.id
                                selectedElementIds = setOf(element.id)
                            }
                            editingText = source?.text.orEmpty()
                        }
                    ) {
                        Text((if (chosen && multiSelectMode) "✓ " else "") + (source?.displayName ?: "Element"))
                    }
                }
            }
        }
    }

    if (showCanvasSettings && scene != null) {
        AlertDialog(
            onDismissRequest = { showCanvasSettings = false },
            title = { Text("Pengaturan canvas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pilih orientasi dan rasio untuk scene aktif.")
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            applyProject(SceneProjectEditor.updateSceneCanvas(
                                currentProject, scene.id, CanvasSize.DEFAULT_PORTRAIT, System.currentTimeMillis()
                            ))
                            showCanvasSettings = false
                        }
                    ) { Text("Portrait · 9:16 · 1080 × 1920") }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            applyProject(SceneProjectEditor.updateSceneCanvas(
                                currentProject, scene.id, CanvasSize.DEFAULT_LANDSCAPE, System.currentTimeMillis()
                            ))
                            showCanvasSettings = false
                        }
                    ) { Text("Landscape · 16:9 · 1920 × 1080") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCanvasSettings = false }) { Text("Tutup") }
            }
        )
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
private fun SceneAssetImage(
    filePath: String,
    contentDescription: String,
    isVideo: Boolean = false,
    isGif: Boolean = false,
    crop: CropRect = CropRect()
) {
    if (isGif) {
        SceneAnimatedGif(filePath, contentDescription, crop)
        return
    }
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
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val cropWidth = (crop.right - crop.left).coerceAtLeast(0.001f)
                val cropHeight = (crop.bottom - crop.top).coerceAtLeast(0.001f)
                scaleX = 1f / cropWidth
                scaleY = 1f / cropHeight
                translationX = ((0.5f - (crop.left + crop.right) / 2f) / cropWidth) * size.width
                translationY = ((0.5f - (crop.top + crop.bottom) / 2f) / cropHeight) * size.height
            }
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

@Composable
private fun SceneAnimatedGif(filePath: String, contentDescription: String, crop: CropRect) {
    val context = LocalContext.current
    val drawable = produceState<Drawable?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(filePath)))
            }.getOrNull()
        }
    }.value
    if (drawable is AnimatedImageDrawable) {
        DisposableEffect(drawable) {
            drawable.start()
            onDispose { drawable.stop() }
        }
    }
    if (drawable != null) {
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    this.contentDescription = contentDescription
                    setImageDrawable(drawable)
                }
            },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val cropWidth = (crop.right - crop.left).coerceAtLeast(0.001f)
                val cropHeight = (crop.bottom - crop.top).coerceAtLeast(0.001f)
                scaleX = 1f / cropWidth
                scaleY = 1f / cropHeight
                translationX = ((0.5f - (crop.left + crop.right) / 2f) / cropWidth) * size.width
                translationY = ((0.5f - (crop.top + crop.bottom) / 2f) / cropHeight) * size.height
            }
        )
    } else {
        Text("GIF tidak dapat dimuat", color = Color.White, textAlign = TextAlign.Center)
    }
}
