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
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlStreamInterface
import java.io.File
import java.util.concurrent.Executors

/**
 * Composites static scene elements into a transparent GL surface above Share Screen.
 * Asset decoding is kept off the UI/GL callbacks; only the final Surface draw is posted
 * to the main thread, matching RootEncoder SurfaceFilterRender's surface ownership rules.
 */
class SceneCanvasLayerController(
    context: Context,
    private val sourceId: String,
    private val glInterface: GlStreamInterface
) {
    enum class State { IDLE, LOADING_ASSETS, WAITING_FOR_GL, WAITING_FOR_SURFACE, READY, STOPPED, FAILED }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val assetExecutor = Executors.newSingleThreadExecutor()
    private var filter: SurfaceFilterRender? = null
    private var outputSurface: Surface? = null
    private var cachedBitmaps: Map<String, Bitmap> = emptyMap()
    private var cachedMovies: Map<String, Movie> = emptyMap()
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
        state = State.LOADING_ASSETS
        operationId = java.util.UUID.randomUUID().toString()

        assetExecutor.execute {
            val decoded = decodeSceneAssets(project)
            mainHandler.post {
                if (state != State.LOADING_ASSETS) {
                    decoded.bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
                    return@post
                }
                cachedBitmaps = decoded.bitmaps
                cachedMovies = decoded.movies
                state = State.WAITING_FOR_GL
                waitForGl()
            }
        }
    }

    private data class DecodedAssets(
        val bitmaps: Map<String, Bitmap>,
        val movies: Map<String, Movie>
    )

    private fun decodeSceneAssets(project: SceneProject): DecodedAssets {
        val assets = project.assets.associateBy { it.id }
        val decoded = LinkedHashMap<String, Bitmap>()
        val movies = LinkedHashMap<String, Movie>()
        project.sources.filter {
            it.enabled && it.type in setOf(SceneSourceType.IMAGE, SceneSourceType.GIF, SceneSourceType.VIDEO)
        }.forEach { source ->
            val asset = assets[source.assetId] ?: return@forEach
            val file = File(appContext.filesDir, asset.relativePath)
            if (source.type == SceneSourceType.GIF) {
                val movie = runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull()
                if (movie != null) movies[source.id] = movie
                else decodeBitmap(file)?.let { decoded[source.id] = it }
            } else {
                val bitmap = if (source.type == SceneSourceType.VIDEO) decodeVideoFrame(file) else decodeBitmap(file)
                if (bitmap != null) decoded[source.id] = bitmap
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
        drawFrame(surface)
    }

    private fun drawFrame(surface: Surface) {
        checkMainThread()
        val currentProject = project ?: return
        val currentScene = scene ?: return
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(
                if (hasShareScreen) Color.TRANSPARENT else currentScene.backgroundArgb.toInt(),
                if (hasShareScreen) PorterDuff.Mode.CLEAR else PorterDuff.Mode.SRC
            )
            val scaleX = outputWidth.toFloat() / currentScene.canvas.width
            val scaleY = outputHeight.toFloat() / currentScene.canvas.height
            val sources = currentProject.sources.associateBy { it.id }
            currentScene.elements.asSequence()
                .filter { it.visible }
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
                        SceneSourceType.SHARE_SCREEN -> Unit
                    }
                    canvas.restoreToCount(save)
                }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        if (cachedMovies.isNotEmpty() && state == State.READY) {
            mainHandler.removeCallbacks(gifFrameRunnable)
            mainHandler.postDelayed(gifFrameRunnable, GIF_FRAME_INTERVAL_MS)
        }
    }

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
        val typeface = if (settings["bold"] == "true") Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size.coerceIn(8f, 512f)
            this.typeface = typeface
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        val text = source.text.orEmpty()
        val lines = wrapText(text, paint, bounds.width().coerceAtLeast(1f))
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * 1.15f
        val totalHeight = lineHeight * lines.size
        var baseline = (bounds.height() - totalHeight) / 2f - metrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, 0f, baseline, paint)
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
