package pe.gob.risangaraes.asistencia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Un establecimiento con el texto de su QR ya firmado por el servidor (qr_texto() de nucleo.R). */
data class ItemQr(val codigo: String, val nombre: String, val distrito: String, val categoria: String, val texto: String)

object Qr {
    /** Contenido del QR: RISANG|codigo|AAAAMM|firma */
    data class Contenido(val codigo: String, val periodo: String, val firma: String)

    private val AAAAMM = DateTimeFormatter.ofPattern("yyyyMM")

    fun leer(texto: String): Contenido? {
        val p = texto.trim().split("|")
        if (p.size != 4 || p[0] != "RISANG" || p[1].isBlank() || !p[2].matches(Regex("\\d{6}"))) return null
        return Contenido(p[1], p[2], p[3])
    }

    /** Mismo criterio que qr_verificar(): vale el mes de la marca, el anterior y el siguiente. */
    fun periodoValido(periodo: String, hoy: LocalDate): Boolean {
        val ym = YearMonth.from(hoy)
        return periodo in listOf(ym.minusMonths(1), ym, ym.plusMonths(1)).map { it.format(AAAAMM) }
    }

    fun periodoTexto(periodo: String): String =
        if (periodo.length == 6) periodo.substring(4) + "/" + periodo.substring(0, 4) else periodo

    fun periodoDe(fecha: LocalDate): String = YearMonth.from(fecha).format(AAAAMM)

    /**
     * Revisión en el teléfono antes de marcar (la firma la verifica el servidor).
     * Devuelve null si está bien o un mensaje explicando el problema.
     */
    fun problema(texto: String, codigoIpress: String?, hoy: LocalDate): String? {
        val c = leer(texto) ?: return "Este código QR no es de la RIS Angaraes. Escanee la hoja pegada en su establecimiento."
        if (codigoIpress != null && c.codigo != codigoIpress) return "Este QR pertenece a otro establecimiento (código ${c.codigo}). Escanee el de su IPRESS."
        if (!periodoValido(c.periodo, hoy)) return "Este QR ya no está vigente (${periodoTexto(c.periodo)}). Pida a RRHH la hoja del mes actual."
        return null
    }

    fun bitmap(texto: String, tam: Int = 800): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tam, tam, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** PDF A4 con una hoja por establecimiento, listo para imprimir y pegar. */
    fun crearPdf(ctx: Context, items: List<ItemQr>, periodo: String): File {
        val doc = PdfDocument()
        val ancho = 595; val alto = 842; val cx = ancho / 2f
        val azul = Color.rgb(0x1F, 0x4E, 0x8C)
        fun pincel(tam: Float, negrita: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = tam; this.color = color
            typeface = if (negrita) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        fun Canvas.textoAjustado(t: String, y: Float, p: Paint) {
            while (p.measureText(t) > ancho - 60 && p.textSize > 11f) p.textSize -= 1f
            drawText(t, cx, y, p)
        }
        items.forEachIndexed { i, it ->
            val pag = doc.startPage(PdfDocument.PageInfo.Builder(ancho, alto, i + 1).create())
            val c = pag.canvas
            c.drawText("RED INTEGRADA DE SALUD ANGARAES", cx, 60f, pincel(16f, true, azul))
            c.drawText("Control de asistencia del personal", cx, 82f, pincel(12f, color = Color.DKGRAY))
            c.textoAjustado(it.nombre, 125f, pincel(26f, true))
            c.textoAjustado(listOf(it.distrito, it.categoria.takeIf { s -> s.isNotBlank() }?.let { s -> "Categoría $s" })
                .filterNotNull().filter { s -> s.isNotBlank() }.joinToString(" · "), 152f, pincel(14f, color = Color.DKGRAY))
            val qr = bitmap(it.texto, 1000)
            c.drawBitmap(qr, null, RectF(97.5f, 175f, 497.5f, 575f), Paint().apply { isFilterBitmap = false })
            qr.recycle()
            c.drawText("Escanee este código con la app «Asistencia RIS Angaraes»", cx, 615f, pincel(14f))
            c.drawText("al registrar su ingreso, salida, retorno o salida final.", cx, 635f, pincel(14f))
            c.drawText("Vigente: ${periodoTexto(periodo)}", cx, 685f, pincel(22f, true, azul))
            c.drawText("Código IPRESS: ${it.codigo}", cx, 712f, pincel(11f, color = Color.GRAY))
            c.drawText("Pegue esta hoja en un lugar visible DENTRO del establecimiento. Reemplácela cada mes.", cx, 790f, pincel(10f, color = Color.GRAY))
            doc.finishPage(pag)
        }
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val f = File(dir, "codigos_qr_$periodo.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}

/** Envía un PDF ya creado al servicio de impresión de Android (impresoras Wi-Fi, «Guardar como PDF»). */
class AdaptadorImpresion(private val archivo: File) : PrintDocumentAdapter() {
    override fun onLayout(viejos: PrintAttributes?, nuevos: PrintAttributes?, cancelar: CancellationSignal?,
                          cb: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?) {
        if (cancelar?.isCanceled == true) { cb.onLayoutCancelled(); return }
        cb.onLayoutFinished(PrintDocumentInfo.Builder(archivo.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
    }

    override fun onWrite(paginas: Array<out PageRange>?, destino: ParcelFileDescriptor, cancelar: CancellationSignal?,
                         cb: PrintDocumentAdapter.WriteResultCallback) {
        try {
            FileInputStream(archivo).use { i -> FileOutputStream(destino.fileDescriptor).use { o -> i.copyTo(o) } }
            cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            cb.onWriteFailed(e.message)
        }
    }
}
