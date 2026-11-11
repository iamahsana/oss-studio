package com.iamahsana.ossstudio.scene.editor

import android.content.Context
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
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.iamahsana.ossstudio.extensions.OssInstalledElement
import com.iamahsana.ossstudio.extensions.OssInstalledElementCatalog
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.iamahsana.ossstudio.scene.model.SceneTextStyle
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlStreamInterface
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Composites static scene elements into a transparent GL surface above Share Screen.
 * Asset decoding is kept off the UI/GL callbacks; only the final Surface draw is posted
 * to the main thread, matching RootEncoder SurfaceFilterRender's surface ownership rules.
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
    private var filter: SurfaceFilterRender? = null
    private var outputSurface: Surface? = null
    private var cachedBitmaps: Map<String, Bitmap> = emptyMap()
    private var cachedMovies: Map<String, Movie> = emptyMap()
    private var cachedExtensionElements: Map<String, OssInstalledElement> = emptyMap()
    private var cachedExtensionFonts: Map<String, Typeface> = emptyMap()
    private var cachedFontElements: Map<String, OssInstalledElement> = emptyMap()
    private var assetSignature: String = ""
    private val assetGeneration = AtomicLong(0L)
    private val gifStartTime = SystemClock.uptimeMillis()
    private val gifFrameRunnable = Runnable { outputSurface?.let { drawFrame(it) } }
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
        glCheck?.let(mainHandler::removeCallbacks)
        glCheck = null
        if (assetExecutor.isShutdown || assetExecutor.isTerminated) {
            assetExecutor = Executors.newSingleThreadExecutor()
        }
        state = State.LOADING_ASSETS
        operationId = java.util.UUID.randomUUID().toString()

        assetExecutor.execute {
            val result = runCatching { decodeSceneAssets(project, scene) }
            mainHandler.post {
                if (state != State.LOADING_ASSETS) {
                    result.getOrNull()?.bitmaps?.values?.forEach { if (!it.isRecycled) it.recycle() }
                    return@post
                }
                result.onSuccess { decoded ->
                    cachedBitmaps = decoded.bitmaps
                    cachedMovies = decoded.movies
                    cachedExtensionElements = decoded.extensions
                    cachedExtensionFonts = decoded.fonts
                    cachedFontElements = decoded.fontElements
                    assetSignature = assetSignature(project, scene)
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
        val movies: Map<String, Movie>,
        val extensions: Map<String, OssInstalledElement>,
        val fonts: Map<String, Typeface>,
        val fontElements: Map<String, OssInstalledElement>
    )

    private fun decodeSceneAssets(project: SceneProject, scene: Scene): DecodedAssets {
        val assets = project.assets.associateBy { it.id }
        val usedSourceIds = scene.elements.filter { it.visible && shouldRenderElement(it) }
            .mapTo(mutableSetOf()) { it.sourceId }
        val decoded = LinkedHashMap<String, Bitmap>()
        val movies = LinkedHashMap<String, Movie>()
        val extensions = LinkedHashMap<String, OssInstalledElement>()
        val fonts = LinkedHashMap<String, Typeface>()
        val fontElements = LinkedHashMap<String, OssInstalledElement>()
        project.sources.filter {
            it.id in usedSourceIds && it.enabled && (
                it.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF, SceneSourceType.VIDEO, SceneSourceType.EXTENSION) ||
                    (it.type == SceneSourceType.TEXT && it.settings["fontExtensionId"] != null && it.settings["fontTypeId"] != null)
                )
        }.forEach { source ->
            val fontExtensionId = source.settings["fontExtensionId"]
            val fontTypeId = source.settings["fontTypeId"]
            if (fontExtensionId != null && fontTypeId != null) {
                val fontElement = OssInstalledElementCatalog.find(
                    File(appContext.filesDir, "extensions"),
                    fontExtensionId,
                    fontTypeId
                )?.takeIf { it.kind == "font" }
                if (fontElement != null) {
                    fontElements[source.id] = fontElement
                    fontElement.resolveResource()?.let { fontFile ->
                        runCatching { Typeface.createFromFile(fontFile) }.getOrNull()?.let { fonts[source.id] = it }
                    }
                }
            }
            if (source.type == SceneSourceType.TEXT) return@forEach
            val extension = if (source.type == SceneSourceType.EXTENSION) {
                val extensionId = source.extensionId ?: return@forEach
                val typeId = source.typeId ?: return@forEach
                OssInstalledElementCatalog.find(File(appContext.filesDir, "extensions"), extensionId, typeId)
                    ?: return@forEach
            } else null
            if (extension != null) extensions[source.id] = extension
            val file = if (extension != null) {
                extension.resolveResource() ?: return@forEach
            } else {
                val asset = source.assetId?.let(assets::get) ?: return@forEach
                File(appContext.filesDir, asset.relativePath)
            }
            val kind = extension?.kind ?: source.type.name.lowercase()
            when (kind) {
                "gif" -> {
                    val movie = runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull()
                    if (movie != null) movies[source.id] = movie
                    else decodeBitmap(file)?.let { decoded[source.id] = it }
                }
                "image", "sticker", "background" -> decodeBitmap(file)?.let { decoded[source.id] = it }
                "video" -> decodeVideoFrame(file)?.let { decoded[source.id] = it }
            }
        }
        return DecodedAssets(decoded, movies, extensions, fonts, fontElements)
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

    private fun decodeVideoFrame(file: File): Bitmap? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
        installFilter()
    }

    private fun installFilter() {
        checkMainThread()
        state = State.WAITING_FOR_SURFACE
        val newFilter = SurfaceFilterRender { surfaceTexture ->
            checkMainThread()
            if (state != State.WAITING_FOR_SURFACE) return@SurfaceFilterRender
            try {
                surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
                val surface = requireNotNull(newFilterSurface()) { "Scene canvas surface was not initialized" }
                outputSurface = surface
                drawFrame(surface)
                state = State.READY
                readyCallback?.invoke()
                log("scene_canvas_ready", "Scene overlay canvas is ready")
            } catch (error: Exception) {
                fail(error.message ?: "Scene overlay surface initialization failed")
            }
        }
        filter = newFilter
        try {
            glInterface.addFilter(newFilter)
        } catch (error: Exception) {
            fail(error.message ?: "Unable to add scene overlay filter")
        }
    }

    private fun newFilterSurface(): Surface? = filter?.surface

    /** Re-render the active scene after a property or source update. */
    fun update(project: SceneProject, scene: Scene) {
        checkMainThread()
        check(state == State.READY) { "Scene canvas must be ready before update" }
        this.project = project
        this.scene = scene
        val surface = outputSurface ?: error("Scene canvas surface is unavailable")
        val nextSignature = assetSignature(project, scene)
        if (nextSignature == assetSignature) {
            drawFrame(surface)
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
                    cachedExtensionElements = decoded.extensions
                    cachedExtensionFonts = decoded.fonts
                    cachedFontElements = decoded.fontElements
                    assetSignature = assetSignature(project, scene)
                    drawFrame(surface)
                }.onFailure { error ->
                    log("scene_canvas_update_decode_failed", error.message ?: "Scene asset update failed")
                }
            }
        }
    }

    private fun assetSignature(project: SceneProject, scene: Scene): String {
        val assets = project.assets.associateBy { it.id }
        val usedSourceIds = scene.elements.filter { it.visible && shouldRenderElement(it) }
            .mapTo(mutableSetOf()) { it.sourceId }
        return project.sources
            .filter {
                it.id in usedSourceIds && it.enabled && (
                    it.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF, SceneSourceType.VIDEO, SceneSourceType.EXTENSION) ||
                        (it.type == SceneSourceType.TEXT && it.settings["fontExtensionId"] != null && it.settings["fontTypeId"] != null)
                    )
            }
            .sortedBy { it.id }
            .joinToString("|") { source ->
                if (source.type == SceneSourceType.EXTENSION) {
                    // Use the already verified catalog snapshot here. update() runs
                    // on the main thread; checksum revalidation belongs to the
                    // background decode path, never to a LIVE frame update.
                    val extension = cachedExtensionElements[source.id]?.takeIf {
                        it.extensionId == source.extensionId && it.typeId == source.typeId
                    }
                    val resource = extension?.resolveResource()
                    val font = cachedFontElements[source.id]?.takeIf {
                        it.extensionId == source.settings["fontExtensionId"] &&
                            it.typeId == source.settings["fontTypeId"]
                    }
                    val fontResource = font?.resolveResource()
                    "${source.id}:${source.type}:${source.enabled}:${source.extensionId}:${source.typeId}:${extension?.kind}:${resource?.length()}:${resource?.lastModified()}:font:${source.settings["fontExtensionId"]}:${source.settings["fontTypeId"]}:${fontResource?.length()}:${fontResource?.lastModified()}"
                } else if (source.settings["fontExtensionId"] != null && source.settings["fontTypeId"] != null) {
                    val font = cachedFontElements[source.id]?.takeIf {
                        it.extensionId == source.settings["fontExtensionId"] &&
                            it.typeId == source.settings["fontTypeId"]
                    }
                    val resource = font?.resolveResource()
                    "${source.id}:${source.type}:font:${source.settings["fontExtensionId"]}:${source.settings["fontTypeId"]}:${resource?.length()}:${resource?.lastModified()}"
                } else {
                    val asset = source.assetId?.let(assets::get)
                    "${source.id}:${source.type}:${source.enabled}:${source.assetId}:${asset?.sha256}:${asset?.relativePath}"
                }
            }
    }

    private fun drawFrame(surface: Surface) {
        checkMainThread()
        val currentProject = project ?: return
        val currentScene = scene ?: return
        if (!surface.isValid) {
            fail("Scene canvas output surface is no longer valid")
            return
        }
        val sources = currentProject.sources.associateBy { it.id }
        val canvas = try {
            surface.lockCanvas(null)
        } catch (error: Exception) {
            fail(error.message ?: "Unable to lock scene canvas surface")
            return
        }
        try {
            canvas.drawColor(
                when (layerPosition) {
                    LayerPosition.ALL -> if (hasShareScreen) Color.TRANSPARENT else currentScene.backgroundArgb.toInt()
                    LayerPosition.BELOW_SHARE_SCREEN -> currentScene.backgroundArgb.toInt()
                    LayerPosition.ABOVE_SHARE_SCREEN -> Color.TRANSPARENT
                },
                if (layerPosition == LayerPosition.BELOW_SHARE_SCREEN || (layerPosition == LayerPosition.ALL && !hasShareScreen)) {
                    PorterDuff.Mode.SRC
                } else PorterDuff.Mode.CLEAR
            )
            val scaleX = outputWidth.toFloat() / currentScene.canvas.width
            val scaleY = outputHeight.toFloat() / currentScene.canvas.height
            currentScene.elements.asSequence()
                .filter { it.visible && shouldRenderElement(it) }
                .sortedBy { it.zIndex }
                .forEach { element ->
                    val source = sources[element.sourceId] ?: return@forEach
                    if (!source.enabled || source.type == SceneSourceType.SHARE_SCREEN) return@forEach
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
                        SceneSourceType.IMAGE, SceneSourceType.VIDEO ->
                            drawCachedBitmap(canvas, source.id, element.crop, destination, element.opacity)
                        SceneSourceType.CAMERA -> drawPlaceholder(canvas, destination, "Camera", element.opacity)
                        SceneSourceType.USB_VIDEO -> drawPlaceholder(canvas, destination, "USB Video", element.opacity)
                        SceneSourceType.EXTENSION -> drawExtensionElement(canvas, source, destination, element.opacity, scaleY)
                        SceneSourceType.SHARE_SCREEN -> Unit
                    }
                    canvas.restoreToCount(save)
                }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        val hasVisibleGif = currentScene.elements.any { element ->
            if (!element.visible || !shouldRenderElement(element)) return@any false
            val source = sources[element.sourceId] ?: return@any false
            if (!cachedMovies.containsKey(source.id)) return@any false
            source.type == SceneSourceType.GIF || (
                source.type == SceneSourceType.EXTENSION &&
                    cachedExtensionElements[source.id]?.kind == "gif"
            )
        }
        if (hasVisibleGif && state == State.READY) {
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

    private fun drawExtensionElement(
        canvas: Canvas,
        source: SceneSource,
        destination: RectF,
        opacity: Float,
        scaleY: Float
    ) {
        val element = cachedExtensionElements[source.id]
        if (element == null) {
            drawPlaceholder(canvas, destination, "Extension unavailable: ${source.displayName}", opacity)
            return
        }
        when (element.kind) {
            "text" -> drawText(
                canvas, source, destination, opacity, scaleY,
                textOverride = source.settings["text"] ?: source.displayName
            )
            "image", "sticker", "video", "background" ->
                drawCachedBitmap(canvas, source.id, com.iamahsana.ossstudio.scene.model.CropRect(), destination, opacity)
            "gif" -> {
                if (!drawCachedMovie(canvas, source.id, destination, opacity)) {
                    drawCachedBitmap(canvas, source.id, com.iamahsana.ossstudio.scene.model.CropRect(), destination, opacity)
                }
            }
            "shape" -> drawExtensionShape(canvas, source, destination, opacity)
            else -> drawPlaceholder(canvas, destination, "Unsupported element: ${element.kind}", opacity)
        }
    }

    private fun drawCachedMovie(
        canvas: Canvas,
        sourceId: String,
        destination: RectF,
        opacity: Float
    ): Boolean {
        val movie = cachedMovies[sourceId] ?: return false
        if (movie.width() <= 0 || movie.height() <= 0) return false
        val layer = canvas.saveLayer(destination, Paint().apply {
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        })
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        movie.setTime(((SystemClock.uptimeMillis() - gifStartTime) % duration).toInt())
        canvas.save()
        canvas.scale(destination.width() / movie.width().toFloat(), destination.height() / movie.height().toFloat())
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
        canvas.restoreToCount(layer)
        return true
    }

    private fun drawExtensionShape(
        canvas: Canvas,
        source: SceneSource,
        bounds: RectF,
        opacity: Float
    ) {
        val color = runCatching {
            Color.parseColor(source.settings["fillColor"] ?: source.settings["color"] ?: "#FFFFFFFF")
        }.getOrDefault(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            style = Paint.Style.FILL
        }
        when (source.settings["shape"]?.lowercase()) {
            "circle", "ellipse", "oval" -> canvas.drawOval(bounds, paint)
            "rounded-rect", "roundrect" -> {
                val radius = (source.settings["cornerRadius"]?.toFloatOrNull() ?: 18f).coerceIn(0f, 256f)
                canvas.drawRoundRect(bounds, radius, radius, paint)
            }
            else -> canvas.drawRect(bounds, paint)
        }
    }

    private fun drawText(
        canvas: Canvas,
        source: SceneSource,
        bounds: RectF,
        opacity: Float,
        scaleY: Float,
        textOverride: String? = null
    ) {
        val textStyle = SceneTextStyle.fromSettings(source.settings)
        val size = textStyle.fontSize * scaleY
        val typefaceStyle = when {
            textStyle.bold && textStyle.italic -> Typeface.BOLD_ITALIC
            textStyle.bold -> Typeface.BOLD
            textStyle.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val alignment = when (textStyle.align) {
            SceneTextStyle.Alignment.LEFT -> Paint.Align.LEFT
            SceneTextStyle.Alignment.RIGHT -> Paint.Align.RIGHT
            SceneTextStyle.Alignment.CENTER -> Paint.Align.CENTER
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = runCatching { Color.parseColor(textStyle.color) }.getOrDefault(Color.WHITE)
            textSize = size.coerceIn(8f, 512f)
            typeface = Typeface.create(cachedExtensionFonts[source.id] ?: Typeface.create(textStyle.fontFamily, Typeface.NORMAL), typefaceStyle)
            textAlign = alignment
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            letterSpacing = textStyle.letterSpacing
            isUnderlineText = textStyle.underline
            isStrikeThruText = textStyle.strikethrough
        }
        val text = textOverride ?: source.text.orEmpty()
        val lines = wrapText(text, paint, bounds.width().coerceAtLeast(1f))
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * textStyle.lineSpacing
        val totalHeight = lineHeight * lines.size
        var baseline = (bounds.height() - totalHeight) / 2f - metrics.ascent
        val x = when (alignment) {
            Paint.Align.LEFT -> 0f
            Paint.Align.RIGHT -> bounds.width()
            else -> bounds.width() / 2f
        }
        lines.forEach { line ->
            if (textStyle.shadowColor != null && textStyle.shadowRadius > 0f) {
                val shadow = Paint(paint).apply {
                    color = runCatching { Color.parseColor(textStyle.shadowColor) }.getOrDefault(Color.BLACK)
                    setShadowLayer(
                        textStyle.shadowRadius * scaleY,
                        textStyle.shadowDx * scaleY,
                        textStyle.shadowDy * scaleY,
                        color
                    )
                }
                canvas.drawText(line, x, baseline, shadow)
            }
            if (textStyle.outlineColor != null && textStyle.outlineWidth > 0f) {
                val outline = Paint(paint).apply {
                    color = runCatching { Color.parseColor(textStyle.outlineColor) }.getOrDefault(Color.BLACK)
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = (textStyle.outlineWidth * scaleY).coerceAtMost(24f)
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

    private fun drawPlaceholder(canvas: Canvas, bounds: RectF, label: String, opacity: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        canvas.drawText(label, 8f, bounds.height() / 2f, paint)
    }

    fun detach() {
        checkMainThread()
        glCheck?.let(mainHandler::removeCallbacks)
        glCheck = null
        assetGeneration.incrementAndGet()
        mainHandler.removeCallbacks(gifFrameRunnable)
        var failed = false
        val oldFilter = filter
        if (oldFilter != null) {
            try {
                glInterface.removeFilter(oldFilter)
                filter = null
                outputSurface = null
            } catch (error: Exception) {
                failed = true
                log("scene_canvas_remove_failed", error.message ?: "Scene overlay filter removal failed")
            }
        }
        if (!failed) {
            cachedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            cachedBitmaps = emptyMap()
            cachedMovies = emptyMap()
            cachedExtensionElements = emptyMap()
            cachedExtensionFonts = emptyMap()
            cachedFontElements = emptyMap()
            assetSignature = ""
            project = null
            scene = null
            state = State.STOPPED
            assetExecutor.shutdownNow()
        } else {
            state = State.FAILED
            throw IllegalStateException("Scene overlay cleanup is incomplete")
        }
    }

    private fun fail(message: String) {
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
