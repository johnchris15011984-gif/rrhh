package pe.gob.risangaraes.asistencia

import android.content.Context
import android.content.Intent
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Para RRHH y la Dirección: genera los QR de su(s) IPRESS (el servidor los firma con su clave secreta),
 * los imprime o comparte en PDF y permite probar la lectura de una hoja ya pegada.
 */
@Composable
fun PantallaQr(almacen: Almacen, volver: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hoy = LocalDate.now(ZONA)
    val esteMes = Qr.periodoDe(hoy)
    val proximoMes = Qr.periodoDe(hoy.plusMonths(1))

    var usuario by rememberSaveable { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }        // la clave no se guarda en ningún lado
    var periodo by rememberSaveable { mutableStateOf(esteMes) }
    var items by remember { mutableStateOf<List<ItemQr>?>(null) }
    var periodoItems by remember { mutableStateOf(esteMes) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }

    val lector = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents ?: return@rememberLauncherForActivityResult
        val c = Qr.leer(txt)
        mensaje = when {
            c == null -> Aviso("Este código no es un QR de asistencia de la RIS Angaraes.", Nivel.ERROR)
            else -> {
                val item = items?.firstOrNull { it.codigo == c.codigo }
                val vigente = if (Qr.periodoValido(c.periodo, hoy)) "vigente" else "VENCIDO"
                val firma = when {
                    item == null -> "no se puede comparar la firma con la lista generada"
                    c.periodo == periodoItems && item.texto == txt.trim() -> "firma correcta"
                    c.periodo == periodoItems -> "FIRMA NO COINCIDE: puede ser una copia alterada"
                    else -> "es de otro periodo que el generado"
                }
                Aviso("QR de ${item?.nombre ?: "código ${c.codigo}"} · periodo ${Qr.periodoTexto(c.periodo)} ($vigente) · $firma.",
                    if (vigente == "vigente" && firma == "firma correcta") Nivel.OK else Nivel.INFO)
            }
        }
    }

    fun generar() {
        scope.launch {
            ocupado = "Solicitando códigos al servidor…"; mensaje = null
            try {
                val r = Api.post(almacen.servidor, "/qr",
                    JSONObject().put("usuario", usuario.trim()).put("clave", clave).put("periodo", periodo))
                if (r.ok) {
                    val lista = r.json?.optJSONArray("items")?.objetos().orEmpty().map {
                        ItemQr(it.texto("codigo") ?: "", it.texto("nombre") ?: "", it.texto("distrito") ?: "",
                            it.texto("categoria") ?: "", it.texto("texto") ?: "")
                    }.filter { it.texto.isNotBlank() }
                    if (lista.isEmpty()) mensaje = Aviso("No hay establecimientos con código registrado para este usuario.", Nivel.INFO)
                    else { items = lista; periodoItems = r.json?.texto("periodo") ?: periodo; clave = "" }
                } else mensaje = Aviso(r.msg.ifBlank { "Error del servidor (${r.http})" }, Nivel.ERROR)
            } catch (e: IOException) {
                mensaje = Aviso("No hay conexión con el servidor. Los códigos QR solo se generan con conexión, porque el servidor es quien los firma.", Nivel.ERROR)
            } finally {
                ocupado = null
            }
        }
    }

    fun pdf(accion: (File) -> Unit) {
        val lista = items ?: return
        scope.launch {
            ocupado = "Preparando el PDF…"
            try {
                val f = withContext(Dispatchers.Default) { Qr.crearPdf(ctx, lista, periodoItems) }
                accion(f)
            } catch (e: Exception) {
                mensaje = Aviso("No se pudo crear el PDF: ${e.message}", Nivel.ERROR)
            } finally {
                ocupado = null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Encabezado("Códigos QR", volver)
        val lista = items
        if (lista == null) {
            Text("Solo para responsables de RRHH y la Dirección. Use el mismo usuario y clave del sistema web. " +
                "La Dirección obtiene todos los establecimientos; RRHH, solo el suyo.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(usuario, { usuario = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clave, { clave = it }, label = { Text("Clave") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Text("Periodo de vigencia", fontWeight = FontWeight.Bold)
            listOf(esteMes to "Este mes", proximoMes to "Próximo mes").forEach { (p, etiqueta) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = periodo == p, onClick = { periodo = p })
                    Text("$etiqueta (${Qr.periodoTexto(p)})")
                }
            }
            mensaje?.let { CajaAviso(it) }
            if (ocupado != null) Ocupado(ocupado!!)
            else Button(onClick = { generar() }, enabled = usuario.isNotBlank() && clave.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Generar códigos QR")
            }
        } else {
            Text("${lista.size} establecimiento(s) · vigente ${Qr.periodoTexto(periodoItems)}", fontWeight = FontWeight.Bold)
            Text("Imprima una hoja por establecimiento y péguela en un lugar visible dentro del local. " +
                "Cada QR vale para su mes (y se acepta un mes antes y uno después).", style = MaterialTheme.typography.bodySmall)
            mensaje?.let { CajaAviso(it) }
            if (ocupado != null) Ocupado(ocupado!!)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pdf { imprimir(ctx, it) } }, modifier = Modifier.weight(1f)) { Text("Imprimir") }
                    Button(onClick = { pdf { compartir(ctx, it) } }, modifier = Modifier.weight(1f)) { Text("Compartir PDF") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        lector.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Escanee una hoja QR para comprobarla").setOrientationLocked(false))
                    }, modifier = Modifier.weight(1f)) { Text("Probar lectura") }
                    OutlinedButton(onClick = { items = null; mensaje = null }, modifier = Modifier.weight(1f)) { Text("Otro periodo") }
                }
            }
            lista.forEach { it -> TarjetaQr(it) }
        }
    }
}

@Composable
private fun TarjetaQr(it: ItemQr) {
    val img = remember(it.texto) { Qr.bitmap(it.texto, 600).asImageBitmap() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(it.nombre, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(listOf(it.distrito, it.categoria).filter { s -> s.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Image(img, contentDescription = "Código QR de ${it.nombre}", filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
            Text("Código ${it.codigo}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun uriDe(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", f)

private fun compartir(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uriDe(ctx, f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Compartir códigos QR"))
}

private fun imprimir(ctx: Context, f: File) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print("Códigos QR RIS Angaraes", AdaptadorImpresion(f), null)
}
