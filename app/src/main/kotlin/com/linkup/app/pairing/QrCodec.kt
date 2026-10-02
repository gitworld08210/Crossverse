package com.linkup.app.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR generation for pairing. The QR carries the FULL [com.linkup.core.pairing.PairingPayload]
 * JSON (secret included) — scanning requires physical proximity, which is the
 * trust assumption documented in :core `Pairing`. Scanning the camera side is
 * handled by zxing-android-embedded's `ScanContract` from the UI; this helper
 * only produces the bitmap to display.
 */
object QrCodec {

    /** Render [content] as a square QR [Bitmap] of [sizePx] pixels per side. */
    fun encodeToBitmap(content: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
