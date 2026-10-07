package com.example.parentalagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream

class StreamService : Service() {
    private var reader: ImageReader? = null
    private var projection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var worker: Handler? = null
    private var lastFrameAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != -1 && resultData != null) {
            startCapture(resultCode, resultData)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        createChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Compartir pantalla activo")
            .setContentText("La pantalla se está compartiendo con autorización del dispositivo.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            1001,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val thread = HandlerThread("screen-capture")
        thread.start()
        worker = Handler(thread.looper)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 700) return@setOnImageAvailableListener
            lastFrameAt = now

            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = width + rowPadding / pixelStride
                val bitmap = android.graphics.Bitmap.createBitmap(
                    bitmapWidth,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val cropped = if (bitmapWidth != width) {
                    android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else bitmap

                val output = ByteArrayOutputStream()
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, output)
                SocketManager.sendScreenFrame(output.toByteArray())

                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
            } finally {
                image.close()
            }
        }, worker)

        virtualDisplay = projection?.createVirtualDisplay(
            "ControlParentalProjection",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            worker
        )

        projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, worker)

        SocketManager.sendStatus(org.json.JSONObject().put("screenSharing", true))
    }

    private fun stopCapture() {
        SocketManager.sendStatus(org.json.JSONObject().put("screenSharing", false))
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        worker?.looper?.quitSafely()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Supervisión y pantalla",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.parentalagent.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "parental_supervision"
    }
}
