package com.iamahsana.ossstudio.logging

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class OssLogLevel(val priority: Int, val androidPriority: Int) {
    VERBOSE(0, Log.VERBOSE),
    DEBUG(1, Log.DEBUG),
    INFO(2, Log.INFO),
    WARN(3, Log.WARN),
    ERROR(4, Log.ERROR),
    FATAL(5, Log.ASSERT)
}

data class OssLogContext(
    val sessionId: String? = null,
    val projectId: String? = null,
    val sceneId: String? = null,
    val sourceId: String? = null,
    val channelId: String? = null,
    val destinationId: String? = null,
    val extensionId: String? = null,
    val operationId: String? = null
)

/**
 * Central, bounded, asynchronous diagnostic logger.
 * Initialize once from Application or the launcher Activity before feature startup.
 */
object OssLogger {
    private const val TAG = "OSS/Diagnostics"
    private const val LOG_FILE_NAME = "oss-diagnostics.jsonl"
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private val MIN_LEVEL = OssLogLevel.DEBUG

    private val droppedRecords = AtomicLong(0)
    @Volatile private var logFile: File? = null
    @Volatile private var executor: ThreadPoolExecutor? = null

    @Synchronized
    fun initialize(context: Context, queueCapacity: Int = 1024) {
        if (executor != null) return
        val directory = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
        logFile = File(directory, LOG_FILE_NAME)
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity.coerceAtLeast(64)),
            { runnable -> Thread(runnable, "oss-log-writer").apply { isDaemon = true } },
            { _, _ -> droppedRecords.incrementAndGet() }
        )
    }

    fun log(
        level: OssLogLevel,
        subsystem: String,
        component: String,
        event: String,
        message: String,
        errorCode: String? = null,
        context: OssLogContext = OssLogContext(),
        throwable: Throwable? = null
    ) {
        if (level.priority < MIN_LEVEL.priority) return
        val safeSubsystem = OssLogRedactor.redact(subsystem)
        val safeComponent = OssLogRedactor.redact(component)
        val safeMessage = OssLogRedactor.redact(message)
        val safeStack = throwable?.let(::stackTrace)?.let(OssLogRedactor::redact)

        val record = JSONObject().apply {
            put("timestamp", Instant.now().toString())
            put("level", level.name)
            put("subsystem", safeSubsystem)
            put("component", safeComponent)
            put("event", OssLogRedactor.redact(event))
            put("errorCode", errorCode ?: JSONObject.NULL)
            put("message", safeMessage)
            put("sessionId", context.sessionId ?: JSONObject.NULL)
            put("projectId", context.projectId ?: JSONObject.NULL)
            put("sceneId", context.sceneId ?: JSONObject.NULL)
            put("sourceId", context.sourceId ?: JSONObject.NULL)
            put("channelId", context.channelId ?: JSONObject.NULL)
            put("destinationId", context.destinationId ?: JSONObject.NULL)
            put("extensionId", context.extensionId ?: JSONObject.NULL)
            put("operationId", context.operationId ?: JSONObject.NULL)
            put("exception", throwable?.javaClass?.name ?: JSONObject.NULL)
            put("stackTrace", safeStack ?: JSONObject.NULL)
            put("droppedBeforeThisRecord", droppedRecords.getAndSet(0))
        }.toString()

        val writer = executor
        if (writer == null) {
            Log.w(TAG, "Logger used before initialize; event dropped")
            return
        }
        try {
            writer.execute {
                Log.println(level.androidPriority, safeSubsystem.take(23), safeMessage)
                appendRecord(record)
            }
        } catch (_: RuntimeException) {
            droppedRecords.incrementAndGet()
        }
    }

    private fun appendRecord(record: String) {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() + record.length + 1 > MAX_FILE_BYTES) {
                val previous = File(file.parentFile, "$LOG_FILE_NAME.1")
                if (previous.exists()) previous.delete()
                file.renameTo(previous)
            }
            file.appendText(record + "\n", Charsets.UTF_8)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist diagnostic record", error)
        }
    }

    private fun stackTrace(error: Throwable): String {
        val output = StringWriter()
        error.printStackTrace(PrintWriter(output))
        return output.toString()
    }
}

internal object OssLogRedactor {
    private val authorizationHeader = Regex(
        "(?i)(authorization\\s*[=:]\\s*)(?:bearer\\s+)?([^&\\s,;]+)"
    )
    private val sensitiveAssignment = Regex(
        "(?i)(stream[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|cookie|secret)(\\s*[=:]\\s*)([^&\\s,;]+)"
    )
    private val rtmpUrl = Regex("(?i)rtmps?://[^\\s\\\"'<>]+")

    fun redact(value: String): String {
        val withoutStreamUrls = rtmpUrl.replace(value, "[REDACTED_STREAM_URL]")
        val withoutAuthorization = authorizationHeader.replace(withoutStreamUrls) {
            it.groupValues[1] + "[REDACTED]"
        }
        return sensitiveAssignment.replace(withoutAuthorization) {
            it.groupValues[1] + it.groupValues[2] + "[REDACTED]"
        }
    }
}
