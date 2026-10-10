package pe.gob.risangaraes.asistencia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

private const val LADO_MAX = 1280                 // px; deja la foto muy por debajo del límite de 3 MB de nucleo.R
private const val BYTES_MAX = 2_500_000

/**
 * Reduce la foto, la endereza según el sensor y le pone marca de agua (nombre, fecha, GPS, QR),
 * como exige registrar_marcacion() de nucleo.R. Devuelve los bytes JPEG.
 */
fun prepararFoto(archivo: File, lineas: List<String>): ByteArray {
    val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(archivo.absolutePath, limites)
    if (limites.outWidth <= 0) throw IOException("No se pudo leer la foto")
    var muestra = 1
    while (max(limites.outWidth, limites.outHeight) / (muestra * 2) >= LADO_MAX) muestra *= 2
    var bmp = BitmapFactory.decodeFile(archivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = muestra })
        ?: throw IOException("No se pudo leer la foto")

    // Orientación
    val giro = when (runCatching { ExifInterface(archivo.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val escala = minOf(1f, LADO_MAX.toFloat() / max(bmp.width, bmp.height))
    if (giro != 0f || escala < 1f) {
        val m = Matrix().apply { postScale(escala, escala); postRotate(giro) }
        val nuevo = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (nuevo !== bmp) bmp.recycle()
        bmp = nuevo
    }
    if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }

    // Marca de agua
    val c = Canvas(bmp)
    val tam = max(18f, bmp.width / 30f)
    val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = tam; typeface = Typeface.DEFAULT_BOLD }
    val alto = (lineas.size * tam * 1.3f + tam).roundToInt()
    c.drawRect(0f, (bmp.height - alto).toFloat(), bmp.width.toFloat(), bmp.height.toFloat(),
        Paint().apply { color = Color.argb(150, 0, 0, 0) })
    lineas.forEachIndexed { i, l ->
        c.drawText(l, tam * 0.6f, bmp.height - alto + tam * 1.2f + i * tam * 1.3f, texto)
    }

    val sal = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, sal)
    if (sal.size() > BYTES_MAX) { sal.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, 55, sal) }
    bmp.recycle()
    return sal.toByteArray()
}
