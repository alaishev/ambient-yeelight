package com.ambient.yeelight.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Display
import android.view.Surface
import com.ambient.yeelight.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Captures the screen into a small frame, [FRAME_WIDTH] wide and as high as the screen's aspect
 * ratio makes it. The system scales the screen down into a virtual display of that size, so the
 * app never copies a full-size picture.
 *
 * @param onFrame called on a background thread with each new frame; the bitmap is reused for the
 *   next frame, so it must be done with before onFrame returns
 * @param onStopped called on the main thread if the system or the user stops the capture, for
 *   example from the status bar; not called after [stop]
 */
class ScreenCapture(
    private val context: Context,
    private val onFrame: (frame: Bitmap) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null // The capture loop; it releases the projection when it ends

    @Volatile
    private var stopped = false // Set by stop(), so the projection's own stop is not reported

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopped) return

            logger.i(TAG, "Capture stopped by the system or the user")
            onStopped()
        }
    }

    /**
     * Starts capturing a frame every [intervalMs] with the permission [data] from
     * MediaProjectionManager. Returns false if the permission does not work.
     */
    fun start(data: Intent, intervalMs: Long): Boolean {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(Activity.RESULT_OK, data)

        if (projection == null) {
            logger.e(TAG, "No media projection: the capture permission does not work")
            return false
        }

        stopped = false

        val size = frameSize()
        val dpi = context.resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)

        // Android 14 throws in createVirtualDisplay() if no callback is registered yet
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val display = projection.createVirtualDisplay(
            "AmbientYeeLight",
            size.width,
            size.height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        logger.i(TAG, "Capturing ${size.width}x${size.height} every $intervalMs ms")
        job = scope.launch {
            try {
                captureFrames(reader, size, intervalMs)
            }
            finally {
                display?.release()
                reader.close()
                projection.unregisterCallback(callback)
                projection.stop()
                logger.i(TAG, "Capture released")
            }
        }
        return true
    }

    /** Stops capturing and releases the projection; [onStopped] is not called. */
    fun stop() {
        stopped = true
        job?.cancel()
    }

    /** Every [intervalMs], copies the newest image of [reader] to the frame and passes it on. */
    private suspend fun captureFrames(reader: ImageReader, size: Size, intervalMs: Long) {
        val frame = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val pixels = ByteArray(size.width * size.height * BYTES_PER_PIXEL) // Rows with no padding
        val rowBytes = size.width * BYTES_PER_PIXEL

        while (true) {
            delay(intervalMs)

            val image = reader.acquireLatestImage() ?: continue // No new image since the last one

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer

                // An image row may be longer than the frame's: copy only the pixels of each row
                for (row in 0 until size.height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(pixels, row * rowBytes, rowBytes)
                }
            }
            finally {
                image.close() // The reader holds only 2 images: an unclosed one stops the capture
            }
            frame.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            onFrame(frame)
        }
    }

    /** [FRAME_WIDTH] wide, as high as the screen's aspect ratio in its current orientation. */
    private fun frameSize(): Size {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val turned = display.rotation == Surface.ROTATION_90 ||
            display.rotation == Surface.ROTATION_270
        val width = if (turned) mode.physicalHeight else mode.physicalWidth
        val height = if (turned) mode.physicalWidth else mode.physicalHeight

        return Size(FRAME_WIDTH, FRAME_WIDTH * height / width)
    }

    private companion object {
        const val TAG = "ScreenCapture"
        // 160x90 for a 16:9 screen: 14 400 pixels are plenty to find the dominant color of the
        // whole screen
        const val FRAME_WIDTH = 160 // px
        const val BYTES_PER_PIXEL = 4 // RGBA_8888
    }
}
