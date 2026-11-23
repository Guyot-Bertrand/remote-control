package fr.remotecontrol.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Décodage d'un QR-code dans une image en luminance (plan Y), avec ZXing core seul. */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** [rowStride] : octets par ligne dans [luma] (≥ [width]) ; la dernière ligne peut ne pas être complétée. */
    /** Toute erreur de ZXing (y compris d'exécution, sur un tampon incohérent) donne `null` : l'analyse continue. */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        return try {
            val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
