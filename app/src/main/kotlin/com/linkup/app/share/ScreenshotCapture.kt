package com.linkup.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.linkup.core.model.TransferKind
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Screenshot capture via [MediaProjection].
 *
 * MediaProjection requires an explicit, per-session user consent dialog: the UI
 * launches [newPermissionIntent] with `ActivityResultContracts.StartActivityForResult`,
 * then passes the (resultCode, data) to [capture]. We capture a single frame off
 * a one-shot [VirtualDisplay] + [ImageReader], encode it to PNG, and hand the
 * bytes to [FileShare] so the screenshot travels as a sealed SCREENSHOT envelope
 * over whichever transport is active (LAN or relay).
 */
class ScreenshotCapture(
    private val context: Context,
    private val fileShare: FileShare
) {

    private val projectionManager: MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** Intent the Activity launches to request the one-time capture consent. */
    fun newPermissionIntent(): Intent = projectionManager.createScreenCaptureIntent()

    /**
     * Capture one frame using the consent [resultCode]/[data] from the permission
     * dialog, encode it to PNG, and send it to all paired devices as a
     * SCREENSHOT transfer. Returns the PNG byte size, or null on failure.
     *
     * NOTE: on API 29+ MediaProjection must be started from a running foreground
     * service of type mediaProjection; [com.linkup.app.service.SyncService]
     * provides a foreground context for this.
     */
    suspend fun capture(resultCode: Int, data: Intent): Int? {
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: return null
        val metrics = displayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val bitmap = grabFrame(projection, width, height, density) ?: run {
            projection.stop()
            return null
        }
        projection.stop()

        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val name = "screenshot-${System.currentTimeMillis()}.png"
        fileShare.sendBytes(name, "image/png", png, kind = TransferKind.SCREENSHOT)
        return png.size
    }

    private suspend fun grabFrame(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val handler = Handler(Looper.getMainLooper())
        var display: VirtualDisplay? = null

        reader.setOnImageAvailableListener({ r ->
            val image: Image? = r.acquireLatestImage()
            if (image != null && cont.isActive) {
                val bitmap = imageToBitmap(image, width)
                image.close()
                display?.release()
                reader.close()
                cont.resume(bitmap)
            } else {
                image?.close()
            }
        }, handler)

        display = projection.createVirtualDisplay(
            "linkup-screenshot",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        cont.invokeOnCancellation {
            runCatching { display?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
        }
    }

    private fun imageToBitmap(image: Image, width: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, image.height)
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
