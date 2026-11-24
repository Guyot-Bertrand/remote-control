package fr.remotecontrol.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Analyse CameraX (sur l'exécuteur d'analyse) : copie le plan Y, le décode, et transmet le texte trouvé.
 * L'[ImageProxy] est toujours fermé, sinon CameraX cesse de livrer des images.
 */
class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val data = plane.buffer
            data.rewind()
            val size = data.remaining()
            if (buffer.size < size) buffer = ByteArray(size)
            data.get(buffer, 0, size)
            QrDecoder.decode(buffer, it.width, it.height, plane.rowStride)?.let(onText)
        }
    }
}
