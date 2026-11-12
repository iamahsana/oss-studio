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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamahsana.ossstudio.extensions.OssInstalledElement
import com.iamahsana.ossstudio.extensions.OssInstalledElementCatalog
import com.iamahsana.ossstudio.scene.model.ElementTransform
import com.iamahsana.ossstudio.scene.model.ElementBoundsAlignment
import com.iamahsana.ossstudio.scene.model.alignTransformsByRotatedBounds
import com.iamahsana.ossstudio.scene.model.distributeTransformsByRotatedBounds
import com.iamahsana.ossstudio.scene.model.clampedToCanvas
import com.iamahsana.ossstudio.scene.model.rotatedBounds
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.Scene
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var extensionCatalogRevision by remember(context.filesDir) { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) extensionCatalogRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val installedElements by produceState(
        initialValue = emptyList<OssInstalledElement>(),
        key1 = context.filesDir,
        key2 = extensionCatalogRevision
    ) {
        value = withContext(Dispatchers.IO) {
            OssInstalledElementCatalog.listInstalled(File(context.filesDir, "extensions"))
        }
    }
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
    fun openCanvasSettings() {
        showCanvasSettings = true
    }
    var viewportZoom by remember(project.id, scene?.id) { mutableStateOf(1f) }
    var viewportPanX by remember(project.id, scene?.id) { mutableStateOf(0f) }
    var viewportPanY by remember(project.id, scene?.id) { mutableStateOf(0f) }
    val selectedElement = scene?.elements?.firstOrNull { it.id == selectedElementId }
    val selectedSource = currentProject.sources.firstOrNull { it.id == selectedElement?.sourceId }
    var editingText by remember(selectedElementId) { mutableStateOf(selectedSource?.text ?: selectedSource?.settings?.get("text").orEmpty()) }
    var confirmRemoveSource by remember(selectedElementId) { mutableStateOf(false) }
    var editorPanel by remember(project.id) { mutableStateOf("layers") }

    fun updateTextSettings(values: Map<String, String?>) {
        if (selectedElement?.locked == true) return
        val sourceId = selectedSource?.id ?: return
        val source = currentProject.sources.firstOrNull { it.id == sourceId } ?: return
        val settings = source.settings.toMutableMap()
        values.forEach { (key, value) ->
            if (value == null) settings.remove(key) else settings[key] = value
        }
        val updatedSource = source.copy(settings = settings)
        applyProject(SceneProjectEditor.updateSource(currentProject, updatedSource, System.currentTimeMillis()))
    }

    fun updateTextSetting(key: String, value: String) {
        updateTextSettings(mapOf(key to value))
    }

    fun addSource(type: SceneSourceType, text: String? = null) {
        val activeScene = scene ?: return
        val source = SceneSource(
            type = type,
            displayName = when (type) {
                SceneSourceType.TEXT -> "Text"
                SceneSourceType.SHARE_SCREEN -> "Share Screen"
                SceneSourceType.EXTENSION -> "Extension element"
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

    fun addExtensionElement(element: OssInstalledElement) {
        val activeScene = scene ?: return
        if (element.kind !in setOf("text", "image", "sticker", "shape", "background", "gif", "video")) return
        val source = SceneSource(
            type = SceneSourceType.EXTENSION,
            displayName = element.name,
            settings = element.defaults + ("extensionKind" to element.kind),
            extensionId = element.extensionId,
            typeId = element.typeId
        )
        val timestamp = System.currentTimeMillis()
        val withSource = SceneProjectEditor.addSource(currentProject, source, timestamp)
        val added = SceneProjectEditor.addElement(withSource, activeScene.id, source.id, timestamp)
        val newElementId = added.scenes.first { it.id == activeScene.id }.elements.last().id
        val updated = if (element.kind == "background") {
            SceneProjectEditor.updateTransform(
                added,
                activeScene.id,
                newElementId,
                ElementTransform(
                    x = 0f,
                    y = 0f,
                    width = activeScene.canvas.width.toFloat(),
                    height = activeScene.canvas.height.toFloat()
                ),
                timestamp
            )
        } else added
        selectedElementId = newElementId
        selectedElementIds = setOf(newElementId)
        editorPanel = "layers"
        applyProject(updated)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .verticalScroll(rememberScrollState())
            .testTag("scene-editor-scroll"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                OutlinedButton(onClick = { openCanvasSettings() }) {
                    Text("Pengaturan canvas")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Snap grid")
                    Switch(checked = snapEnabled, onCheckedChange = { snapEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grid")
                    Switch(checked = gridVisible, onCheckedChange = { gridVisible = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Safe area")
                    Switch(checked = safeAreaVisible, onCheckedChange = { safeAreaVisible = it })
                }
            }
            Text(scene.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${scene.canvas.width} × ${scene.canvas.height} px",
                modifier = Modifier.testTag("scene-canvas-dimensions"),
                style = MaterialTheme.typography.labelSmall
            )
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
                                            val candidate = transform.copy(
                                                x = if (snapEnabled) snapSceneCoordinate(nextX) else nextX,
                                                y = if (snapEnabled) snapSceneCoordinate(nextY) else nextY
                                            ).clampedToCanvas(scene.canvas)
                                            dragX = candidate.x
                                            dragY = candidate.y
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
                            SceneSourceType.EXTENSION -> source.displayName
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
                            val customFontFamily = remember(
                                source.id,
                                source.settings["fontExtensionId"],
                                source.settings["fontTypeId"]
                            ) {
                                val font = installedElements.firstOrNull {
                                    it.kind == "font" &&
                                        it.extensionId == source.settings["fontExtensionId"] &&
                                        it.typeId == source.settings["fontTypeId"]
                                }
                                font?.resolveResource()?.let { file ->
                                    runCatching {
                                        FontFamily(android.graphics.Typeface.createFromFile(file))
                                    }.getOrNull()
                                }
                            }
                            val textColor = runCatching {
                                Color(android.graphics.Color.parseColor(source.settings["color"] ?: "#FFFFFFFF"))
                            }.getOrDefault(Color.White)
                            val textSize = source.settings["fontSize"]?.toFloatOrNull() ?: 48f
                            val decoration = when {
                                source.settings["underline"] == "true" && source.settings["strikethrough"] == "true" ->
                                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                source.settings["underline"] == "true" -> TextDecoration.Underline
                                source.settings["strikethrough"] == "true" -> TextDecoration.LineThrough
                                else -> TextDecoration.None
                            }
                            val shadowRadius = (source.settings["shadowRadius"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 32f)
                            val shadowColor = runCatching {
                                Color(android.graphics.Color.parseColor(source.settings["shadowColor"] ?: "#FF000000"))
                            }.getOrDefault(Color.Black)
                            val letterSpacing = (source.settings["letterSpacing"]?.toFloatOrNull() ?: 0f).coerceIn(-0.1f, 0.5f)
                            val lineSpacing = (source.settings["lineSpacing"]?.toFloatOrNull() ?: 1.15f).coerceIn(1f, 2f)
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
                                fontFamily = customFontFamily ?: when (source.settings["fontFamily"]) {
                                    "serif" -> FontFamily.Serif
                                    "monospace" -> FontFamily.Monospace
                                    else -> FontFamily.Default
                                },
                                fontSize = ((textSize * scale) / density.density).sp,
                                letterSpacing = (letterSpacing * textSize * scale / density.density).sp,
                                lineHeight = (textSize * lineSpacing * scale / density.density).sp,
                                textDecoration = decoration,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    shadow = if (shadowRadius > 0f) Shadow(
                                        color = shadowColor,
                                        offset = Offset(2f * scale, 2f * scale),
                                        blurRadius = shadowRadius * scale
                                    ) else null
                                )
                            )
                        } else if (source.type == SceneSourceType.EXTENSION) {
                            val extensionElement = installedElements.firstOrNull {
                                it.extensionId == source.extensionId && it.typeId == source.typeId
                            }
                            val resource = extensionElement?.resolveResource()
                            val extensionKind = extensionElement?.kind
                            when (extensionKind) {
                                "text" -> {
                                    val customFontFamily = remember(
                                        source.id,
                                        source.settings["fontExtensionId"],
                                        source.settings["fontTypeId"]
                                    ) {
                                        val font = installedElements.firstOrNull {
                                            it.kind == "font" &&
                                                it.extensionId == source.settings["fontExtensionId"] &&
                                                it.typeId == source.settings["fontTypeId"]
                                        }
                                        font?.resolveResource()?.let { file ->
                                            runCatching {
                                                FontFamily(android.graphics.Typeface.createFromFile(file))
                                            }.getOrNull()
                                        }
                                    }
                                    val textColor = runCatching {
                                        Color(android.graphics.Color.parseColor(source.settings["color"] ?: "#FFFFFFFF"))
                                    }.getOrDefault(Color.White)
                                    val textSize = source.settings["fontSize"]?.toFloatOrNull() ?: 48f
                            val decoration = when {
                                source.settings["underline"] == "true" && source.settings["strikethrough"] == "true" ->
                                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                source.settings["underline"] == "true" -> TextDecoration.Underline
                                source.settings["strikethrough"] == "true" -> TextDecoration.LineThrough
                                else -> TextDecoration.None
                            }
                            val shadowRadius = (source.settings["shadowRadius"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 32f)
                            val shadowColor = runCatching {
                                Color(android.graphics.Color.parseColor(source.settings["shadowColor"] ?: "#FF000000"))
                            }.getOrDefault(Color.Black)
                            val letterSpacing = (source.settings["letterSpacing"]?.toFloatOrNull() ?: 0f).coerceIn(-0.1f, 0.5f)
                            val lineSpacing = (source.settings["lineSpacing"]?.toFloatOrNull() ?: 1.15f).coerceIn(1f, 2f)
                                    val alignment = when (source.settings["align"]) {
                                        "left" -> TextAlign.Start
                                        "right" -> TextAlign.End
                                        else -> TextAlign.Center
                                    }
                                    Text(
                                        source.settings["text"] ?: source.displayName,
                                        color = textColor,
                                        textAlign = alignment,
                                        fontWeight = if (source.settings["bold"] == "true") FontWeight.Bold else FontWeight.Normal,
                                        fontStyle = if (source.settings["italic"] == "true") FontStyle.Italic else FontStyle.Normal,
                                        fontFamily = customFontFamily ?: FontFamily.Default,
                                        fontSize = ((textSize * scale) / density.density).sp,
                                        letterSpacing = (letterSpacing * textSize * scale / density.density).sp,
                                        lineHeight = (textSize * lineSpacing * scale / density.density).sp,
                                        textDecoration = decoration,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            shadow = if (shadowRadius > 0f) Shadow(
                                                color = shadowColor,
                                                offset = Offset(2f * scale, 2f * scale),
                                                blurRadius = shadowRadius * scale
                                            ) else null
                                        )
                                    )
                                }
                                "image", "sticker", "gif", "video", "background" -> {
                                    if (resource != null) {
                                        SceneAssetImage(
                                            filePath = resource.absolutePath,
                                            contentDescription = source.displayName,
                                            isVideo = extensionKind == "video",
                                            isGif = extensionKind == "gif",
                                            crop = element.crop
                                        )
                                    } else {
                                        Text("Resource ekstensi tidak tersedia", color = Color.White)
                                    }
                                }
                                "shape" -> {
                                    val shapeColor = runCatching {
                                        Color(android.graphics.Color.parseColor(
                                            source.settings["fillColor"] ?: source.settings["color"] ?: "#FFFFFFFF"
                                        ))
                                    }.getOrDefault(Color.White)
                                    val shape = when (source.settings["shape"]?.lowercase()) {
                                        "circle", "ellipse", "oval" -> RoundedCornerShape(50)
                                        "rounded-rect", "roundrect" -> RoundedCornerShape(
                                            (source.settings["cornerRadius"]?.toFloatOrNull() ?: 18f).coerceIn(0f, 256f).dp
                                        )
                                        else -> RoundedCornerShape(0.dp)
                                    }
                                    Box(Modifier.fillMaxSize().background(shapeColor, shape))
                                }
                                else -> Text("Ekstensi tidak tersedia", color = Color.White)
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
                                                transform.copy(width = resizeWidth, height = resizeHeight).clampedToCanvas(scene.canvas),
                                                System.currentTimeMillis()
                                            )
                                            applyProject(updated, "resize:${element.id}")
                                        }
                                    }
                            )
                        }
                        if (element.id == selectedElementId && !element.locked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .size(18.dp)
                                    .testTag("scene-resize-handle-top-start")
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(element.id, scale, scene.id) {
                                        var resizeX = transform.x
                                        var resizeY = transform.y
                                        var resizeWidth = transform.width
                                        var resizeHeight = transform.height
                                        val fixedRight = transform.x + transform.width
                                        val fixedBottom = transform.y + transform.height
                                        detectDragGestures(
                                            onDragEnd = { history.endGroup(); historyVersion++ },
                                            onDragCancel = { history.endGroup(); historyVersion++ }
                                        ) { change, amount ->
                                            change.consume()
                                            resizeX = (resizeX + amount.x / scale)
                                                .coerceIn(0f, (fixedRight - 16f).coerceAtLeast(0f))
                                            resizeY = (resizeY + amount.y / scale)
                                                .coerceIn(0f, (fixedBottom - 16f).coerceAtLeast(0f))
                                            resizeWidth = (fixedRight - resizeX).coerceAtLeast(16f)
                                            resizeHeight = (fixedBottom - resizeY).coerceAtLeast(16f)
                                            val updated = SceneProjectEditor.updateTransform(
                                                latestProject.value,
                                                scene.id,
                                                element.id,
                                                transform.copy(
                                                    x = resizeX,
                                                    y = resizeY,
                                                    width = resizeWidth,
                                                    height = resizeHeight
                                                ).clampedToCanvas(scene.canvas),
                                                System.currentTimeMillis()
                                            )
                                            applyProject(updated, "resize:${element.id}")
                                        }
                                    }
                            )
                        }
                        if (element.id == selectedElementId && !element.locked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .testTag("scene-resize-handle-top-end")
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(element.id, scale, scene.id) {
                                        var resizeY = transform.y
                                        var resizeWidth = transform.width
                                        var resizeHeight = transform.height
                                        val fixedBottom = transform.y + transform.height
                                        detectDragGestures(
                                            onDragEnd = { history.endGroup(); historyVersion++ },
                                            onDragCancel = { history.endGroup(); historyVersion++ }
                                        ) { change, amount ->
                                            change.consume()
                                            resizeWidth = (resizeWidth + amount.x / scale)
                                                .coerceIn(16f, (scene.canvas.width - transform.x).coerceAtLeast(16f))
                                            resizeY = (resizeY + amount.y / scale)
                                                .coerceIn(0f, (fixedBottom - 16f).coerceAtLeast(0f))
                                            resizeHeight = (fixedBottom - resizeY).coerceAtLeast(16f)
                                            val updated = SceneProjectEditor.updateTransform(
                                                latestProject.value,
                                                scene.id,
                                                element.id,
                                                transform.copy(y = resizeY, width = resizeWidth, height = resizeHeight).clampedToCanvas(scene.canvas),
                                                System.currentTimeMillis()
                                            )
                                            applyProject(updated, "resize:${element.id}")
                                        }
                                    }
                            )
                        }
                        if (element.id == selectedElementId && !element.locked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .size(18.dp)
                                    .testTag("scene-resize-handle-bottom-start")
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .pointerInput(element.id, scale, scene.id) {
                                        var resizeX = transform.x
                                        var resizeWidth = transform.width
                                        var resizeHeight = transform.height
                                        val fixedRight = transform.x + transform.width
                                        detectDragGestures(
                                            onDragEnd = { history.endGroup(); historyVersion++ },
                                            onDragCancel = { history.endGroup(); historyVersion++ }
                                        ) { change, amount ->
                                            change.consume()
                                            resizeX = (resizeX + amount.x / scale)
                                                .coerceIn(0f, (fixedRight - 16f).coerceAtLeast(0f))
                                            resizeWidth = (fixedRight - resizeX).coerceAtLeast(16f)
                                            resizeHeight = (resizeHeight + amount.y / scale)
                                                .coerceIn(16f, (scene.canvas.height - transform.y).coerceAtLeast(16f))
                                            val updated = SceneProjectEditor.updateTransform(
                                                latestProject.value,
                                                scene.id,
                                                element.id,
                                                transform.copy(x = resizeX, width = resizeWidth, height = resizeHeight).clampedToCanvas(scene.canvas),
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
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("scene-element-insertion-shelf"),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Tambah elemen", style = MaterialTheme.typography.titleSmall)
                    Button(
                        modifier = Modifier.fillMaxWidth().testTag("scene-add-text"),
                        onClick = { addSource(SceneSourceType.TEXT, "Teks baru") }
                    ) { Text("Tambah teks") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).testTag("scene-add-media"),
                            onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) }
                        ) { Text("Media") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).testTag("scene-add-share-screen"),
                            onClick = { addSource(SceneSourceType.SHARE_SCREEN) }
                        ) { Text("Share Screen") }
                    }
                    val insertableExtensions = installedElements.filter {
                        it.kind in setOf("text", "image", "sticker", "shape", "background", "gif", "video")
                    }
                    if (insertableExtensions.isNotEmpty()) {
                        Text("Elemen ekstensi", style = MaterialTheme.typography.labelLarge)
                        insertableExtensions.forEach { extensionElement ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().testTag("scene-add-extension-element"),
                                onClick = { addExtensionElement(extensionElement) }
                            ) {
                                Text("${extensionElement.name} · ${extensionElement.kind}")
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.testTag("scene-panel-layers"),
                        onClick = { editorPanel = "layers" }
                    ) { Text("Lapisan") }
                    OutlinedButton(
                        modifier = Modifier.testTag("scene-panel-properties"),
                        enabled = selectedElement != null && selectedSource != null,
                        onClick = { editorPanel = "properties" }
                    ) { Text("Properti") }
                    OutlinedButton(onClick = { openCanvasSettings() }) { Text("Canvas") }
                }
            }
            if (editorPanel == "properties" && selectedElement != null && selectedSource != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("scene-properties-panel"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Elemen dipilih: ${selectedSource.displayName}", style = MaterialTheme.typography.titleSmall)
                        val elementTransform = selectedElement.transform
                        Text("Posisi horizontal: ${elementTransform.x.toInt()} px")
                        Slider(
                            modifier = Modifier.testTag("scene-transform-x"),
                            value = elementTransform.x.coerceIn(0f, (scene.canvas.width - elementTransform.width).coerceAtLeast(0f)),
                            onValueChange = { value ->
                                if (!selectedElement.locked) applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    elementTransform.copy(x = value), System.currentTimeMillis()
                                ))
                            },
                            valueRange = 0f..(scene.canvas.width - elementTransform.width).coerceAtLeast(0f),
                            enabled = !selectedElement.locked
                        )
                        Text("Posisi vertikal: ${elementTransform.y.toInt()} px")
                        Slider(
                            modifier = Modifier.testTag("scene-transform-y"),
                            value = elementTransform.y.coerceIn(0f, (scene.canvas.height - elementTransform.height).coerceAtLeast(0f)),
                            onValueChange = { value ->
                                if (!selectedElement.locked) applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    elementTransform.copy(y = value), System.currentTimeMillis()
                                ))
                            },
                            valueRange = 0f..(scene.canvas.height - elementTransform.height).coerceAtLeast(0f),
                            enabled = !selectedElement.locked
                        )
                        Text("Lebar: ${elementTransform.width.toInt()} px")
                        Slider(
                            modifier = Modifier.testTag("scene-transform-width"),
                            value = elementTransform.width.coerceIn(16f, scene.canvas.width.toFloat()),
                            onValueChange = { value ->
                                if (!selectedElement.locked) {
                                    val x = elementTransform.x.coerceAtMost((scene.canvas.width - value).coerceAtLeast(0f))
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(x = x, width = value), System.currentTimeMillis()
                                    ))
                                }
                            },
                            valueRange = 16f..scene.canvas.width.toFloat(),
                            enabled = !selectedElement.locked
                        )
                        Text("Tinggi: ${elementTransform.height.toInt()} px")
                        Slider(
                            modifier = Modifier.testTag("scene-transform-height"),
                            value = elementTransform.height.coerceIn(16f, scene.canvas.height.toFloat()),
                            onValueChange = { value ->
                                if (!selectedElement.locked) {
                                    val y = elementTransform.y.coerceAtMost((scene.canvas.height - value).coerceAtLeast(0f))
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(y = y, height = value), System.currentTimeMillis()
                                    ))
                                }
                            },
                            valueRange = 16f..scene.canvas.height.toFloat(),
                            enabled = !selectedElement.locked
                        )
                        Text("Rotasi: ${elementTransform.rotationDegrees.toInt()}°")
                        Slider(
                            modifier = Modifier.testTag("scene-transform-rotation"),
                            value = ((elementTransform.rotationDegrees % 360f) + 360f) % 360f,
                            onValueChange = { value ->
                                if (!selectedElement.locked) applyProject(SceneProjectEditor.updateTransform(
                                    currentProject, scene.id, selectedElement.id,
                                    elementTransform.copy(rotationDegrees = value), System.currentTimeMillis()
                                ))
                            },
                            valueRange = 0f..360f,
                            // 359 intermediate steps provide one-degree rotation increments.
                            steps = 359,
                            enabled = !selectedElement.locked
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-rotate-left-90"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    val next = ((elementTransform.rotationDegrees - 90f) % 360f + 360f) % 360f
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(rotationDegrees = next), System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Putar -90°") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-rotate-right-90"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    val next = ((elementTransform.rotationDegrees + 90f) % 360f + 360f) % 360f
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(rotationDegrees = next), System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Putar +90°") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-rotate-reset"),
                                enabled = !selectedElement.locked && elementTransform.rotationDegrees != 0f,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(rotationDegrees = 0f), System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Reset") }
                        }
                        Text("Perataan ke canvas", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-left"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(x = elementTransform.x - elementTransform.rotatedBounds().left), System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Kiri") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-center-horizontal"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(x = elementTransform.x + (scene.canvas.width - elementTransform.rotatedBounds().width) / 2f - elementTransform.rotatedBounds().left),
                                        System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Tengah") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-right"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(x = elementTransform.x + scene.canvas.width - elementTransform.rotatedBounds().right),
                                        System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Kanan") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-top"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(y = elementTransform.y - elementTransform.rotatedBounds().top), System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Atas") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-center-vertical"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(y = elementTransform.y + (scene.canvas.height - elementTransform.rotatedBounds().height) / 2f - elementTransform.rotatedBounds().top),
                                        System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Tengah") }
                            OutlinedButton(
                                modifier = Modifier.testTag("scene-align-bottom"),
                                enabled = !selectedElement.locked,
                                onClick = {
                                    applyProject(SceneProjectEditor.updateTransform(
                                        currentProject, scene.id, selectedElement.id,
                                        elementTransform.copy(y = elementTransform.y + scene.canvas.height - elementTransform.rotatedBounds().bottom),
                                        System.currentTimeMillis()
                                    ))
                                }
                            ) { Text("Bawah") }
                        }
                        Text("Opacity: ${(selectedElement.opacity * 100f).toInt()}%")
                        Slider(
                            value = selectedElement.opacity,
                            onValueChange = {
                                if (!selectedElement.locked) applyProject(SceneProjectEditor.setElementOpacity(
                                    currentProject, scene.id, selectedElement.id, it, System.currentTimeMillis()
                                ))
                            },
                            valueRange = 0f..1f,
                            enabled = !selectedElement.locked
                        )
                        val selectedExtensionMedia = selectedSource.type == SceneSourceType.EXTENSION &&
                            installedElements.any {
                                it.extensionId == selectedSource.extensionId &&
                                    it.typeId == selectedSource.typeId &&
                                    it.kind in setOf("image", "sticker", "gif", "video", "background")
                            }
                        if (selectedSource.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF, SceneSourceType.VIDEO) ||
                            selectedExtensionMedia
                        ) {
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
                                valueRange = 0f..maxLeft,
                                enabled = !selectedElement.locked
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
                                valueRange = minRight..1f,
                                enabled = !selectedElement.locked
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
                                valueRange = 0f..maxTop,
                                enabled = !selectedElement.locked
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
                                valueRange = minBottom..1f,
                                enabled = !selectedElement.locked
                            )
                            OutlinedButton(enabled = !selectedElement.locked, onClick = {
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
                        val selectedExtensionText = selectedSource.type == SceneSourceType.EXTENSION &&
                            installedElements.any {
                                it.extensionId == selectedSource.extensionId &&
                                    it.typeId == selectedSource.typeId && it.kind == "text"
                            }
                        if (selectedSource.type == SceneSourceType.TEXT || selectedExtensionText) {
                            if (selectedElement.locked) {
                                Text("Elemen terkunci. Buka kunci untuk mengubah teks dan tipografinya.")
                            } else {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Isi teks") }
                            )
                            Button(onClick = {
                                val updatedSource = if (selectedSource.type == SceneSourceType.TEXT) {
                                    selectedSource.copy(text = editingText)
                                } else {
                                    selectedSource.copy(settings = selectedSource.settings + ("text" to editingText))
                                }
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
                            val availableExtensionFonts = installedElements.filter { it.kind == "font" }
                            if (availableExtensionFonts.isNotEmpty()) {
                                Text("Font ekstensi", style = MaterialTheme.typography.labelLarge)
                                availableExtensionFonts.forEach { font ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().testTag("scene-select-extension-font"),
                                        onClick = {
                                            updateTextSettings(mapOf(
                                                "fontExtensionId" to font.extensionId,
                                                "fontTypeId" to font.typeId
                                            ))
                                        }
                                    ) { Text(font.name) }
                                }
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        updateTextSettings(mapOf(
                                            "fontExtensionId" to null,
                                            "fontTypeId" to null
                                        ))
                                    }
                                ) { Text("Gunakan font bawaan") }
                            }
                            Text("Jenis huruf", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("sans-serif" to "Sans", "serif" to "Serif", "monospace" to "Mono").forEach { (family, label) ->
                                    OutlinedButton(onClick = { updateTextSetting("fontFamily", family) }, modifier = Modifier.weight(1f)) {
                                        Text(label)
                                    }
                                }
                            }
                            Text("Outline: " + (selectedSource.settings["outlineWidth"]?.toFloatOrNull() ?: 0f).toInt() + " px")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { updateTextSetting("outlineColor", "#FF000000") }) { Text("Hitam") }
                                OutlinedButton(onClick = { updateTextSetting("outlineColor", "#FF7C4DFF") }) { Text("Ungu") }
                                OutlinedButton(onClick = { updateTextSetting("outlineColor", "#FFFFFFFF") }) { Text("Putih") }
                            }
                            Slider(
                                value = (selectedSource.settings["outlineWidth"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 12f),
                                onValueChange = { updateTextSetting("outlineWidth", it.toString()) },
                                valueRange = 0f..12f,
                                steps = 23,
                                modifier = Modifier.testTag("text-outline-width")
                            )
                            Text("Bayangan: " + (selectedSource.settings["shadowRadius"]?.toFloatOrNull() ?: 0f).toInt() + " px")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    updateTextSetting("shadowColor", "#FF000000")
                                    updateTextSetting("shadowRadius", "6")
                                }) { Text("Bayangan hitam") }
                                OutlinedButton(onClick = {
                                    updateTextSetting("shadowColor", "#FF7C4DFF")
                                    updateTextSetting("shadowRadius", "6")
                                }) { Text("Bayangan ungu") }
                                OutlinedButton(onClick = { updateTextSetting("shadowRadius", "0") }) { Text("Matikan") }
                            }
                            Text("Jarak huruf")
                            Slider(
                                value = (selectedSource.settings["letterSpacing"]?.toFloatOrNull() ?: 0f).coerceIn(-0.1f, 0.5f),
                                onValueChange = { updateTextSetting("letterSpacing", it.toString()) },
                                valueRange = -0.1f..0.5f,
                                steps = 11,
                                modifier = Modifier.testTag("text-letter-spacing")
                            )
                            Text("Jarak baris")
                            Slider(
                                value = (selectedSource.settings["lineSpacing"]?.toFloatOrNull() ?: 1.15f).coerceIn(1f, 2f),
                                onValueChange = { updateTextSetting("lineSpacing", it.toString()) },
                                valueRange = 1f..2f,
                                steps = 9,
                                modifier = Modifier.testTag("text-line-spacing")
                            )
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Garis bawah", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = selectedSource.settings["underline"] == "true",
                                    onCheckedChange = { updateTextSetting("underline", it.toString()) }
                                )
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Coret", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = selectedSource.settings["strikethrough"] == "true",
                                    onCheckedChange = { updateTextSetting("strikethrough", it.toString()) }
                                )
                            }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().testTag("text-reset-formatting"),
                                onClick = {
                                    updateTextSettings(listOf(
                                        "fontSize", "color", "align", "bold", "italic",
                                        "fontExtensionId", "fontTypeId", "fontFamily",
                                        "outlineWidth", "outlineColor", "shadowRadius", "shadowColor",
                                        "letterSpacing", "lineSpacing", "underline", "strikethrough"
                                    ).associateWith { null })
                                }
                            ) { Text("Reset gaya teks") }
                            }
                        }
                        val selectedExtensionShape = selectedSource.type == SceneSourceType.EXTENSION &&
                            installedElements.any {
                                it.extensionId == selectedSource.extensionId &&
                                    it.typeId == selectedSource.typeId && it.kind == "shape"
                            }
                        if (selectedExtensionShape) {
                            if (selectedElement.locked) {
                                Text("Elemen terkunci. Buka kunci untuk mengubah bentuk dan warnanya.")
                            } else {
                            Text("Bentuk", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "rectangle" to "Persegi",
                                    "rounded-rect" to "Sudut bulat",
                                    "circle" to "Lingkaran"
                                ).forEach { (shape, label) ->
                                    OutlinedButton(
                                        modifier = Modifier.testTag("extension-shape-$shape"),
                                        onClick = { updateTextSetting("shape", shape) }
                                    ) { Text(label) }
                                }
                            }
                            Text("Warna bentuk", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "#FFFFFFFF" to "Putih",
                                    "#FF202124" to "Hitam",
                                    "#FF4CAF50" to "Hijau",
                                    "#FF2196F3" to "Biru",
                                    "#FFE91E63" to "Merah muda",
                                    "#FFFFC107" to "Kuning"
                                ).forEach { (color, label) ->
                                    OutlinedButton(
                                        modifier = Modifier.testTag("extension-shape-color-$color"),
                                        onClick = { updateTextSetting("fillColor", color) }
                                    ) { Text(label) }
                                }
                            }
                            val cornerRadius = (selectedSource.settings["cornerRadius"]?.toFloatOrNull() ?: 18f)
                                .coerceIn(0f, 128f)
                            Text("Radius sudut: ${cornerRadius.toInt()} px")
                            Slider(
                                modifier = Modifier.testTag("extension-shape-corner-radius"),
                                value = cornerRadius,
                                onValueChange = { updateTextSetting("cornerRadius", it.toInt().toString()) },
                                valueRange = 0f..128f,
                                enabled = selectedSource.settings["shape"] == "rounded-rect" ||
                                    selectedSource.settings["shape"] == "roundrect"
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
            if (editorPanel == "layers") {
            Text("Elemen scene", style = MaterialTheme.typography.titleMedium)
            // Keep the mode toggle outside the horizontal action scroller.
            // A horizontally scrollable parent was preventing reliable taps in
            // the emulator accessibility test and is also harder to use on phones.
            OutlinedButton(
                modifier = Modifier.testTag("scene-multiselect-toggle"),
                onClick = {
                    val enteringMultiSelect = !multiSelectMode
                    android.util.Log.i("OSSSceneSelection", "toggle multi-select: entering=$enteringMultiSelect")
                    multiSelectMode = enteringMultiSelect
                    selectedElementIds = if (enteringMultiSelect) emptySet() else selectedElementIds
                }
            ) { Text(if (multiSelectMode) "Selesai memilih" else "Pilih beberapa") }
            val selectedGroupIds = scene.elements
                .filter { it.id in selectedElementIds }
                .mapNotNull { it.groupId }
                .distinct()
            val selectedElementsForAlignment = scene.elements.filter { it.id in selectedElementIds }
            val canAlignSelection = selectedElementsForAlignment.size >= 2 &&
                selectedElementsForAlignment.none { it.locked }
            if (canAlignSelection) {
                fun alignSelection(horizontal: String? = null, vertical: String? = null) {
                    var updated = currentProject
                    val timestamp = System.currentTimeMillis()
                    val original = selectedElementsForAlignment.map { it.transform }
                    var aligned = original
                    if (horizontal != null) {
                        aligned = alignTransformsByRotatedBounds(
                            aligned, true,
                            when (horizontal) {
                                "left" -> ElementBoundsAlignment.START
                                "center" -> ElementBoundsAlignment.CENTER
                                else -> ElementBoundsAlignment.END
                            }, scene.canvas
                        )
                    }
                    if (vertical != null) {
                        aligned = alignTransformsByRotatedBounds(
                            aligned, false,
                            when (vertical) {
                                "top" -> ElementBoundsAlignment.START
                                "center" -> ElementBoundsAlignment.CENTER
                                else -> ElementBoundsAlignment.END
                            }, scene.canvas
                        )
                    }
                    selectedElementsForAlignment.zip(aligned).forEach { (selected, transform) ->
                        updated = SceneProjectEditor.updateTransform(
                            updated, scene.id, selected.id, transform, timestamp
                        )
                    }
                    applyProject(updated)
                }
                val horizontalDistribution = if (selectedElementsForAlignment.size >= 3) {
                    distributeTransformsByRotatedBounds(
                        selectedElementsForAlignment.map { it.transform }, true, scene.canvas
                    )
                } else null
                val verticalDistribution = if (selectedElementsForAlignment.size >= 3) {
                    distributeTransformsByRotatedBounds(
                        selectedElementsForAlignment.map { it.transform }, false, scene.canvas
                    )
                } else null
                Text("Ratakan pilihan", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-left"), onClick = { alignSelection(horizontal = "left") }) { Text("Kiri") }
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-center-x"), onClick = { alignSelection(horizontal = "center") }) { Text("Tengah X") }
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-right"), onClick = { alignSelection(horizontal = "right") }) { Text("Kanan") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-top"), onClick = { alignSelection(vertical = "top") }) { Text("Atas") }
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-center-y"), onClick = { alignSelection(vertical = "center") }) { Text("Tengah Y") }
                    OutlinedButton(modifier = Modifier.testTag("scene-multi-align-bottom"), onClick = { alignSelection(vertical = "bottom") }) { Text("Bawah") }
                }
                if (selectedElementsForAlignment.size >= 3) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.testTag("scene-distribute-horizontal"),
                            enabled = horizontalDistribution != null,
                            onClick = {
                                horizontalDistribution?.let { transforms ->
                                    var updated = currentProject
                                    val timestamp = System.currentTimeMillis()
                                    selectedElementsForAlignment.zip(transforms).forEach { (item, transform) ->
                                        updated = SceneProjectEditor.updateTransform(
                                            updated, scene.id, item.id, transform, timestamp
                                        )
                                    }
                                    applyProject(updated)
                                }
                            }
                        ) { Text("Sebar horizontal") }
                        OutlinedButton(
                            modifier = Modifier.testTag("scene-distribute-vertical"),
                            enabled = verticalDistribution != null,
                            onClick = {
                                verticalDistribution?.let { transforms ->
                                    var updated = currentProject
                                    val timestamp = System.currentTimeMillis()
                                    selectedElementsForAlignment.zip(transforms).forEach { (item, transform) ->
                                        updated = SceneProjectEditor.updateTransform(
                                            updated, scene.id, item.id, transform, timestamp
                                        )
                                    }
                                    applyProject(updated)
                                }
                            }
                        ) { Text("Sebar vertikal") }
                    }
                }
            }
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
                        OutlinedButton(
                            modifier = Modifier.testTag("scene-group-scale-up"),
                            onClick = {
                                applyProject(SceneProjectEditor.scaleGroup(
                                    currentProject, scene.id, selectedGroupIds.single(), 1.15f, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("Perbesar grup") }
                        OutlinedButton(
                            modifier = Modifier.testTag("scene-group-scale-down"),
                            onClick = {
                                applyProject(SceneProjectEditor.scaleGroup(
                                    currentProject, scene.id, selectedGroupIds.single(), 0.87f, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("Perkecil grup") }
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
                modifier = Modifier.fillMaxWidth().testTag("scene-layer-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scene.elements.sortedBy { it.zIndex }.forEach { element ->
                    val source = currentProject.sources.firstOrNull { it.id == element.sourceId }
                    val chosen = element.id in selectedElementIds
                    val layerPosition = scene.elements.sortedBy { it.zIndex }.indexOfFirst { it.id == element.id }
                    val layerCount = scene.elements.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).testTag("scene-element-item"),
                            onClick = {
                                android.util.Log.i("OSSSceneSelection", "layer click: id=${element.id}, multiSelect=$multiSelectMode, before=${selectedElementIds.size}")
                                if (multiSelectMode) {
                                    selectedElementIds = if (element.id in selectedElementIds) {
                                        selectedElementIds - element.id
                                    } else {
                                        selectedElementIds + element.id
                                    }
                                    selectedElementId = element.id
                                } else {
                                    selectedElementId = element.id
                                    selectedElementIds = setOf(element.id)
                                }
                                android.util.Log.i("OSSSceneSelection", "layer click complete: selected=${selectedElementIds.size}")
                                editingText = source?.text ?: source?.settings?.get("text").orEmpty()
                            }
                        ) {
                            val state = buildString {
                                if (chosen && multiSelectMode) append("✓ ")
                                append(source?.displayName ?: "Element")
                                if (element.locked) append(" · Terkunci")
                                if (!element.visible) append(" · Tersembunyi")
                            }
                            Text(state)
                        }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-visibility-" + element.id),
                            onClick = {
                                applyProject(SceneProjectEditor.setElementVisibility(
                                    currentProject, scene.id, element.id, !element.visible, System.currentTimeMillis()
                                ))
                            }
                        ) { Text(if (element.visible) "Sembunyi" else "Tampil") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-lock-" + element.id),
                            onClick = {
                                applyProject(SceneProjectEditor.setElementLocked(
                                    currentProject, scene.id, element.id, !element.locked, System.currentTimeMillis()
                                ))
                            }
                        ) { Text(if (element.locked) "Buka" else "Kunci") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-duplicate-" + element.id),
                            enabled = !element.locked,
                            onClick = {
                                val beforeIds = scene.elements.mapTo(mutableSetOf()) { it.id }
                                val duplicated = SceneProjectEditor.duplicateElement(
                                    currentProject, scene.id, element.id, System.currentTimeMillis()
                                )
                                val duplicateId = duplicated.scenes.first { it.id == scene.id }
                                    .elements.first { it.id !in beforeIds }.id
                                selectedElementId = duplicateId
                                selectedElementIds = setOf(duplicateId)
                                applyProject(duplicated)
                            }
                        ) { Text("Salin") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-move-up-" + element.id),
                            enabled = layerPosition > 0,
                            onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, element.id, layerPosition - 1, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("↑") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-move-down-" + element.id),
                            enabled = layerPosition < layerCount - 1,
                            onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, element.id, layerPosition + 1, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("↓") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-send-back-" + element.id),
                            enabled = layerPosition > 0,
                            onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, element.id, 0, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("Paling bawah") }
                        TextButton(
                            modifier = Modifier.testTag("scene-layer-bring-front-" + element.id),
                            enabled = layerPosition < layerCount - 1,
                            onClick = {
                                applyProject(SceneProjectEditor.moveElement(
                                    currentProject, scene.id, element.id, layerCount - 1, System.currentTimeMillis()
                                ))
                            }
                        ) { Text("Paling atas") }
                    }
                }
            }
            }
        }
    }

    if (showCanvasSettings && scene != null) {
        AlertDialog(
            onDismissRequest = { showCanvasSettings = false },
            title = { Text("Format canvas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rasio dan ukuran canvas mengikuti orientasi output LIVE project.")
                    Text("Untuk mengubahnya, buka halaman Studio lalu pilih Portrait 9:16 atau Landscape 16:9 pada bagian Orientasi output LIVE.")
                    Text("Perubahan akan diterapkan ke seluruh scene secara proporsional dan salinan sebelumnya disimpan sebagai recovery.")
                    Text(
                        "Format aktif: ${scene.canvas.width} × ${scene.canvas.height} px",
                        style = MaterialTheme.typography.labelMedium
                    )
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