package fr.remotecontrol.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val size = 400

    /** QR-code généré par l'encodeur de ZXing, en luminance (noir 0, blanc 255). */
    private fun luma(text: String): ByteArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { i -> if (m[i % size, i / size]) 0 else 255.toByte() }
    }

    @Test fun decodes_tight_buffer() = assertEquals("rc://w/42", QrDecoder.decode(luma("rc://w/42"), size, size))

    @Test fun decodes_padded_rows() {
        val stride = 448
        val src = luma("rc://w/42")
        // Lignes complétées par 0x80, sauf la dernière (comme un plan Y de CameraX).
        val buf = ByteArray(stride * (size - 1) + size) { 0x80.toByte() }
        for (y in 0 until size) System.arraycopy(src, y * size, buf, y * stride, size)
        assertEquals("rc://w/42", QrDecoder.decode(buf, size, size, stride))
    }

    @Test fun plain_image_is_null() = assertNull(QrDecoder.decode(ByteArray(size * size) { 0x80.toByte() }, size, size))

    /** Tampon plus court que l'image annoncée : ZXing lève une exception d'exécution, l'analyse continue. */
    @Test fun short_buffer_is_null() = assertNull(QrDecoder.decode(ByteArray(10), size, size))

    /** Pas de ligne plus courte que l'image : refusé par ZXing (IllegalArgumentException), sans plantage. */
    @Test fun bad_stride_is_null() = assertNull(QrDecoder.decode(ByteArray(size * size), size, size, rowStride = size / 2))
}
