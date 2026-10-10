package pe.gob.risangaraes.asistencia

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PantallaMarcar(almacen: Almacen, perfil: Perfil, onPerfil: () -> Unit, ir: (Pantalla) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var enLinea by remember { mutableStateOf<Boolean?>(null) }
    var pendientes by remember { mutableIntStateOf(almacen.cantidadPendientes()) }
    var ahora by remember { mutableStateOf(ZonedDateTime.now(ZONA)) }
    var mensaje by remember { mutableStateOf<Aviso?>(null) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var faltaGps by remember { mutableStateOf(false) }
    var gps by remember { mutableStateOf<Deferred<Punto?>?>(null) }
    // Se guardan aunque Android cierre la app mientras la cámara está abierta
    var tipo by rememberSaveable { mutableStateOf<String?>(null) }
    var qr by rememberSaveable { mutableStateOf<String?>(null) }
    var rutaFoto by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { ahora = ZonedDateTime.now(ZONA); delay(30_000) } }
    LaunchedEffect(Unit) {
        val srv = Api.ping(almacen.servidor)
        enLinea = srv != null
        if (srv != null) {
            almacen.guardarReloj(srv)
            if (almacen.cantidadPendientes() > 0) Sincronizador.sincronizar(ctx) else Sincronizador.actualizarPerfil(ctx)
            onPerfil()
            pendientes = almacen.cantidadPendientes()
        }
    }

    fun reiniciar() {
        tipo = null; qr = null; faltaGps = false
        rutaFoto?.let { File(it).delete() }; rutaFoto = null
        gps?.cancel(); gps = null
    }

    fun iniciarGps() { if (gps == null) gps = scope.async { runCatching { obtenerUbicacion(ctx) }.getOrNull() } }

    suspend fun registrar() {
        val t = tipo; val ruta = rutaFoto
        if (t == null || ruta == null || !File(ruta).exists()) {
            mensaje = Aviso("Se perdió la captura. Vuelva a marcar.", Nivel.ERROR); reiniciar(); return
        }
        try {
            ocupado = "Verificando conexión…"
            val srv = Api.ping(almacen.servidor)
            val online = srv != null
            enLinea = online
            if (srv != null) almacen.guardarReloj(srv)

            ocupado = "Obteniendo ubicación GPS…"
            iniciarGps()
            val punto = runCatching { gps?.await() }.getOrNull()
            if (punto?.simulada == true) {
                mensaje = Aviso("Se detectó una ubicación simulada (aplicación de GPS falso). Desactívela para poder marcar.", Nivel.ERROR)
                reiniciar(); return
            }
            if (online && punto == null) {
                // api.R exige GPS cuando hay conexión: se conserva la foto y el QR para reintentar
                gps = null; faltaGps = true
                mensaje = Aviso("Hay conexión, pero el GPS no da su ubicación. Active la ubicación del teléfono, " +
                    "acérquese a una ventana o patio y pulse «Reintentar ubicación».", Nivel.ERROR)
                return
            }
            faltaGps = false

            ocupado = "Preparando la foto…"
            val ts = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val cqr = qr?.let { Qr.leer(it) }
            val lineas = listOf(
                perfil.nombres,
                "DNI ${perfil.dni} · $t",
                perfil.ipressNombre,
                FORMATO_FECHA_HORA.format(ts.atZone(ZONA)),
                punto?.let { String.format(Locale.US, "GPS %.6f, %.6f ±%d m", it.lat, it.lon, it.acc.roundToInt()) } ?: "Sin GPS",
                cqr?.let { "QR ${it.codigo} · ${Qr.periodoTexto(it.periodo)}" } ?: "Sin QR",
            )
            val bytes = withContext(Dispatchers.Default) { prepararFoto(File(ruta), lineas) }

            val uuid = UUID.randomUUID().toString()
            val m = JSONObject()
                .put("uuid", uuid).put("dni", perfil.dni).put("tipo", t)
                .put("ts", ts.toString()).put("en_linea", online)
                .put("foto", Base64.encodeToString(bytes, Base64.NO_WRAP))
            qr?.let { m.put("qr", it) }
            punto?.let { m.put("lat", it.lat).put("lon", it.lon).put("acc", it.acc.toDouble()) }
            withContext(Dispatchers.IO) { almacen.encolar(m) }
            File(ruta).delete(); rutaFoto = null

            // Aviso local de geocerca (el servidor decide; esto solo informa)
            val avisoZona = if (punto != null && perfil.lat != null && perfil.lon != null) {
                val d = distanciaM(punto.lat, punto.lon, perfil.lat, perfil.lon)
                val radio = perfil.radioM ?: 200.0
                if (d > radio) "\nAtención: está a ${d.roundToInt()} m del establecimiento (límite ${radio.roundToInt()} m). La marcación quedará en revisión." else ""
            } else ""

            mensaje = if (online) {
                ocupado = "Enviando al servidor…"
                val r = Sincronizador.sincronizar(ctx)
                val h = almacen.historialDe(uuid)
                when {
                    h == null -> Aviso("Marcación guardada en el teléfono. Aún no se pudo enviar (${r.error ?: "sin respuesta"}); se reintentará sola.$avisoZona", Nivel.INFO)
                    h.optString("estado") == "Rechazada" -> Aviso("Rechazada por el servidor: ${h.optString("resultado")}", Nivel.ERROR)
                    else -> Aviso("✔ ${h.optString("resultado")}$avisoZona", if (avisoZona.isEmpty()) Nivel.OK else Nivel.INFO)
                }
            } else {
                SyncWorker.ahora(ctx)
                Aviso("✔ $t guardado en el teléfono (sin conexión). Se enviará solo cuando vuelva la señal.$avisoZona", Nivel.INFO)
            }
            tipo = null; qr = null; gps = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mensaje = Aviso("No se pudo registrar: ${e.message ?: e.javaClass.simpleName}", Nivel.ERROR)
            reiniciar()
        } finally {
            ocupado = null
            pendientes = almacen.cantidadPendientes()
            onPerfil()
        }
    }

    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { registrar() }
        else { mensaje = Aviso("Se canceló la foto. Vuelva a intentarlo.", Nivel.ERROR); reiniciar() }
    }

    fun abrirCamara() {
        val dir = File(ctx.cacheDir, "fotos").apply { mkdirs() }
        val f = File(dir, "captura_${System.currentTimeMillis()}.jpg")
        rutaFoto = f.absolutePath
        camara.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", f))
    }

    val escaner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents
        val problema = when (txt) {
            null -> "Se canceló la lectura del código QR."
            else -> Qr.problema(txt, perfil.ipressCodigo, LocalDate.now(ZONA))
        }
        if (problema != null) { mensaje = Aviso(problema, Nivel.ERROR); reiniciar() }
        else { qr = txt; abrirCamara() }
    }

    fun iniciarEscaneo() {
        iniciarGps()   // el GPS se va ubicando mientras la persona escanea y se toma la foto
        escaner.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Enfoque el código QR pegado en el establecimiento")
            .setBeepEnabled(true)
            .setOrientationLocked(false))
    }

    fun tiene(permiso: String) = ContextCompat.checkSelfPermission(ctx, permiso) == PackageManager.PERMISSION_GRANTED

    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cam = res[Manifest.permission.CAMERA] ?: tiene(Manifest.permission.CAMERA)
        val ubi = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            tiene(Manifest.permission.ACCESS_FINE_LOCATION)
        if (cam && ubi) iniciarEscaneo()
        else { mensaje = Aviso("Para marcar debe permitir el uso de la cámara y de la ubicación.", Nivel.ERROR); reiniciar() }
    }

    fun empezar(t: String) {
        mensaje = null; reiniciar(); tipo = t
        permisos.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // ---------------- Interfaz ----------------
    val hoy = ahora.toLocalDate()
    val turno = perfil.turnoDe(hoy)
    val sugeridos = perfil.tiposSugeridos(ahora)
    val desfase = almacen.desfaseRelojMin()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("RED INTEGRADA DE SALUD ANGARAES", style = MaterialTheme.typography.labelLarge, color = AZUL, fontWeight = FontWeight.Bold)
        Text(perfil.nombres, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(listOfNotNull(perfil.ipressNombre, perfil.distrito).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(FORMATO_FECHA_LARGA.format(ahora).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                Text("Turno de hoy: " + (turno?.let { "$it – ${NOMBRE_TURNO[it] ?: it}" } ?: "sin turno programado"))
                turno?.let { perfil.horarios[it] }?.takeIf { it.isNotEmpty() }?.let { h ->
                    Text(h.joinToString("   ") { "${it.tipo} ${it.desde}–${it.hasta}" }, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when (enLinea) { null -> "Verificando conexión…"; true -> "● Conectado al servidor"; false -> "● Sin conexión: las marcaciones se guardan en el teléfono" },
                    color = when (enLinea) { true -> Color(0xFF2E7D32); false -> Color(0xFFB26A00); null -> Color.Gray },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (almacen.tokenRechazado) {
            CajaAviso(Aviso("El servidor ya no reconoce este teléfono (RRHH lo desvinculó o su registro está inactivo). " +
                "Las marcaciones quedan guardadas; vuelva a vincularlo en Ajustes con su DNI y PIN.", Nivel.ERROR))
        }
        if (desfase != null && abs(desfase) > 5) {
            CajaAviso(Aviso("La hora del teléfono difiere ${abs(desfase)} min de la del servidor. Active «Fecha y hora automáticas»; " +
                "las marcaciones con hora alterada quedan observadas.", Nivel.INFO))
        }
        if (pendientes > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$pendientes marcación(es) por enviar", color = Color(0xFFB26A00), modifier = Modifier.padding(top = 12.dp))
                TextButton(enabled = ocupado == null, onClick = {
                    scope.launch {
                        ocupado = "Enviando marcaciones pendientes…"
                        val r = Sincronizador.sincronizar(ctx)
                        ocupado = null
                        pendientes = r.quedan; onPerfil()
                        mensaje = if (r.error == null) Aviso("Enviadas: ${r.enviadas}. Rechazadas: ${r.rechazadas}. Revise el detalle en Historial.",
                            if (r.rechazadas > 0) Nivel.INFO else Nivel.OK)
                        else Aviso("${r.error}. Quedan ${r.quedan} por enviar.", Nivel.INFO)
                    }
                }) { Text("Enviar ahora") }
            }
        }

        mensaje?.let { CajaAviso(it) }

        when {
            ocupado != null -> Ocupado(ocupado!!)
            faltaGps -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mensaje = null; scope.launch { registrar() } }) { Text("Reintentar ubicación") }
                OutlinedButton(onClick = { mensaje = null; reiniciar() }) { Text("Cancelar") }
            }
            else -> {
                Text("Registrar marcación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TIPOS.forEach { t ->
                    val mod = Modifier.fillMaxWidth().height(56.dp)
                    if (t in sugeridos) Button(onClick = { empezar(t) }, modifier = mod) { Text(t) }
                    else OutlinedButton(onClick = { empezar(t) }, modifier = mod) { Text(t) }
                }
                Text("1) Escanee el código QR pegado en su establecimiento.  2) Tómese una foto dentro del local.  " +
                    "La ubicación GPS se registra sola. Funciona aunque no haya señal.",
                    style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { ir(Pantalla.HISTORIAL) }) { Text("Historial") }
            TextButton(onClick = { ir(Pantalla.QR) }) { Text("Códigos QR") }
            TextButton(onClick = { ir(Pantalla.AJUSTES) }) { Text("Ajustes") }
        }
    }
}
