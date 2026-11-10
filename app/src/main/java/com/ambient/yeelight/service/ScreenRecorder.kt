package com.ambient.yeelight.service

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.palette.graphics.Palette
import com.ambient.yeelight.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * @author alaishev
 * ScreenRecorder
 * Implement capturing image in loop and calculate dominant color
 * @param context           Application context
 * @param onChangeColorFunc Callback function called when dominant color was changed
 */
class ScreenRecorder(private val context: Context, private val onChangeColorFunc: (color: Int) -> Unit) {
    private var job: Job = Job()
    private var scope = CoroutineScope(Dispatchers.IO)

    private var mLastColor = 0x000000

    /**
     * Launch calculating dominant color in separate thread
     * @param data Intent after receiving MediaProjection Permission
     * @param interval delay between capturing images in milliseconds
     */
    fun start(data: Intent, interval: Long) {
        job = scope.launch {
            val imageReader = createImageReader(data)

            // TODO limit change events for 144 per minute
            while (true) {
                delay(interval)

                // Get next available image
                val bitmap = getNextImage(imageReader) ?: continue

                // Calculate dominant color
                val color = getDominantColor(bitmap)

                // Notify Listener if color was changed
                if (colorChanged(color)) {
                    mLastColor = color
                    onChangeColorFunc(color)
                }
            }
        }
    }

    /**
     * Just stop calculating dominant color thread
     */
    fun stop() {
        job.cancel()
    }

    /**
     * Create Virtual Display and Image Reader for capturing images
     * @param data Intent after receiving MediaProjection Permission
     * @return create instance of Image Reader
     */
    private fun createImageReader(data: Intent): ImageReader {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager?
        windowManager!!.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = mediaProjectionManager
            .getMediaProjection(-1, data) ?: throw IllegalStateException("No media projection")
        mediaProjection.createVirtualDisplay(
            "AmbientLightDisplay",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null)

        return imageReader
    }

    /**
     * acquire next available image from Reader and convert to BitMap
     * @param imageReader instance of Image Reader for capture image
     * @return image converted to BitMap
     */
    private fun getNextImage(imageReader: ImageReader): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null

        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        return bitmap
    }

    /**
     * Calculate dominant color of BitMap
     * @param bitmap Bitmap object
     * @return dominant color
     */
    private fun getDominantColor(bitmap: Bitmap): Int {
        val p = Palette.from(bitmap).generate()
        val dominant = p.getDominantColor(0x000000)

        // Just remove first two bits of alpha
        return dominant and 0x00FFFFFF
    }

    /**
     * Check if dominant color was changed significantly
     * @param color current dominant color
     * @return true if color was changed according to smooth rules
     */
    private fun colorChanged(color: Int): Boolean {

        if ((color == 0x000000) || (color == mLastColor)) {
            return false
        }

        // Initialize first color
        if (mLastColor == 0x000000) {
            return true
        }

        val deltaE = CIE76ColorDistanceCalculator.getColorDistance(color, mLastColor)
        logger.d(TAG, "Color = ${colorToHex(color)} / $color, deltaE = $deltaE")

        return (deltaE > 2.5)

    }

    /**
     * Convert rgb color to Hex representation
     * @param color rgb color
     * @return Hex representation of rgb color
     */
    private fun colorToHex(color: Int): String {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Integer.toHexString(Color.rgb(r, g, b))
    }

    companion object {
        const val TAG = "ScreenRecorder"
    }
}
