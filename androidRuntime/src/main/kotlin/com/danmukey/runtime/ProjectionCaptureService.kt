package com.danmukey.runtime

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.danmukey.shared.visual.ArgbFrame
import com.danmukey.shared.visual.ScreenCaptureSource

/** Holds the user-approved MediaProjection session for explicit calibration and diagnostics. */
class ProjectionCaptureService : Service() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    @Volatile
    private var captureRequested = false
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("DanmuKeyProjectionCapture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.parcelableIntentExtra(EXTRA_RESULT_DATA)
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    requestServiceStop("projection_permission_missing")
                    return START_NOT_STICKY
                }
                startProjection(
                    resultCode = resultCode,
                    resultData = resultData,
                    captureInitialFrame = intent.getBooleanExtra(EXTRA_CAPTURE_INITIAL_FRAME, false),
                )
            }

            ACTION_STOP -> requestServiceStop("projection_stop_requested")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        ProjectionCaptureSessionRegistry.detach("projection_service_destroyed")
        val cleanupPosted = captureHandler.post {
            stopProjectionOnCaptureThread("projection_service_destroyed")
            captureThread.quitSafely()
        }
        if (!cleanupPosted) {
            Log.e(TAG, "Capture thread rejected projection cleanup")
            captureThread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureHandler.post {
            if (stopping || mediaProjection == null) return@post
            reconfigureCapturePipelineOnCaptureThread()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, resultData: Intent, captureInitialFrame: Boolean) {
        captureHandler.post {
            if (stopping) return@post
            if (mediaProjection != null) {
                if (captureInitialFrame) requestFrameOnCaptureThread()
                return@post
            }

            runCatching {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val projection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) {
                    "MediaProjection permission result could not create a session"
                }
                mediaProjection = projection
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            handleProjectionStoppedOnCaptureThread()
                        }
                    },
                    captureHandler,
                )
                createInitialCapturePipelineOnCaptureThread(projection)
                ProjectionCaptureSessionRegistry.attach(::requestFrame)
                if (captureInitialFrame) requestFrameOnCaptureThread()
            }.onFailure { error ->
                failCapturePipelineOnCaptureThread("projection_start_failed", error)
            }
        }
    }

    private fun createInitialCapturePipelineOnCaptureThread(projection: MediaProjection) {
        check(virtualDisplay == null) { "VirtualDisplay already exists" }
        val configuration = currentCaptureConfiguration()
        screenWidth = configuration.screenWidth
        screenHeight = configuration.screenHeight
        captureWidth = configuration.captureWidth
        captureHeight = configuration.captureHeight
        imageReader = createImageReaderOnCaptureThread(captureWidth, captureHeight)
        virtualDisplay = checkNotNull(
            projection.createVirtualDisplay(
                "RaisedHandDetection",
                captureWidth,
                captureHeight,
                configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                captureHandler,
            ),
        ) { "Unable to create projection VirtualDisplay" }
    }

    private fun reconfigureCapturePipelineOnCaptureThread() {
        val display = virtualDisplay ?: run {
            failCapturePipelineOnCaptureThread(
                "projection_display_missing_during_reconfigure",
                IllegalStateException("VirtualDisplay is unavailable"),
            )
            return
        }
        ProjectionCaptureSessionRegistry.detach("projection_configuration_changed")
        captureRequested = false
        if (!setDisplaySurfaceOnCaptureThread(display, null, "projection_surface_detach_failed")) return

        val oldReader = imageReader
        runCatching { oldReader?.close() }.onFailure { error ->
            failCapturePipelineOnCaptureThread("projection_reader_close_failed", error)
            return
        }
        imageReader = null

        runCatching {
            val configuration = currentCaptureConfiguration()
            display.resize(
                configuration.captureWidth,
                configuration.captureHeight,
                configuration.densityDpi,
            )
            screenWidth = configuration.screenWidth
            screenHeight = configuration.screenHeight
            captureWidth = configuration.captureWidth
            captureHeight = configuration.captureHeight
            imageReader = createImageReaderOnCaptureThread(captureWidth, captureHeight)
        }.onFailure { error ->
            failCapturePipelineOnCaptureThread("projection_reconfigure_failed", error)
            return
        }
        ProjectionCaptureSessionRegistry.attach(::requestFrame)
    }

    private fun currentCaptureConfiguration(): CapturePipelineConfiguration {
        val metrics = resources.displayMetrics
        val currentScreenWidth = metrics.widthPixels
        val currentScreenHeight = metrics.heightPixels
        val target = chooseCaptureResolution(
            screenWidth = currentScreenWidth,
            screenHeight = currentScreenHeight,
            density = metrics.density,
        )
        return CapturePipelineConfiguration(
            screenWidth = currentScreenWidth,
            screenHeight = currentScreenHeight,
            captureWidth = target.width,
            captureHeight = target.height,
            densityDpi = (metrics.densityDpi.toLong() * target.width / currentScreenWidth)
                .toInt()
                .coerceAtLeast(1),
        )
    }

    private fun createImageReaderOnCaptureThread(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            reader.setOnImageAvailableListener({ source ->
                if (stopping || source !== imageReader) return@setOnImageAvailableListener
                val image = runCatching { source.acquireLatestImage() }.getOrElse { error ->
                    failCapturePipelineOnCaptureThread("projection_image_acquire_failed", error)
                    return@setOnImageAvailableListener
                } ?: return@setOnImageAvailableListener
                var processingError: Throwable? = null
                try {
                    if (captureRequested) {
                        captureRequested = false
                        val frame = image.toArgbFrame(
                            width = width,
                            height = height,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                        )
                        ProjectionCaptureSessionRegistry.publish(frame)
                    }
                } catch (error: Throwable) {
                    processingError = error
                } finally {
                    runCatching { image.close() }.onFailure { closeError ->
                        if (processingError == null) processingError = closeError
                    }
                }
                processingError?.let { error ->
                    failCapturePipelineOnCaptureThread("projection_image_processing_failed", error)
                    return@setOnImageAvailableListener
                }
                if (!captureRequested) {
                    val display = virtualDisplay ?: return@setOnImageAvailableListener
                    setDisplaySurfaceOnCaptureThread(display, null, "projection_surface_detach_failed")
                }
            }, captureHandler)
        } catch (error: Throwable) {
            runCatching { reader.close() }.onFailure(error::addSuppressed)
            throw error
        }
        return reader
    }

    private fun requestServiceStop(reason: String) {
        stopping = true
        ProjectionCaptureSessionRegistry.detach(reason)
        val cleanupPosted = captureHandler.post {
            stopProjectionOnCaptureThread(reason)
            stopSelf()
        }
        if (!cleanupPosted) {
            Log.e(TAG, "Capture thread rejected projection stop: $reason")
            stopSelf()
        }
    }

    private fun handleProjectionStoppedOnCaptureThread() {
        if (stopping && mediaProjection == null) return
        stopping = true
        val reason = "projection_session_stopped"
        releaseCaptureResourcesOnCaptureThread(reason)
        mediaProjection = null
        stopSelf()
    }

    private fun failCapturePipelineOnCaptureThread(reason: String, error: Throwable) {
        Log.e(TAG, "Projection capture pipeline failed: $reason", error)
        stopping = true
        stopProjectionOnCaptureThread(reason)
        stopSelf()
    }

    private fun stopProjectionOnCaptureThread(reason: String) {
        stopping = true
        releaseCaptureResourcesOnCaptureThread(reason)

        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.stop() }.onFailure { error ->
            Log.e(TAG, "Failed to stop MediaProjection: $reason", error)
        }
    }

    /** Must run on [captureHandler] so display and reader operations never race each other. */
    private fun releaseCaptureResourcesOnCaptureThread(reason: String) {
        ProjectionCaptureSessionRegistry.detach(reason)
        captureRequested = false

        val display = virtualDisplay
        runCatching { display?.surface = null }.onFailure { error ->
            Log.e(TAG, "Failed to detach projection Surface: $reason", error)
        }

        val reader = imageReader
        runCatching { reader?.close() }.onFailure { error ->
            Log.e(TAG, "Failed to close projection ImageReader: $reason", error)
        }
        runCatching { display?.release() }.onFailure { error ->
            Log.e(TAG, "Failed to release projection VirtualDisplay: $reason", error)
        }

        imageReader = null
        virtualDisplay = null
        screenWidth = 0
        screenHeight = 0
        captureWidth = 0
        captureHeight = 0
    }

    private data class CapturePipelineConfiguration(
        val screenWidth: Int,
        val screenHeight: Int,
        val captureWidth: Int,
        val captureHeight: Int,
        val densityDpi: Int,
    )

    private fun requestFrame() {
        captureHandler.post {
            requestFrameOnCaptureThread()
        }
    }

    private fun requestFrameOnCaptureThread() {
        if (stopping || captureRequested) return
        val reader = imageReader ?: run {
            failCapturePipelineOnCaptureThread(
                "projection_reader_missing",
                IllegalStateException("ImageReader is unavailable"),
            )
            return
        }
        val display = virtualDisplay ?: run {
            failCapturePipelineOnCaptureThread(
                "projection_display_missing",
                IllegalStateException("VirtualDisplay is unavailable"),
            )
            return
        }
        val surface = runCatching { reader.surface }.getOrElse { error ->
            failCapturePipelineOnCaptureThread("projection_reader_surface_failed", error)
            return
        }
        captureRequested = true
        if (!setDisplaySurfaceOnCaptureThread(display, surface, "projection_surface_attach_failed")) {
            captureRequested = false
        }
    }

    private fun setDisplaySurfaceOnCaptureThread(
        display: VirtualDisplay,
        surface: Surface?,
        failureReason: String,
    ): Boolean = runCatching {
        display.surface = surface
    }.fold(
        onSuccess = { true },
        onFailure = { error ->
            failCapturePipelineOnCaptureThread(failureReason, error)
            false
        },
    )

    private fun Image.toArgbFrame(
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): ArgbFrame {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val pixels = IntArray(width * height)
            paddedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return ArgbFrame(
                width = width,
                height = height,
                pixels = pixels,
                capturedAt = System.currentTimeMillis(),
                source = ScreenCaptureSource.MediaProjection,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            )
        } finally {
            paddedBitmap.recycle()
        }
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, ProjectionCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("怪团建正在识别举手标志")
            .setContentText("按需读取低分辨率画面；点击停止可立即结束")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopPendingIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "举手标志识别",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntentExtra(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            getParcelableExtra(name)
        }

    companion object {
        internal const val PREFERENCES_NAME = "danmukey_projection"
        const val ACTION_START = "com.danmukey.runtime.action.START_PROJECTION"
        const val ACTION_STOP = "com.danmukey.runtime.action.STOP_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CAPTURE_INITIAL_FRAME = "capture_initial_frame"
        private const val TAG = "DanmuProjection"
        private const val NOTIFICATION_CHANNEL_ID = "danmukey_projection"
        private const val NOTIFICATION_ID = 4201

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            captureInitialFrame: Boolean = false,
        ) {
            val intent = Intent(context, ProjectionCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_CAPTURE_INITIAL_FRAME, captureInitialFrame)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionCaptureService::class.java))
        }
    }
}
