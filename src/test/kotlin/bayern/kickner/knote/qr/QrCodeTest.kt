package bayern.kickner.knote.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SHORT_URL = "https://knote.kickner.bayern/example"
private val LONG_URL = "https://knote.kickner.bayern/" + "abschnitt-mit-langem-namen/".repeat(4) + "a".repeat(20)

private fun decode(image: BufferedImage): String {
    val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
    return MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
}

private fun image(png: ByteArray): BufferedImage = ImageIO.read(png.inputStream())

private fun scaled(image: BufferedImage, factor: Double): BufferedImage {
    val width = (image.width * factor).toInt()
    return BufferedImage(width, width, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().drawImage(image, 0, 0, width, width, null) }
}

class QrCodeTest {

    @Test
    fun `the code scans despite the logo`() {
        listOf(SHORT_URL, LONG_URL).forEach { url -> assertEquals(url, decode(image(qrCodePng(url))), url) }
    }

    @Test
    fun `the code still scans when shown small`() {
        listOf(0.5, 0.3).forEach { factor -> assertEquals(SHORT_URL, decode(scaled(image(qrCodePng(SHORT_URL)), factor)), "factor $factor") }
    }

    @Test
    fun `the code is a square png with white quiet zone and the logo in the middle`() {
        val image = image(qrCodePng(SHORT_URL))

        assertEquals(image.width, image.height)
        assertEquals(Color.WHITE.rgb, image.getRGB(10, 10))
        val center = Color(image.getRGB(image.width / 2, image.height / 2))
        val colored = (0 until 40).flatMap { dx -> (0 until 40).map { dy -> Color(image.getRGB(image.width / 2 - 20 + dx, image.height / 2 - 20 + dy)) } }
            .any { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) > 60 }
        assertTrue(colored, "no colored logo pixels around the center, center is $center")
    }
}
