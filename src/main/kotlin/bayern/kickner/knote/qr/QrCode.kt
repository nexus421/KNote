package bayern.kickner.knote.qr

import qrcode.QRCode
import qrcode.color.Colors
import qrcode.raw.ErrorCorrectionLevel
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Width of one QR module in pixels. A typical sheet URL gives a code of about 500 pixels, enough to print. */
private const val MODULE_PIXELS = 14

/** White border around the code in modules, as the QR standard asks for. */
private const val QUIET_ZONE_MODULES = 4

/** Share of the code width covered by the logo. Error correction level high restores far more than these cells. */
private const val LOGO_SHARE = 0.22

/** White frame around the logo as share of the logo size, keeps the logo apart from the modules. */
private const val LOGO_FRAME_SHARE = 0.1

/** Loads resources through KNote's own class loader, a JDK class would not see them. */
private object Resources

/** The K logo from the static resources, read once. Null if it is missing, the code then has no logo. */
private val logo: BufferedImage? by lazy {
    runCatching { Resources::class.java.getResourceAsStream("/static/logo.jpg")?.use(ImageIO::read) }.getOrNull()
}

/**
 * A PNG with a QR code for [url]: black modules on white, the K logo on a white square in the middle and the quiet
 * zone around it. Error correction level high, so the code still scans although the logo covers its center.
 */
fun qrCodePng(url: String): ByteArray {
    val codeSize = baseBuilder().build(url).canvasSize
    val logoSize = (codeSize * LOGO_SHARE).toInt()
    val framedLogo = logo?.let { framed(it, logoSize) }
    val builder = baseBuilder()
    if (framedLogo != null) builder.withLogo(png(framedLogo), logoSize, logoSize, clearLogoArea = true)
    val code = ImageIO.read(builder.build(url).renderToBytes().inputStream())
    return png(withQuietZone(code))
}

private fun baseBuilder() = QRCode.ofRoundedSquares()
    .withSize(MODULE_PIXELS)
    .withColor(Colors.BLACK)
    .withBackgroundColor(Colors.WHITE)
    .withErrorCorrectionLevel(ErrorCorrectionLevel.HIGH)

/** [image] scaled into a white rounded square of [size] pixels with a small frame. */
private fun framed(image: BufferedImage, size: Int): BufferedImage {
    val frame = (size * LOGO_FRAME_SHARE).toInt()
    val result = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = result.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.WHITE
    g.fillRect(0, 0, size, size)
    val inner = size - 2 * frame
    g.clip = RoundRectangle2D.Float(frame.toFloat(), frame.toFloat(), inner.toFloat(), inner.toFloat(), inner * 0.2f, inner * 0.2f)
    g.drawImage(image, frame, frame, inner, inner, null)
    g.dispose()
    return result
}

/** [code] on a white canvas with [QUIET_ZONE_MODULES] modules of space on every side. */
private fun withQuietZone(code: BufferedImage): BufferedImage {
    val border = QUIET_ZONE_MODULES * MODULE_PIXELS
    val result = BufferedImage(code.width + 2 * border, code.height + 2 * border, BufferedImage.TYPE_INT_RGB)
    val g = result.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, result.width, result.height)
    g.drawImage(code, border, border, null)
    g.dispose()
    return result
}

private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
