package pe.gob.risangaraes.asistencia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.programar(this)
        setContent { App() }
    }
}

enum class Pantalla { SERVIDOR, VINCULAR, MARCAR, HISTORIAL, QR, AJUSTES }

val AZUL = Color(0xFF1F4E8C)

@Composable
fun App() {
    val ctx = LocalContext.current
    val almacen = remember { Almacen(ctx) }
    var perfil by remember { mutableStateOf(almacen.perfil) }
    fun inicio(): Pantalla = when {
        almacen.servidor.isBlank() -> Pantalla.SERVIDOR
        almacen.token == null || perfil == null -> Pantalla.VINCULAR
        else -> Pantalla.MARCAR
    }
    var pantalla by rememberSaveable { mutableStateOf(inicio()) }
    val volver = { pantalla = inicio() }
    BackHandler(enabled = pantalla != inicio()) { volver() }

    MaterialTheme(colorScheme = lightColorScheme(primary = AZUL, secondary = Color(0xFF2E7D32))) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (pantalla) {
                    Pantalla.SERVIDOR -> PantallaServidor(almacen, onListo = volver)
                    Pantalla.VINCULAR -> PantallaVincular(
                        almacen,
                        onVinculado = { perfil = almacen.perfil; SyncWorker.programar(ctx); pantalla = Pantalla.MARCAR },
                        irQr = { pantalla = Pantalla.QR },
                        irServidor = { pantalla = Pantalla.SERVIDOR },
                    )
                    Pantalla.MARCAR -> {
                        val p = perfil
                        if (p == null) LaunchedEffect(Unit) { pantalla = Pantalla.VINCULAR }
                        else PantallaMarcar(almacen, p, onPerfil = { perfil = almacen.perfil }, ir = { pantalla = it })
                    }
                    Pantalla.HISTORIAL -> PantallaHistorial(almacen, volver)
                    Pantalla.QR -> PantallaQr(almacen, volver)
                    Pantalla.AJUSTES -> PantallaAjustes(
                        almacen, perfil,
                        onPerfil = { perfil = almacen.perfil },
                        onDesvincular = { almacen.desvincular(); perfil = null; pantalla = Pantalla.VINCULAR },
                        irServidor = { pantalla = Pantalla.SERVIDOR },
                        volver = volver,
                    )
                }
            }
        }
    }
}

// ---------- Componentes comunes ----------
enum class Nivel { OK, INFO, ERROR }
data class Aviso(val texto: String, val nivel: Nivel)

@Composable
fun CajaAviso(a: Aviso, modifier: Modifier = Modifier) {
    val (fondo, borde) = when (a.nivel) {
        Nivel.OK -> Color(0xFFD1E7DD) to Color(0xFF0F5132)
        Nivel.INFO -> Color(0xFFFFF3CD) to Color(0xFF664D03)
        Nivel.ERROR -> Color(0xFFF8D7DA) to Color(0xFF842029)
    }
    Text(
        a.texto, color = borde,
        modifier = modifier.fillMaxWidth().background(fondo, RoundedCornerShape(8.dp)).padding(12.dp),
    )
}

@Composable
fun Encabezado(titulo: String, volver: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (volver != null) TextButton(onClick = volver) { Text("← Volver") }
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AZUL)
    }
}

@Composable
fun Ocupado(texto: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Spacer(Modifier.width(16.dp))
        Text(texto)
    }
}
