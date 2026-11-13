package com.iamahsana.ossstudio.scene.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Movie
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.pedro.encoder.input.gl.render.filters.\`object\`.SurfaceFilterRender
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.extrasources.CameraUvcSource
import com.pedro.extrasources.Media3VideoSource
import com.pedro.library.view.GlStreamInterface
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Composites a scene into ordered GL surface filters.
 *
 * Static objects are grouped into transparent Canvas segments. Video and
 * capture sources use RootEncoder SurfaceFilterRender inputs at their exact
 * scene z-order, so a video/camera layer does not incorrectly cover text or
 * images that should appear above it.
 */
class SceneCanvasLayerController(
    context: Context,
    private val sourceId: String,
    private val glInterface: GlStreamInterface,
    private val layerPosition: LayerPosition = LayerPosition.ALL,
    private val shareScreenZIndex: Int? = null
) {
    enum class LayerPosition { ALL, BELOW_SHARE_SCREEN, ABOVE_SHARE_SCREEN }
    enum class State { IDLE, LOADING_ASSETS, WAITING_FOR_GL, WAITING_FOR_SURFACE, READY, STOPPED, FAILED }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var assetExecutor = Executors.newSingleThreadExecutor()
    private val filters = mutableListOf<SurfaceFilterRender>()
    private val canvasSurfaces = mutableMapOf<Int, Surface>()
    private val activeLiveSources = mutableMapOf<String, VideoSource>()
    private var renderPlan: List<SceneRenderNode> = emptyList()
    private var liveSourceSignature: List<String> = emptyList()
    private var surfacesReady = 0
    private var cachedBitmaps: Map<String, Bitmap> = emptyMap()
    private var cachedMovies: Map<String, Movie> = emptyMap()
    private var assetSignature: String = ""
    private val assetGeneration = AtomicLong(0L)
    private val gifStartTime = SystemClock.uptimeMillis()
    private val gifFrameRunnable = Runnable { renderGifFrames() }
    private var project: SceneProject? = null
    private var scene: Scene? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var hasShareScreen = false
    private var readyCallback: (() -> Unit)? = null
    private var failureCallback: (() -> Unit)? = null
    private var glCheck: Runnable? = null
    private var glAttempts = 0
    private var operationId = java.util.UUID.randomUUID().toString()

    @Volatile
    var state: State = State.IDLE
        private set

    fun attach(
        project: SceneProject,
        scene: Scene,
        outputWidth: Int,
        outputHeight: Int,
        hasShareScreen: Boolean,
        onReady: () -> Unit,
        onFailure: () -> Unit
    ) {
        checkMainThread()
        require(outputWidth > 0 && outputHeight > 0)
        check(state == State.IDLE || state == State.STOPPED || state == State.FAILED) {
            "Scene canvas layer is already attached"
        }
        this.project = project
        this.scene = scene
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.hasShareScreen = hasShareScreen
        readyCallback = onReady
        failureCallback = onFailure
        glAttempts = 0
        surfacesReady = 0
        glCheck?.let(mainHandler::removeCallbacks)
        glCheck = null
        if (assetExecutor.isShutdown || assetExecutor.isTerminated) {
            assetExecutor = Executors.newSingleThreadExecutor()
        }
        state = State.LOADING_ASSETS
        operationId = java.util.UUID.randomUUID().toString()

        assetExecutor.execute {
            val result = runCatching {
                val decoded = decodeSceneAssets(project, scene)
                val plan = SceneRenderPlan.build(project, scene, layerPosition, shareScreenZIndex)
                Triple(decoded, plan, assetSignature(project, scene))
            }
            mainHandler.post {
                if (state != State.LOADING_ASSETS) {
                    result.getOrNull()?.first?.bitmaps?.values?.forEach { if (!it.isRecycled) it.recycle() }
                    return@post
                }
                result.onSuccess { (decoded, plan, signature) ->
                    cachedBitmaps = decoded.bitmaps
                    cachedMovies = decoded.movies
                    renderPlan = plan
                    liveSourceSignature = SceneRenderPlan.liveSourceSignature(project, plan)
                    assetSignature = signature
                    state = State.WAITING_FOR_GL
                    waitForGl()
                }.onFailure { error ->
                    fail(error.message ?: "Scene asset decoding failed")
                }
            }
        }
    }

    private data class DecodedAssets(
        val bitmaps: Map<String, Bitmap>,
        val movies: Map<String, Movie>
    )

    private fun decodeSceneAssets(project: SceneProject, scene: Scene): DecodedAssets {
        val assets = project.assets.associateBy { it.id }
        val sources = project.sources.associateBy { it.id }
        val usedSourceIds = scene.elements.asSequence()
            .filter { it.visible && shouldRenderElement(it) }
            .mapNotNull { sources[it.sourceId] }
            .filter { it.enabled && it.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF) }
            .mapTo(mutableSetOf()) { it.id }
        val decoded = LinkedHashMap<String, Bitmap>()
        val movies = LinkedHashMap<String, Movie>()
        project.sources.filter { it.id in usedSourceIds }.forEach { source ->
            val asset = source.assetId?.let(assets::get) ?: return@forEach
            val file = File(appContext.filesDir, asset.relativePath)
            if (source.type == SceneSourceType.GIF) {
                val movie = runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull()
                if (movie != null) movies[source.id] = movie
                else decodeBitmap(file)?.let { decoded[source.id] = it }
            } else {
                decodeBitmap(file)?.let { decoded[source.id] = it }
            }
        }
        return DecodedAssets(decoded, movies)
    }

    private fun decodeBitmap(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun waitForGl() {
        checkMainThread()
        if (state != State.WAITING_FOR_GL) return
        if (!glInterface.isRunning()) {
            glAttempts++
            if (glAttempts >= MAX_GL_ATTEMPTS) {
                fail("Scene GL chain did not become ready")
                return
            }
            val retry = Runnable { waitForGl() }
            glCheck = retry
            mainHandler.postDelayed(retry, GL_RETRY_MS)
            return
        }
        installFilters()
    }

    private fun installFilters() {
        checkMainThread()
        if (renderPlan.isEmpty()) {
            fail("Scene render plan is empty")
            return
        }
        state = State.WAITING_FOR_SURFACE
        surfacesReady = 0
        filters.clear()
        canvasSurfaces.clear()
        activeLiveSources.clear()
        val currentScene = scene ?: run { fail("Scene is unavailable"); return }

        renderPlan.forEachIndexed { index, node ->
            lateinit var filter: SurfaceFilterRender
            filter = SurfaceFilterRender { surfaceTexture ->
                checkMainThread()
                if (state != State.WAITING_FOR_SURFACE) return@SurfaceFilterRender
                try {
                    when (node) {
                        is SceneRenderNode.CanvasSegment -> {
                            surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
                            val surface = requireNotNull(filter.surface) {
                                "Scene canvas segment surface was not initialized"
                            }
                            canvasSurfaces[index] = surface
                            drawFrame(node, surface)
                        }
                        is SceneRenderNode.LiveSource -> startLiveSource(node, filter, surfaceTexture, currentScene)
                    }
                    if (state != State.WAITING_FOR_SURFACE) return@SurfaceFilterRender
                    surfacesReady++
                    if (surfacesReady == renderPlan.size) {
                        state = State.READY
                        readyCallback?.invoke()
                        log("scene_canvas_ready", "Ordered scene compositor is ready")
                        renderGifFrames()
                    }
                } catch (error: Exception) {
                    fail(error.message ?: "Scene source initialization failed")
                }
            }
            if (node is SceneRenderNode.LiveSource) {
                configureLiveSourceFilter(filter, node, currentScene)
            }
            filters.add(filter)
            glInterface.addFilter(filter)
        }
    }

    private fun configureLiveSourceFilter(
        filter: SurfaceFilterRender,
        node: SceneRenderNode.LiveSource,
        currentScene: Scene
    ) {
        val transform = node.element.transform
        filter.setScale(
            transform.width / currentScene.canvas.width.toFloat() * 100f,
            transform.height / currentScene.canvas.height.toFloat() * 100f
        )
        filter.setPosition(
            transform.x / currentScene.canvas.width.toFloat() * 100f,
            transform.y / currentScene.canvas.height.toFloat() * 100f
        )
        val sourceRotation = node.source.settings["rotationDegrees"]?.toIntOrNull() ?: 0
        filter.rotation = (transform.rotationDegrees.roundToInt() + sourceRotation).mod(360)
        filter.alpha = node.element.opacity
    }

    private fun startLiveSource(
        node: SceneRenderNode.LiveSource,
        filter: SurfaceFilterRender,
        surfaceTexture: android.graphics.SurfaceTexture,
        currentScene: Scene
    ) {
        val adapter = when (node.source.type) {
            SceneSourceType.VIDEO -> createVideoSource(node.source, surfaceTexture)
            SceneSourceType.CAMERA -> createCameraSource(node.source, surfaceTexture)
            SceneSourceType.USB_VIDEO -> createUsbVideoSource(surfaceTexture)
            else -> error("Unsupported live scene source: " + node.source.type)
        }
        activeLiveSources[node.element.id] = adapter
        configureLiveSourceFilter(filter, node, currentScene)
        log(
            "scene_live_source_started",
            "Live source " + node.source.type + " attached to element " + node.element.id
        )
    }

    private fun createVideoSource(
        source: SceneSource,
        surfaceTexture: android.graphics.SurfaceTexture
    ): VideoSource {
        val currentProject = project ?: error("Scene project is unavailable")
        val asset = source.assetId?.let { id -> currentProject.assets.firstOrNull { it.id == id } }
            ?: error("Video asset is missing from the active project")
        val file = File(appContext.filesDir, asset.relativePath)
        require(file.isFile) { "Video asset file is missing: " + asset.relativePath }
        surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
        val speed = source.settings["speed"]?.toFloatOrNull()?.coerceIn(0.25f, 2f) ?: 1f
        val loop = source.settings["loop"] != "false"
        val adapter = Media3VideoSource(
            context = appContext,
            path = Uri.fromFile(file),
            speed = speed,
            loopMode = loop
        )
        check(adapter.init(outputWidth, outputHeight, 30, 0)) {
            "Media3 could not initialize the selected video"
        }
        try {
            adapter.start(surfaceTexture)
        } catch (error: Exception) {
            runCatching { adapter.stop() }
            runCatching { adapter.release() }
            throw error
        }
        return adapter
    }

    private fun createCameraSource(
        source: SceneSource,
        surfaceTexture: android.graphics.SurfaceTexture
    ): VideoSource {
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required before this scene can go LIVE"
        }
        val facing = if (source.settings["facing"] == "front") {
            CameraHelper.Facing.FRONT
        } else {
            CameraHelper.Facing.BACK
        }
        val adapter = Camera2Source(appContext)
        if (facing == CameraHelper.Facing.FRONT) adapter.switchCamera()
        val resolutions = adapter.getCameraResolutions(facing)
        val preferred = resolutions
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: resolutions.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: error("No supported camera resolution is available")
        adapter.setRequiredResolution(preferred)
        surfaceTexture.setDefaultBufferSize(preferred.width, preferred.height)
        check(adapter.init(preferred.width, preferred.height, 30, 0)) {
            "Camera source could not be initialized"
        }
        try {
            adapter.start(surfaceTexture)
        } catch (error: Exception) {
            runCatching { adapter.stop() }
            runCatching { adapter.release() }
            throw error
        }
        return adapter
    }

    private fun createUsbVideoSource(
        surfaceTexture: android.graphics.SurfaceTexture
    ): VideoSource {
        check(appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            "This Android device does not support USB host mode"
        }
        val adapter = CameraUvcSource()
        surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
        check(adapter.init(outputWidth, outputHeight, 30, 0)) {
            "USB camera source could not be initialized"
        }
        try {
            adapter.start(surfaceTexture)
        } catch (error: Exception) {
            runCatching { adapter.stop() }
            runCatching { adapter.release() }
            throw error
        }
        log(
            "usb_video_waiting_for_device",
            "USB video source is listening for an attached UVC camera"
        )
        return adapter
    }

    /** Re-render the active scene after a property or source update. */
    fun update(project: SceneProject, scene: Scene) {
        checkMainThread()
        check(state == State.READY) { "Scene canvas must be ready before update" }
        val nextPlan = SceneRenderPlan.build(project, scene, layerPosition, shareScreenZIndex)
        val nextLiveSignature = SceneRenderPlan.liveSourceSignature(project, nextPlan)
        if (nextLiveSignature != liveSourceSignature) {
            log(
                "live_source_topology_change_requires_restart",
                "Adding, removing, replacing, or reordering a live decoder/capture source requires restarting LIVE"
            )
            return
        }
        this.project = project
        this.scene = scene
        val surfaceSignature = assetSignature(project, scene)
        if (surfaceSignature == assetSignature) {
            applyRenderPlan(nextPlan)
            return
        }

        val generation = assetGeneration.incrementAndGet()
        assetExecutor.execute {
            val result = runCatching { decodeSceneAssets(project, scene) }
            mainHandler.post {
                if (state != State.READY || generation != assetGeneration.get()) {
                    result.getOrNull()?.bitmaps?.values?.forEach { if (!it.isRecycled) it.recycle() }
                    return@post
                }
                result.onSuccess { decoded ->
                    cachedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
                    cachedBitmaps = decoded.bitmaps
                    cachedMovies = decoded.movies
                    assetSignature = surfaceSignature
                    applyRenderPlan(nextPlan)
                }.onFailure { error ->
                    log("scene_canvas_update_decode_failed", error.message ?: "Scene asset update failed")
                }
            }
        }
    }

    private fun applyRenderPlan(nextPlan: List<SceneRenderNode>) {
        renderPlan = nextPlan
        val currentScene = scene ?: return
        nextPlan.forEachIndexed { index, node ->
            when (node) {
                is SceneRenderNode.CanvasSegment -> {
                    canvasSurfaces[index]?.let { surface -> drawFrame(node, surface) }
                }
                is SceneRenderNode.LiveSource -> {
                    filters.getOrNull(index)?.let { filter ->
                        configureLiveSourceFilter(filter, node, currentScene)
                    }
                }
            }
        }
        renderGifFrames()
    }

    private fun assetSignature(project: SceneProject, scene: Scene): String {
        val sources = project.sources.associateBy { it.id }
        val usedSourceIds = scene.elements.asSequence()
            .filter { it.visible && shouldRenderElement(it) }
            .mapNotNull { sources[it.sourceId] }
            .filter { it.enabled && it.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF) }
            .mapTo(mutableSetOf()) { it.id }
        val assets = project.assets.associateBy { it.id }
        return project.sources
            .filter { it.id in usedSourceIds }
            .sortedBy { it.id }
            .joinToString("|") { source ->
                val asset = source.assetId?.let(assets::get)
                source.id + ":" + source.type + ":" + source.enabled + ":" +
                    source.assetId + ":" + asset?.sha256 + ":" + asset?.relativePath
            }
    }

    private fun drawFrame(node: SceneRenderNode.CanvasSegment, surface: Surface) {
        checkMainThread()
        val currentProject = project ?: return
        val currentScene = scene ?: return
        if (!surface.isValid) {
            fail("Scene canvas segment surface is no longer valid")
            return
        }
        val sources = currentProject.sources.associateBy { it.id }
        val canvas = try {
            surface.lockCanvas(null)
        } catch (error: Exception) {
            fail(error.message ?: "Unable to lock scene canvas segment")
            return
        }
        try {
            if (node.drawsBackground) {
                val background = when (layerPosition) {
                    LayerPosition.ALL -> if (hasShareScreen) Color.TRANSPARENT else currentScene.backgroundArgb.toInt()
                    LayerPosition.BELOW_SHARE_SCREEN -> currentScene.backgroundArgb.toInt()
                    LayerPosition.ABOVE_SHARE_SCREEN -> Color.TRANSPARENT
                }
                val mode = if (
                    layerPosition == LayerPosition.BELOW_SHARE_SCREEN ||
                    (layerPosition == LayerPosition.ALL && !hasShareScreen)
                ) PorterDuff.Mode.SRC else PorterDuff.Mode.CLEAR
                canvas.drawColor(background, mode)
            } else {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }

            val scaleX = outputWidth.toFloat() / currentScene.canvas.width
            val scaleY = outputHeight.toFloat() / currentScene.canvas.height
            node.elements.sortedBy { it.zIndex }.forEach { element ->
                val source = sources[element.sourceId] ?: return@forEach
                if (!element.visible || !source.enabled) return@forEach
                val transform = element.transform
                val left = transform.x * scaleX
                val top = transform.y * scaleY
                val width = transform.width * scaleX
                val height = transform.height * scaleY
                val save = canvas.save()
                canvas.translate(left, top)
                canvas.rotate(transform.rotationDegrees, width / 2f, height / 2f)
                val destination = RectF(0f, 0f, width, height)
                when (source.type) {
                    SceneSourceType.TEXT -> drawText(canvas, source, destination, element.opacity, scaleY)
                    SceneSourceType.GIF -> {
                        val movie = cachedMovies[source.id]
                        if (movie != null && movie.width() > 0 && movie.height() > 0) {
                            val layer = canvas.saveLayer(destination, Paint().apply {
                                alpha = (element.opacity * 255f).toInt().coerceIn(0, 255)
                            })
                            val duration = movie.duration().takeIf { it > 0 } ?: 1000
                            movie.setTime(((SystemClock.uptimeMillis() - gifStartTime) % duration).toInt())
                            canvas.scale(width / movie.width().toFloat(), height / movie.height().toFloat())
                            movie.draw(canvas, 0f, 0f)
                            canvas.restoreToCount(layer)
                        } else {
                            drawCachedBitmap(canvas, source.id, element.crop, destination, element.opacity)
                        }
                    }
                    SceneSourceType.IMAGE ->
                        drawCachedBitmap(canvas, source.id, element.crop, destination, element.opacity)
                    SceneSourceType.VIDEO,
                    SceneSourceType.CAMERA,
                    SceneSourceType.USB_VIDEO,
                    SceneSourceType.SHARE_SCREEN -> Unit
                }
                canvas.restoreToCount(save)
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun renderGifFrames() {
        checkMainThread()
        if (state != State.READY) return
        var hasVisibleGif = false
        renderPlan.forEachIndexed { index, node ->
            if (node is SceneRenderNode.CanvasSegment) {
                val containsGif = node.elements.any { element ->
                    element.visible &&
                        shouldRenderElement(element) &&
                        cachedMovies.containsKey(element.sourceId)
                }
                if (containsGif) {
                    canvasSurfaces[index]?.let { surface ->
                        drawFrame(node, surface)
                        hasVisibleGif = true
                    }
                }
            }
        }
        if (hasVisibleGif) {
            mainHandler.removeCallbacks(gifFrameRunnable)
            mainHandler.postDelayed(gifFrameRunnable, GIF_FRAME_INTERVAL_MS)
        } else {
            mainHandler.removeCallbacks(gifFrameRunnable)
        }
    }

    private fun shouldRenderElement(element: com.iamahsana.ossstudio.scene.model.SceneElement): Boolean =
        isSceneElementInLayer(element.zIndex, layerPosition, shareScreenZIndex)

    private fun drawCachedBitmap(
        canvas: Canvas,
        sourceId: String,
        crop: com.iamahsana.ossstudio.scene.model.CropRect,
        destination: RectF,
        opacity: Float
    ) {
        val bitmap = cachedBitmaps[sourceId] ?: return
        val left = (crop.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (crop.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (crop.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (crop.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        val sourceRect = Rect(left, top, right, bottom)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, sourceRect, destination, paint)
    }

    private fun drawText(
        canvas: Canvas,
        source: SceneSource,
        bounds: RectF,
        opacity: Float,
        scaleY: Float
    ) {
        val settings = source.settings
        val size = (settings["fontSize"]?.toFloatOrNull() ?: 48f) * scaleY
        val color = runCatching { Color.parseColor(settings["color"] ?: "#FFFFFFFF") }.getOrDefault(Color.WHITE)
        val bold = settings["bold"] == "true"
        val italic = settings["italic"] == "true"
        val typefaceStyle = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val alignment = when (settings["align"]) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size.coerceIn(8f, 512f)
            this.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)
            textAlign = alignment
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        val outlineColor = settings["outlineColor"]?.let {
            runCatching { Color.parseColor(it) }.getOrNull()
        }
        val outlineWidth = (settings["outlineWidth"]?.toFloatOrNull() ?: 0f) * scaleY
        val text = source.text.orEmpty()
        val lines = wrapText(text, paint, bounds.width().coerceAtLeast(1f))
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * 1.15f
        val totalHeight = lineHeight * lines.size
        var baseline = (bounds.height() - totalHeight) / 2f - metrics.ascent
        val x = when (alignment) {
            Paint.Align.LEFT -> 0f
            Paint.Align.RIGHT -> bounds.width()
            else -> bounds.width() / 2f
        }
        lines.forEach { line ->
            if (outlineColor != null && outlineWidth > 0f) {
                val outline = Paint(paint).apply {
                    this.color = outlineColor
                    style = Paint.Style.STROKE
                    strokeWidth = outlineWidth.coerceAtMost(24f)
                }
                canvas.drawText(line, x, baseline, outline)
            }
            canvas.drawText(line, x, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val output = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    output.add(line)
                    line = word
                } else line = candidate
            }
            output.add(line)
        }
        return output.ifEmpty { listOf("") }
    }

    fun detach() {
        checkMainThread()
        glCheck?.let(mainHandler::removeCallbacks)
        glCheck = null
        assetGeneration.incrementAndGet()
        mainHandler.removeCallbacks(gifFrameRunnable)

        activeLiveSources.values.toList().forEach { adapter ->
            runCatching { adapter.stop() }
            runCatching { adapter.release() }
        }
        activeLiveSources.clear()

        var failed = false
        filters.toList().forEach { oldFilter ->
            try {
                glInterface.removeFilter(oldFilter)
            } catch (error: Exception) {
                failed = true
                log("scene_canvas_remove_failed", error.message ?: "Scene compositor filter removal failed")
            }
        }
        if (!failed) {
            filters.clear()
            canvasSurfaces.clear()
            cachedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            cachedBitmaps = emptyMap()
            cachedMovies = emptyMap()
            assetSignature = ""
            liveSourceSignature = emptyList()
            renderPlan = emptyList()
            project = null
            scene = null
            state = State.STOPPED
            assetExecutor.shutdownNow()
        } else {
            state = State.FAILED
            throw IllegalStateException("Scene compositor cleanup is incomplete")
        }
    }

    private fun fail(message: String) {
        if (state == State.FAILED || state == State.STOPPED) return
        state = State.FAILED
        log("scene_canvas_failed", message)
        failureCallback?.invoke()
    }

    private fun log(event: String, message: String) {
        com.iamahsana.ossstudio.logging.OssLogger.log(
            level = if (state == State.FAILED) com.iamahsana.ossstudio.logging.OssLogLevel.ERROR else com.iamahsana.ossstudio.logging.OssLogLevel.INFO,
            subsystem = "OSS/Scene/Compositor",
            component = "SceneCanvasLayerController",
            event = event,
            message = message,
            context = com.iamahsana.ossstudio.logging.OssLogContext(sourceId = sourceId, operationId = operationId)
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SceneCanvasLayerController lifecycle must run on the main thread"
        }
    }

    companion object {
        private const val GL_RETRY_MS = 50L
        private const val MAX_GL_ATTEMPTS = 100
        private const val GIF_FRAME_INTERVAL_MS = 33L
    }
}

internal fun isSceneElementInLayer(
    zIndex: Int,
    layerPosition: SceneCanvasLayerController.LayerPosition,
    shareScreenZIndex: Int?
): Boolean = when (layerPosition) {
    SceneCanvasLayerController.LayerPosition.ALL -> true
    SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN -> zIndex < (shareScreenZIndex ?: Int.MIN_VALUE)
    SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN -> zIndex > (shareScreenZIndex ?: Int.MAX_VALUE)
}
