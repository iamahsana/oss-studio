package com.iamahsana.ossstudio.stream.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.iamahsana.ossstudio.audio.OssAudioMixerSession
import com.iamahsana.ossstudio.audio.AudioMixerSettingsStore
import com.iamahsana.ossstudio.audio.AudioMixerControlBus
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.iamahsana.ossstudio.scene.editor.SceneCanvasLayerController
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenLayerController
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenSessionService
import com.iamahsana.ossstudio.stream.AudioStopStatus
import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.rtmp.RtmpStreamSession
import com.iamahsana.ossstudio.stream.rtmp.MultiRtmpStreamSession
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfile
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfileStore
import com.pedro.encoder.input.sources.audio.BufferAudioSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android adapter joining service-owned MediaProjection, RootEncoder RTMP,
 * independent OSS audio buses, and the Share Screen GL layer.
 *
 * No audio source-mode picker exists: the mixer starts every available core
 * channel and reports unavailable channels independently.
 */
class AndroidLiveRuntimeFactory(
    context: Context,
    private val projectionService: () -> ShareScreenSessionService?,
    private val streamConfig: RtmpStreamSession.Config = RtmpStreamSession.Config(),
    private val sceneProvider: () -> SceneProject? = { null }
) : LiveSessionController.RuntimeFactory {

    private val appContext = context.applicationContext

    override fun create(): LiveSessionController.Runtime {
        return AndroidLiveRuntime(
            appContext = appContext,
            projectionService = projectionService,
            streamConfig = streamConfig,
            sceneProvider = sceneProvider
        )
    }

    private class AndroidLiveRuntime(
        private val appContext: Context,
        private val projectionService: () -> ShareScreenSessionService?,
        private val streamConfig: RtmpStreamSession.Config,
        private val sceneProvider: () -> SceneProject?
    ) : LiveSessionController.Runtime {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val runtimeId = UUID.randomUUID().toString()
        private val audioStopRequested = AtomicBoolean(false)

        private var screenLayer: ShareScreenLayerController? = null
        private val sceneCanvasLayers = mutableListOf<SceneCanvasLayerController>()
        private var screenCheck: Runnable? = null
        private var screenCheckAttempts = 0
        private var released = false
        private var streamListener: LiveSessionController.RtmpListener? = null
        private var streamConnectedReported = false
        private var streamFailureReported = false
        private var rtmpConnected = false
        private var audioReady = false

        private var stream: RtmpStreamSession? = null
        private var multiStream: MultiRtmpStreamSession? = null
        private var singleDestinationProfile: RtmpDestinationProfile? = null
        private var audioMixerSession: OssAudioMixerSession? = null
        private var activeProject: SceneProject? = null
        private var activeStreamConfig: RtmpStreamSession.Config = streamConfig
        private var sceneProjectSubscription: AutoCloseable? = null

        override fun prepare(): Boolean {
            checkMainThread()
            val project = sceneProvider()
            if (project == null) {
                log(OssLogLevel.ERROR, "scene_project_unavailable", "No active scene project was supplied to the LIVE runtime", "SCENE_PROJECT_UNAVAILABLE")
                return false
            }
            val scene = project.scenes.firstOrNull { it.id == project.activeSceneId } ?: project.scenes.firstOrNull()
            if (scene == null) {
                log(OssLogLevel.ERROR, "scene_unavailable", "Active project has no scene to stream", "SCENE_UNAVAILABLE")
                return false
            }
            activeProject = project
            sceneProjectSubscription = ActiveSceneProjectRegistry.subscribe { updated ->
                dispatchMain { onActiveSceneProjectUpdated(updated) }
            }
            val outputSize = sceneOutputResolution(scene.canvas)
            activeStreamConfig = streamConfig.copy(
                width = outputSize.width,
                height = outputSize.height
            )

            val bufferAudioSource = BufferAudioSource()
            val enabledProfiles = try {
                RtmpDestinationProfileStore.load(appContext).filter { it.enabled }
            } catch (error: Exception) {
                log(
                    OssLogLevel.ERROR,
                    "destination_vault_load_failed",
                    "Unable to decrypt RTMP destination profiles",
                    "RTMP_DESTINATION_VAULT_FAILED",
                    error
                )
                return false
            }

            if (enabledProfiles.size > 1) {
                val newMultiStream = MultiRtmpStreamSession(
                    context = appContext,
                    profiles = enabledProfiles,
                    audioSource = bufferAudioSource,
                    listener = object : MultiRtmpStreamSession.Listener {
                        override fun onStateChanged(
                            state: MultiRtmpStreamSession.State,
                            destinations: List<MultiRtmpStreamSession.DestinationSnapshot>
                        ) {
                            dispatchMain {
                                if (released || audioStopRequested.get()) return@dispatchMain
                                when (state) {
                                    MultiRtmpStreamSession.State.LIVE -> {
                                        rtmpConnected = true
                                        reportConnectedIfReady()
                                    }
                                    MultiRtmpStreamSession.State.FAILED -> {
                                        if (destinations.none { it.state == MultiRtmpStreamSession.DestinationState.CONNECTED }) {
                                            reportFailureOnce()
                                        }
                                    }
                                    MultiRtmpStreamSession.State.STOPPED -> {
                                        if (rtmpConnected) reportFailureOnce()
                                    }
                                    else -> Unit
                                }
                            }
                        }

                        override fun onDestinationChanged(
                            destination: MultiRtmpStreamSession.DestinationSnapshot
                        ) {
                            log(
                                if (destination.state == MultiRtmpStreamSession.DestinationState.CONNECTED) OssLogLevel.INFO else OssLogLevel.WARN,
                                "rtmp_destination_state",
                                "RTMP destination state: ${destination.state}",
                                destinationId = destination.id
                            )
                        }
                    }
                )
                if (!newMultiStream.prepare(
                        MultiRtmpStreamSession.Config(
                            width = activeStreamConfig.width,
                            height = activeStreamConfig.height,
                            fps = activeStreamConfig.fps,
                            videoBitrate = activeStreamConfig.videoBitrate,
                            audioSampleRate = activeStreamConfig.audioSampleRate,
                            audioStereo = activeStreamConfig.audioStereo,
                            audioBitrate = activeStreamConfig.audioBitrate
                        )
                    )
                ) {
                    runCatching { newMultiStream.release() }
                    log(
                        OssLogLevel.ERROR,
                        "multi_encoder_prepare_failed",
                        "RootEncoder could not prepare shared multi-destination codecs",
                        "LIVE_MULTI_ENCODER_PREPARE_FAILED"
                    )
                    return false
                }
                multiStream = newMultiStream
            } else {
                singleDestinationProfile = enabledProfiles.singleOrNull()
                val newStream = RtmpStreamSession(
                    context = appContext,
                    listener = RtmpStreamSession.Listener { state, detail ->
                        dispatchMain { onRtmpStateChanged(state, detail) }
                    },
                    audioSource = bufferAudioSource
                )
                if (!newStream.prepare(activeStreamConfig)) {
                    runCatching { newStream.release() }
                    log(
                        OssLogLevel.ERROR,
                        "encoder_prepare_failed",
                        "RootEncoder could not prepare video/audio codecs",
                        "LIVE_ENCODER_PREPARE_FAILED"
                    )
                    return false
                }
                stream = newStream
            }
            val mixerSettings = AudioMixerSettingsStore.load(appContext)
            audioMixerSession = OssAudioMixerSession(
                context = appContext,
                bufferAudioSource = bufferAudioSource,
                initialChannelConfigs = arrayOf(mixerSettings.microphone, mixerSettings.internalAudio),
                initialLiveMasterGain = mixerSettings.liveMasterGain,
                initialMonitorMasterGain = mixerSettings.monitorMasterGain,
                onCaptureStatus = { snapshot ->
                log(
                    if (snapshot.hasAnySource) OssLogLevel.INFO else OssLogLevel.WARN,
                    "audio_capture_status",
                    "Audio sources: mic=${snapshot.microphone.state}, internal=${snapshot.internalAudio.state}",
                    snapshot.microphone.errorCode ?: snapshot.internalAudio.errorCode
                )
                },
                onFatalError = { error ->
                    log(
                        OssLogLevel.ERROR,
                        "audio_mixer_worker_failed",
                        "Audio mixer worker failed while LIVE; initiating session failure cleanup",
                        "AUDIO_MIXER_WORKER_FAILED",
                        error
                    )
                    dispatchMain { reportFailureOnce() }
                }
            )
            AudioMixerControlBus.attach(checkNotNull(audioMixerSession))
            log(
                OssLogLevel.INFO,
                "runtime_prepared",
                "RootEncoder prepared with OSS BufferAudioSource; independent mixer is ready to start"
            )
            return true
        }


        private fun onActiveSceneProjectUpdated(updated: SceneProject?) {
            checkMainThread()
            val current = activeProject ?: return
            if (updated == null || updated.id != current.id) {
                log(OssLogLevel.WARN, "live_project_switch_ignored", "Changing projects requires stopping and restarting LIVE")
                return
            }
            val scene = updated.scenes.firstOrNull { it.id == updated.activeSceneId }
                ?: updated.scenes.firstOrNull()
            if (scene == null) {
                log(OssLogLevel.WARN, "live_scene_update_ignored", "Updated project has no active scene")
                return
            }
            val sources = updated.sources.associateBy { it.id }
            val shareElement = scene.elements.firstOrNull { element ->
                element.visible && sources[element.sourceId]?.let {
                    it.enabled && it.type == SceneSourceType.SHARE_SCREEN
                } == true
            }
            val currentScene = current.scenes.firstOrNull { it.id == current.activeSceneId }
                ?: current.scenes.firstOrNull()
            if (currentScene != null && currentScene.canvas != scene.canvas) {
                log(OssLogLevel.WARN, "live_canvas_resize_ignored", "Changing canvas dimensions requires restarting LIVE")
                return
            }
            val currentSources = current.sources.associateBy { it.id }
            val currentShareElement = currentScene?.elements?.firstOrNull { element ->
                element.visible && currentSources[element.sourceId]?.let {
                    it.enabled && it.type == SceneSourceType.SHARE_SCREEN
                } == true
            }
            val hadShareScreen = currentShareElement != null
            if (hadShareScreen && shareElement != null &&
                currentShareElement?.zIndex != shareElement.zIndex
            ) {
                log(OssLogLevel.WARN, "live_share_layer_order_ignored", "Changing Share Screen z-order requires restarting LIVE")
                return
            }
            if (hadShareScreen != (shareElement != null)) {
                log(OssLogLevel.WARN, "live_share_screen_toggle_ignored", "Adding or removing Share Screen requires restarting LIVE")
                return
            }
            activeProject = updated
            sceneCanvasLayers.forEach { layer ->
                if (layer.state == SceneCanvasLayerController.State.READY) {
                    layer.update(updated, scene)
                } else {
                    log(OssLogLevel.INFO, "live_scene_update_deferred", "Scene update will apply when compositor becomes ready")
                }
            }
            shareElement?.let { element ->
                val transform = element.transform
                screenLayer?.updateTransform(
                    scaleX = transform.width / scene.canvas.width.toFloat() * 100f,
                    scaleY = transform.height / scene.canvas.height.toFloat() * 100f,
                    positionX = transform.x / scene.canvas.width.toFloat() * 100f,
                    positionY = transform.y / scene.canvas.height.toFloat() * 100f,
                    rotationDegrees = transform.rotationDegrees
                )
            }
        }

        override fun startRtmp(
            endpoint: String,
            listener: LiveSessionController.RtmpListener
        ) {
            checkMainThread()
            streamListener = listener
            streamConnectedReported = false
            streamFailureReported = false
            rtmpConnected = false
            audioReady = false
            audioStopRequested.set(false)

            when {
                multiStream != null -> {
                    if (multiStream?.startAll() == 0) reportFailureOnce()
                }
                else -> checkNotNull(stream) { "RTMP pipeline was not prepared" }
                    .start(singleDestinationProfile?.endpoint() ?: endpoint)
            }

            // RootEncoder starts BufferAudioSource as part of startStream().
            // Start the independent capture/mixer graph afterward so it writes
            // into an initialized, running encoder source.
            if (audioStopRequested.get()) return
            val mixer = checkNotNull(audioMixerSession) { "OSS audio mixer was not prepared" }
            val projection = projectionService()?.getActiveProjection()
            Thread({
                if (audioStopRequested.get()) return@Thread
                val ready = runCatching { mixer.start(projection) }.getOrElse { error ->
                    if (!audioStopRequested.get()) {
                        log(
                            OssLogLevel.ERROR,
                            "audio_graph_start_exception",
                            "OSS audio graph startup threw an exception",
                            "AUDIO_GRAPH_START_EXCEPTION",
                            error
                        )
                    }
                    false
                }
                dispatchMain {
                    if (released || audioStopRequested.get()) return@dispatchMain
                    if (!ready) {
                        reportFailureOnce()
                    } else {
                        audioReady = true
                        reportConnectedIfReady()
                    }
                }
            }, "oss-audio-graph-start").apply {
                isDaemon = true
                start()
            }
        }

        private fun onRtmpStateChanged(state: RtmpStreamSession.State, detail: String?) {
            if (released || audioStopRequested.get()) return
            when (state) {
                RtmpStreamSession.State.LIVE -> {
                    rtmpConnected = true
                    reportConnectedIfReady()
                }
                RtmpStreamSession.State.FAILED -> reportFailureOnce()
                RtmpStreamSession.State.STOPPED -> if (rtmpConnected) reportFailureOnce()
                else -> Unit
            }
        }

        private fun reportConnectedIfReady() {
            if (rtmpConnected && audioReady && !streamConnectedReported && !streamFailureReported) {
                streamConnectedReported = true
                log(
                    OssLogLevel.INFO,
                    "live_prerequisites_ready",
                    "RTMP destination and OSS audio mixer are both ready"
                )
                streamListener?.onConnected()
            }
        }

        private fun reportFailureOnce() {
            if (streamFailureReported || audioStopRequested.get()) return
            streamFailureReported = true
            log(
                OssLogLevel.ERROR,
                "live_prerequisite_failed",
                "RTMP connection or OSS audio mixer failed to become ready",
                "LIVE_PREREQUISITE_FAILED"
            )
            streamListener?.onFailure()
        }

        override fun attachScreen(listener: LiveSessionController.ScreenListener) {
            checkMainThread()
            val project = activeProject
            if (project == null) {
                log(OssLogLevel.ERROR, "scene_project_unavailable", "Cannot start LIVE without an active scene project", "SCENE_PROJECT_UNAVAILABLE")
                listener.onFailure()
                return
            }
            val scene = project.scenes.firstOrNull { it.id == project.activeSceneId }
                ?: project.scenes.firstOrNull()
            if (scene == null) {
                log(OssLogLevel.ERROR, "scene_unavailable", "Active project has no scene", "SCENE_UNAVAILABLE")
                listener.onFailure()
                return
            }
            val sources = project.sources.associateBy { it.id }
            val shareElement = scene.elements.firstOrNull { element ->
                element.visible && sources[element.sourceId]?.let {
                    it.enabled && it.type == SceneSourceType.SHARE_SCREEN
                } == true
            }
            val glInterface = multiStream?.glInterface()
                ?: stream?.glInterface()
                ?: run { listener.onFailure(); return }

            if (shareElement == null) {
                val layer = SceneCanvasLayerController(
                    context = appContext,
                    sourceId = "scene-layer-all-$runtimeId",
                    glInterface = glInterface
                )
                sceneCanvasLayers.add(layer)
                layer.attach(
                    project = project,
                    scene = scene,
                    outputWidth = activeStreamConfig.width,
                    outputHeight = activeStreamConfig.height,
                    hasShareScreen = false,
                    onReady = {
                        val latest = activeProject
                        val latestScene = latest?.scenes?.firstOrNull { it.id == latest.activeSceneId }
                            ?: latest?.scenes?.firstOrNull()
                        if (latest != null && latest.id == project.id && latestScene != null && latest !== project) {
                            layer.update(latest, latestScene)
                        }
                        listener.onReady()
                    },
                    onFailure = { listener.onFailure() }
                )
                return
            }

            val belowLayer = SceneCanvasLayerController(
                context = appContext,
                sourceId = "scene-layer-below-$runtimeId",
                glInterface = glInterface,
                layerPosition = SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN,
                shareScreenZIndex = shareElement.zIndex
            )
            sceneCanvasLayers.add(belowLayer)
            belowLayer.attach(
                project = project,
                scene = scene,
                outputWidth = activeStreamConfig.width,
                outputHeight = activeStreamConfig.height,
                hasShareScreen = true,
                onReady = {
                    val latestProject = activeProject ?: project
                    val latestScene = latestProject.scenes.firstOrNull { it.id == latestProject.activeSceneId }
                        ?: latestProject.scenes.firstOrNull()
                    if (latestScene == null) {
                        listener.onFailure()
                    } else {
                        if (latestProject !== project) belowLayer.update(latestProject, latestScene)
                        attachShareScreenAndAboveLayer(
                            latestProject,
                            latestScene,
                            shareElement.zIndex,
                            glInterface,
                            listener
                        )
                    }
                },
                onFailure = { listener.onFailure() }
            )
        }

        private fun attachShareScreenAndAboveLayer(
            project: SceneProject,
            scene: com.iamahsana.ossstudio.scene.model.Scene,
            shareScreenZIndex: Int,
            glInterface: com.pedro.library.view.GlStreamInterface,
            listener: LiveSessionController.ScreenListener
        ) {
            checkMainThread()
            val sources = project.sources.associateBy { it.id }
            val shareElement = scene.elements.firstOrNull { element ->
                element.visible && element.zIndex == shareScreenZIndex &&
                    sources[element.sourceId]?.let { it.enabled && it.type == SceneSourceType.SHARE_SCREEN } == true
            } ?: run { listener.onFailure(); return }
            val projection = projectionService()?.getActiveProjection()
            if (projection == null) {
                log(
                    OssLogLevel.ERROR,
                    "projection_unavailable",
                    "The active scene contains Share Screen but no MediaProjection is available",
                    "SCREEN_PROJECTION_UNAVAILABLE"
                )
                listener.onFailure()
                return
            }

            val transform = shareElement.transform
            val layer = ShareScreenLayerController(
                sourceId = shareElement.sourceId,
                glInterface = glInterface
            )
            screenLayer = layer
            screenCheckAttempts = 0
            try {
                layer.attach(
                    mediaProjection = projection,
                    width = activeStreamConfig.width,
                    height = activeStreamConfig.height,
                    densityDpi = appContext.resources.displayMetrics.densityDpi,
                    scaleX = transform.width / scene.canvas.width.toFloat() * 100f,
                    scaleY = transform.height / scene.canvas.height.toFloat() * 100f,
                    positionX = transform.x / scene.canvas.width.toFloat() * 100f,
                    positionY = transform.y / scene.canvas.height.toFloat() * 100f,
                    rotationDegrees = transform.rotationDegrees
                )
                watchScreenReadiness(layer, listener) {
                    val latestProject = activeProject ?: project
                    val latestScene = latestProject.scenes.firstOrNull { it.id == latestProject.activeSceneId }
                        ?: latestProject.scenes.firstOrNull()
                    if (latestScene == null) {
                        listener.onFailure()
                        return@watchScreenReadiness
                    }
                    val aboveLayer = SceneCanvasLayerController(
                        context = appContext,
                        sourceId = "scene-layer-above-$runtimeId",
                        glInterface = glInterface,
                        layerPosition = SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN,
                        shareScreenZIndex = shareScreenZIndex
                    )
                    sceneCanvasLayers.add(aboveLayer)
                    aboveLayer.attach(
                        project = latestProject,
                        scene = latestScene,
                        outputWidth = activeStreamConfig.width,
                        outputHeight = activeStreamConfig.height,
                        hasShareScreen = true,
                        onReady = {
                            val newest = activeProject
                            val newestScene = newest?.scenes?.firstOrNull { it.id == newest.activeSceneId }
                                ?: newest?.scenes?.firstOrNull()
                            if (newest != null && newest.id == project.id && newestScene != null && newest !== latestProject) {
                                aboveLayer.update(newest, newestScene)
                            }
                            listener.onReady()
                        },
                        onFailure = { listener.onFailure() }
                    )
                }
            } catch (error: Exception) {
                log(
                    OssLogLevel.ERROR,
                    "screen_attach_exception",
                    error.message ?: "Share Screen attach failed",
                    "SCREEN_SOURCE_ATTACH_FAILED",
                    error
                )
                listener.onFailure()
            }
        }

        private fun watchScreenReadiness(
            layer: ShareScreenLayerController,
            listener: LiveSessionController.ScreenListener,
            onReady: () -> Unit = { listener.onReady() }
        ) {
            val check = object : Runnable {
                override fun run() {
                    if (released || screenLayer !== layer) return
                    when (layer.state) {
                        ShareScreenLayerController.State.CAPTURING -> {
                            screenCheck = null
                            log(
                                OssLogLevel.INFO,
                                "screen_source_ready",
                                "Share Screen VirtualDisplay is attached to RootEncoder GL"
                            )
                            onReady()
                        }
                        ShareScreenLayerController.State.FAILED,
                        ShareScreenLayerController.State.STOPPED -> {
                            screenCheck = null
                            listener.onFailure()
                        }
                        else -> {
                            screenCheckAttempts++
                            if (screenCheckAttempts >= MAX_SCREEN_CHECKS) {
                                screenCheck = null
                                log(
                                    OssLogLevel.ERROR,
                                    "screen_attach_timeout",
                                    "Share Screen did not become ready before adapter timeout",
                                    "SCREEN_SOURCE_READY_TIMEOUT"
                                )
                                listener.onFailure()
                            } else {
                                mainHandler.postDelayed(this, SCREEN_CHECK_INTERVAL_MS)
                            }
                        }
                    }
                }
            }
            screenCheck = check
            mainHandler.post(check)
        }

        override fun detachScreen() {
            checkMainThread()
            screenCheck?.let(mainHandler::removeCallbacks)
            screenCheck = null
            if (sceneCanvasLayers.size > 1) {
                val aboveLayer = sceneCanvasLayers.last()
                aboveLayer.detach()
                sceneCanvasLayers.removeAt(sceneCanvasLayers.lastIndex)
            }
            screenLayer?.let {
                it.detach()
                screenLayer = null
            }
            sceneCanvasLayers.firstOrNull()?.let {
                it.detach()
                sceneCanvasLayers.removeAt(0)
            }
        }

        override fun stopRtmp() {
            checkMainThread()
            multiStream?.stopAndRelease() ?: stream?.stop()
        }

        override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
            checkMainThread()
            audioStopRequested.set(true)
            val mixer = audioMixerSession ?: run {
                completion(AudioStopStatus(true, true, true))
                return
            }
            mixer.stopAsync { result ->
                dispatchMain {
                    if (!result.completed) {
                        log(
                            OssLogLevel.ERROR,
                            "audio_cleanup_incomplete",
                            "Audio cleanup status: mixer=${result.mixerStopped}, capture=${result.captureStopped}, monitor=${result.monitorStopped}",
                            "AUDIO_GRAPH_CLEANUP_PENDING"
                        )
                    }
                    if (result.completed) AudioMixerControlBus.detach(mixer)
                    completion(
                        AudioStopStatus(
                            mixerStopped = result.mixerStopped,
                            captureStopped = result.captureStopped,
                            monitorStopped = result.monitorStopped
                        )
                    )
                }
            }
        }

        override fun stopProjection(): Boolean {
            checkMainThread()
            val service = projectionService() ?: return true
            return if (service.getActiveProjection() == null) {
                true
            } else {
                service.stopProjectionSession()
            }
        }

        override fun releaseRtmp() {
            checkMainThread()
            released = true
            screenCheck?.let(mainHandler::removeCallbacks)
            screenCheck = null
            multiStream?.release()
            multiStream = null
            stream?.release()
            stream = null
            singleDestinationProfile = null
            sceneProjectSubscription?.close()
            sceneProjectSubscription = null
            activeProject = null
            if (audioMixerSession?.state == OssAudioMixerSession.State.STOPPED) {
                audioMixerSession?.let(AudioMixerControlBus::detach)
                audioMixerSession = null
            }
        }

        private fun dispatchMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else mainHandler.post(action)
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "AndroidLiveRuntime lifecycle must run on the main thread"
            }
        }

        private fun log(
            level: OssLogLevel,
            event: String,
            message: String,
            errorCode: String? = null,
            error: Throwable? = null,
            destinationId: String? = null
        ) {
            OssLogger.log(
                level = level,
                subsystem = "OSS/Stream/Core",
                component = "AndroidLiveRuntime",
                event = event,
                message = message,
                errorCode = errorCode,
                context = OssLogContext(operationId = runtimeId, destinationId = destinationId),
                throwable = error
            )
        }

        companion object {
            private const val SCREEN_CHECK_INTERVAL_MS = 50L
            private const val MAX_SCREEN_CHECKS = 120
        }
    }
}
