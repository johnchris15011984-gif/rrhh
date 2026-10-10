package pe.gob.risangaraes.asistencia

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

private fun fechaLocal(isoUtc: String): String =
    runCatching { FORMATO_FECHA_HORA.format(Instant.parse(isoUtc).atZone(ZONA)) }.getOrDefault(isoUtc)

// ---------------- Dirección del servidor ----------------
@Composable
fun PantallaServidor(almacen: Almacen, onListo: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(almacen.servidor) }
    var ocupado by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Encabezado("Servidor de la RIS")
        Text("Escriba la dirección de la API de asistencia (api.R) que le indicó la Oficina de Informática. Por ejemplo:\n" +
            "https://asistencia.risangaraes.gob.pe/api\nhttp://192.168.1.10:8000", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Dirección del servidor") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        mensaje?.let { CajaAviso(it) }
        if (ocupado) Ocupado("Probando conexión…")
        else Button(enabled = url.startsWith("http://") || url.startsWith("https://"), modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                ocupado = true
                val limpio = url.trimEnd('/')
                val srv = Api.ping(limpio)
                ocupado = false
                if (srv != null) { almacen.servidor = limpio; almacen.guardarReloj(srv); onListo() }
                else mensaje = Aviso("El servidor no responde en esa dirección. Revise que esté bien escrita y que tenga internet.", Nivel.ERROR)
            }
        }) { Text("Probar y guardar") }
    }
}

// ---------------- Vincular el teléfono (DNI + PIN, una sola vez) ----------------
@Composable
fun PantallaVincular(almacen: Almacen, onVinculado: () -> Unit, irQr: () -> Unit, irServidor: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dni by rememberSaveable { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Encabezado("Vincular este teléfono")
        Text("Se hace una sola vez y necesita conexión. Use su DNI y el PIN que le entregó RRHH. " +
            "Después podrá marcar su asistencia aunque no haya señal.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(dni, { dni = it.filter(Char::isLetterOrDigit).take(12) }, label = { Text("DNI") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN (4 a 6 dígitos)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        mensaje?.let { CajaAviso(it) }
        if (ocupado) Ocupado("Vinculando…")
        else Button(enabled = dni.length >= 8 && pin.length >= 4, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                ocupado = true; mensaje = null
                try {
                    val equipo = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
                    val r = Api.post(almacen.servidor, "/vincular", JSONObject().put("dni", dni).put("pin", pin).put("equipo", equipo))
                    val perfil = r.json?.optJSONObject("perfil")
                    val token = r.json?.optString("token").orEmpty()
                    if (r.ok && perfil != null && token.matches(Regex("[0-9a-f]{64}"))) {
                        almacen.token = token
                        almacen.perfilJson = perfil.toString()
                        almacen.tokenRechazado = false
                        almacen.guardarReloj(perfil.texto("servidor_utc"))
                        pin = ""
                        onVinculado()
                    } else mensaje = Aviso(r.msg.ifBlank { "No se pudo vincular (error ${r.http})" }, Nivel.ERROR)
                } catch (e: IOException) {
                    mensaje = Aviso("No hay conexión con el servidor. Para vincular el teléfono necesita internet (solo esta vez).", Nivel.ERROR)
                } finally {
                    ocupado = false
                }
            }
        }) { Text("Vincular") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Servidor: ${almacen.servidor}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        TextButton(onClick = irServidor) { Text("Cambiar servidor") }
        TextButton(onClick = irQr) { Text("Generar códigos QR (RRHH / Dirección)") }
    }
}

// ---------------- Historial y pendientes ----------------
@Composable
fun PantallaHistorial(almacen: Almacen, volver: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var ocupado by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }
    val pend = remember(version) { almacen.resumenPendientes() }
    val hist = remember(version) { almacen.historial() }
    val dniActual = almacen.perfil?.dni

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Encabezado("Historial", volver)
        Text("Por enviar (${pend.size})", fontWeight = FontWeight.Bold)
        if (pend.isEmpty()) Text("No hay marcaciones pendientes.", color = Color.Gray)
        pend.forEach { m ->
            val otra = dniActual != null && m.optString("dni") != dniActual
            Text("• ${m.optString("tipo")} · ${fechaLocal(m.optString("ts"))}" +
                (if (m.has("lat")) "" else " · sin GPS") + (if (otra) " · de otro DNI (${m.optString("dni")})" else ""))
        }
        mensaje?.let { CajaAviso(it) }
        if (pend.isNotEmpty()) {
            if (ocupado) Ocupado("Enviando…")
            else Button(onClick = {
                scope.launch {
                    ocupado = true
                    val r = Sincronizador.sincronizar(ctx)
                    ocupado = false; version++
                    mensaje = if (r.error == null) Aviso("Enviadas: ${r.enviadas}. Rechazadas: ${r.rechazadas}.", Nivel.OK)
                    else Aviso("${r.error}. Quedan ${r.quedan}.", Nivel.INFO)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Enviar ahora") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Enviadas recientemente", fontWeight = FontWeight.Bold)
        if (hist.isEmpty()) Text("Todavía no hay envíos.", color = Color.Gray)
        hist.forEach { h ->
            val rech = h.optString("estado") == "Rechazada"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${h.optString("tipo")} · ${fechaLocal(h.optString("ts"))}", fontWeight = FontWeight.Bold)
                    Text(h.optString("estado"), color = if (rech) Color(0xFF842029) else Color(0xFF0F5132))
                    Text(h.optString("resultado"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------- Ajustes ----------------
@Composable
fun PantallaAjustes(almacen: Almacen, perfil: Perfil?, onPerfil: () -> Unit, onDesvincular: () -> Unit,
                    irServidor: () -> Unit, volver: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmar by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }
    var ocupado by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Encabezado("Ajustes", volver)
        perfil?.let {
            Text("${it.nombres}\nDNI ${it.dni} · Condición ${it.condicion}\n${it.ipressNombre}" +
                (it.categoria?.let { c -> " (categoría $c)" } ?: ""))
            Text("Programación descargada: ${it.programacion.size} día(s)", style = MaterialTheme.typography.bodySmall)
        }
        Text("Servidor: ${almacen.servidor}", style = MaterialTheme.typography.bodySmall)
        mensaje?.let { CajaAviso(it) }
        if (ocupado) Ocupado("Actualizando…")
        else Button(modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                ocupado = true
                val ok = Sincronizador.actualizarPerfil(ctx)
                ocupado = false; onPerfil()
                mensaje = when {
                    !ok -> Aviso("Sin conexión con el servidor.", Nivel.ERROR)
                    almacen.tokenRechazado -> Aviso("El servidor no reconoce este teléfono: desvincúlelo y vuelva a vincularlo con su DNI y PIN.", Nivel.ERROR)
                    else -> Aviso("Programación y horarios actualizados.", Nivel.OK)
                }
            }
        }) { Text("Actualizar mi programación") }
        OutlinedButton(onClick = irServidor, modifier = Modifier.fillMaxWidth()) { Text("Cambiar servidor") }
        OutlinedButton(onClick = { confirmar = true }, modifier = Modifier.fillMaxWidth()) { Text("Desvincular este teléfono") }
        Text("Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }

    if (confirmar) {
        val n = almacen.cantidadPendientes()
        AlertDialog(
            onDismissRequest = { confirmar = false },
            title = { Text("¿Desvincular este teléfono?") },
            text = {
                Text("Tendrá que volver a ingresar su DNI y PIN para marcar." +
                    if (n > 0) "\n\nHay $n marcación(es) sin enviar: se enviarán cuando vuelva a vincular este teléfono con el MISMO DNI." else "")
            },
            confirmButton = { TextButton(onClick = { confirmar = false; onDesvincular() }) { Text("Desvincular") } },
            dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Cancelar") } },
        )
    }
}
