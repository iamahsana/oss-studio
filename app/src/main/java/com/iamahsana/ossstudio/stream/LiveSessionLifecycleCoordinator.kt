package com.iamahsana.ossstudio.stream

import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID

/**
 * Enforces teardown ordering across scene capture, RTMP, and MediaProjection.
 *
 * Ownership:
 * - ScreenLayer owns the VirtualDisplay and GL filter.
 * - StreamPipeline owns RootEncoder codecs, GL worker, and network session.
 * - ProjectionSession is the foreground-service boundary that ends the
 *   Android MediaProjection token.
 *
 * Every cleanup stage is attempted even if an earlier stage fails. Logging is
 * deliberately best-effort and must never interrupt resource teardown.
 * Call from the main thread.
 */
class LiveSessionLifecycleCoordinator(
    private val screenLayer: ScreenLayer,
    private val streamPipeline: StreamPipeline,
    private val projectionSession: ProjectionSession,
    private val diagnosticLog: ((OssLogLevel, String, String, String?) -> Unit)? = null
) {
    fun interface ScreenLayer {
        fun detach()
    }

    interface StreamPipeline {
        fun stop()
        fun release()

        /** Optional asynchronous audio teardown, completed before RTMP stops. */
        fun stopAudio(completion: (Boolean) -> Unit) {
            completion(true)
        }
    }

    fun interface ProjectionSession {
        /** Returns false when the owner cannot confirm projection release. */
        fun stop(): Boolean
    }

    data class ShutdownResult(
        val completed: Boolean,
        val failedStages: List<String>
    )

    private val operationId = UUID.randomUUID().toString()

    @Volatile
    private var shutdownResult: ShutdownResult? = null

    private var shutdownInProgress = false
    private val pendingCallbacks = mutableListOf<(ShutdownResult) -> Unit>()

    /**
     * Teardown order:
     * 1. Release VirtualDisplay and enqueue GL layer removal.
     * 2. Stop RTMP and its GL/encoder workers.
     * 3. Revoke MediaProjection through its owning foreground service.
     * 4. Release final RootEncoder resources.
     *
     * Repeated calls return the first result and never double-release owners.
     */
    @Synchronized
    fun shutdown(): ShutdownResult {
        shutdownResult?.let { return it }
        val failures = mutableListOf<String>()

        runStage(failures, "screen_layer_detach", "Share Screen layer detached") {
            screenLayer.detach()
        }
        runStage(failures, "rtmp_stop", "RTMP pipeline stopped") {
            streamPipeline.stop()
        }
        runStage(failures, "projection_stop", "MediaProjection service stopped") {
            check(projectionSession.stop()) {
                "MediaProjection owner could not confirm release"
            }
        }
        runStage(failures, "rtmp_release", "RTMP pipeline resources released") {
            streamPipeline.release()
        }

        return ShutdownResult(
            completed = failures.isEmpty(),
            failedStages = failures.toList()
        ).also { shutdownResult = it }
    }

    /**
     * Non-blocking teardown variant. The audio graph must stop its mixer worker
     * and capture sources on a background executor before RTMP/GL and the
     * projection are stopped. The completion is delivered by the audio owner;
     * Android adapters must marshal it back to the main thread.
     */
    fun shutdownAsync(onComplete: (ShutdownResult) -> Unit) {
        val failures = mutableListOf<String>()
        synchronized(this) {
            shutdownResult?.let {
                onComplete(it)
                return
            }
            if (shutdownInProgress) {
                pendingCallbacks += onComplete
                return
            }
            shutdownInProgress = true
        }

        runStage(failures, "screen_layer_detach", "Share Screen layer detached") {
            screenLayer.detach()
        }

        val callbackOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishAfterAudio: (Boolean) -> Unit = { audioStopped ->
            if (callbackOnce.compareAndSet(false, true)) {
                if (!audioStopped) {
                    failures += "audio_stop"
                    logSafely(
                        OssLogLevel.ERROR,
                        "audio_stop_failed",
                        "Audio mixer/capture teardown did not complete",
                        "AUDIO_STOP_FAILED"
                    )
                } else {
                    logSafely(OssLogLevel.INFO, "audio_stop_completed", "Audio mixer/capture teardown completed")
                }

                runStage(failures, "rtmp_stop", "RTMP pipeline stopped") {
                    streamPipeline.stop()
                }
                runStage(failures, "projection_stop", "MediaProjection service stopped") {
                    check(projectionSession.stop()) {
                        "MediaProjection owner could not confirm release"
                    }
                }
                runStage(failures, "rtmp_release", "RTMP pipeline resources released") {
                    streamPipeline.release()
                }

                val result = ShutdownResult(failures.isEmpty(), failures.toList())
                val callbacks = synchronized(this) {
                    shutdownResult = result
                    shutdownInProgress = false
                    val copy = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    copy
                }
                runCatching { onComplete(result) }
                callbacks.forEach { callback -> runCatching { callback(result) } }
            }
        }

        try {
            streamPipeline.stopAudio(finishAfterAudio)
        } catch (_: Exception) {
            finishAfterAudio(false)
        }
    }

    private fun runStage(
        failures: MutableList<String>,
        stage: String,
        successMessage: String,
        action: () -> Unit
    ) {
        try {
            action()
            logSafely(OssLogLevel.INFO, "${stage}_completed", successMessage)
        } catch (error: Exception) {
            // Record first; even a broken diagnostics sink must not block later cleanup.
            failures += stage
            logSafely(
                OssLogLevel.ERROR,
                "${stage}_failed",
                error.message ?: "Lifecycle teardown stage failed",
                "${stage.uppercase()}_FAILED"
            )
        }
    }

    private fun logSafely(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null
    ) {
        runCatching {
            val sink = diagnosticLog
            if (sink != null) {
                sink(level, event, message, errorCode)
            } else {
                OssLogger.log(
                    level = level,
                    subsystem = "OSS/Stream/Core",
                    component = "LiveSessionLifecycleCoordinator",
                    event = event,
                    message = message,
                    errorCode = errorCode,
                    context = OssLogContext(operationId = operationId)
                )
            }
        }
    }
}
