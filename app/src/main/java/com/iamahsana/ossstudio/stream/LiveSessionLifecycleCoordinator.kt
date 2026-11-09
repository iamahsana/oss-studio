package com.iamahsana.ossstudio.stream

import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID

/**
 * Enforces teardown ordering across scene capture, RTMP, and MediaProjection.
 *
 * Ownership is deliberately split:
 * - [ScreenLayer] owns the VirtualDisplay and GL filter.
 * - [StreamPipeline] owns RootEncoder codecs, GL worker, and network session.
 * - [ProjectionSession] is the foreground service boundary that ends the
 *   Android MediaProjection token.
 *
 * Every cleanup stage is attempted even if an earlier stage fails. This keeps
 * a failed GL detach from preventing RTMP shutdown or projection revocation.
 * Call from the main thread.
 */
class LiveSessionLifecycleCoordinator(
    private val screenLayer: ScreenLayer,
    private val streamPipeline: StreamPipeline,
    private val projectionSession: ProjectionSession,
    private val diagnosticLog: (OssLogLevel, String, String, String?) -> Unit = { level, event, message, errorCode ->
        OssLogger.log(
            level = level,
            subsystem = "OSS/Stream/Core",
            component = "LiveSessionLifecycleCoordinator",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = "lifecycle")
        )
    }
) {
    fun interface ScreenLayer {
        fun detach()
    }

    interface StreamPipeline {
        fun stop()
        fun release()
    }

    fun interface ProjectionSession {
        fun stop()
    }

    data class ShutdownResult(
        val completed: Boolean,
        val failedStages: List<String>
    )

    private val operationId = UUID.randomUUID().toString()
    private var shutdownResult: ShutdownResult? = null

    /**
     * Teardown order is intentional:
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
        activeFailures = failures

        runStage("screen_layer_detach", "Share Screen layer detached") {
            screenLayer.detach()
        }
        runStage("rtmp_stop", "RTMP pipeline stopped") {
            streamPipeline.stop()
        }
        runStage("projection_stop", "MediaProjection service stopped") {
            projectionSession.stop()
        }
        runStage("rtmp_release", "RTMP pipeline resources released") {
            streamPipeline.release()
        }

        activeFailures = null
        return ShutdownResult(
            completed = failures.isEmpty(),
            failedStages = failures.toList()
        ).also { shutdownResult = it }
    }

    private inline fun runStage(
        stage: String,
        successMessage: String,
        action: () -> Unit
    ) {
        try {
            action()
            log(OssLogLevel.INFO, "${stage}_completed", successMessage)
        } catch (error: Exception) {
            // Continue teardown: later owners still need their release signal.
            log(
                OssLogLevel.ERROR,
                "${stage}_failed",
                error.message ?: "Lifecycle teardown stage failed",
                "${stage.uppercase()}_FAILED"
            )
            failuresSink(stage)
        }
    }

    // Isolated hook keeps stage failure collection local to a shutdown call.
    private var activeFailures: MutableList<String>? = null

    private fun failuresSink(stage: String) {
        activeFailures?.add(stage)
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null
    ) {
        diagnosticLog(level, event, message, errorCode)
    }
}
