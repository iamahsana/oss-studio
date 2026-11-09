package com.iamahsana.ossstudio.scene.source.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID

/**
 * Owns the Android foreground-service lifetime for one user-approved
 * MediaProjection session. UI obtains the active projection through LocalBinder.
 */
class ShareScreenSessionService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ShareScreenSessionService = this@ShareScreenSessionService

        fun observeProjectionState(listener: (Boolean) -> Unit) {
            projectionStateListener = listener
            listener(mediaProjection != null)
        }

        fun clearProjectionStateListener() {
            projectionStateListener = null
        }
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var projectionStateListener: ((Boolean) -> Unit)? = null
    private var operationId: String = UUID.randomUUID().toString()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession(stopProjection = true)
                return START_NOT_STICKY
            }
            ACTION_START -> startSession(intent)
            else -> {
                log(OssLogLevel.WARN, "invalid_service_action", "Share Screen service received an unknown action")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseProjection(stopProjection = true)
        log(OssLogLevel.INFO, "service_destroyed", "Share Screen service destroyed and projection released")
        super.onDestroy()
    }

    fun getActiveProjection(): MediaProjection? = mediaProjection

    private fun startSession(intent: Intent) {
        if (mediaProjection != null) {
            log(OssLogLevel.WARN, "duplicate_start_ignored", "A Share Screen projection is already active")
            return
        }

        operationId = UUID.randomUUID().toString()
        try {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
                ?: error("MediaProjection permission result data is missing")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = requireNotNull(manager.getMediaProjection(resultCode, resultData)) {
                "Android did not create a MediaProjection session"
            }
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    log(OssLogLevel.INFO, "projection_stopped", "Android MediaProjection session stopped")
                    finishSession(stopProjection = false)
                }
            }
            projection.registerCallback(callback, android.os.Handler(mainLooper))
            mediaProjection = projection
            projectionCallback = callback
            notifyProjectionState(active = true)
            log(OssLogLevel.INFO, "projection_ready", "Share Screen permission session is ready")
        } catch (error: Exception) {
            log(
                OssLogLevel.ERROR,
                "projection_start_failed",
                error.message ?: "Unable to start MediaProjection session",
                "SCREEN_PROJECTION_SERVICE_START_FAILED"
            )
            finishSession(stopProjection = true)
        }
    }

    private fun finishSession(stopProjection: Boolean) {
        releaseProjection(stopProjection)
        notifyProjectionState(active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log(OssLogLevel.INFO, "service_session_finished", "Share Screen foreground session released")
    }

    private fun notifyProjectionState(active: Boolean) {
        runCatching { projectionStateListener?.invoke(active) }
            .onFailure {
                log(
                    OssLogLevel.WARN,
                    "projection_state_listener_failed",
                    it.message ?: "Unable to notify Share Screen state observer",
                    "SCREEN_STATE_OBSERVER_FAILED"
                )
            }
    }

    private fun releaseProjection(stopProjection: Boolean) {
        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null

        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        if (stopProjection) {
            runCatching { projection?.stop() }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OSS Share Screen",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while OSS captures the screen for a LIVE scene"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("OSS Share Screen aktif")
            .setContentText("Layar sedang digunakan sebagai source scene LIVE")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Permissions/Screen",
            component = "ShareScreenSessionService",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId)
        )
    }

    private fun Intent.parcelableIntent(name: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    companion object {
        private const val CHANNEL_ID = "oss-share-screen"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.iamahsana.ossstudio.action.START_SCREEN_CAPTURE"
        private const val ACTION_STOP = "com.iamahsana.ossstudio.action.STOP_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ShareScreenSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ShareScreenSessionService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }
}
