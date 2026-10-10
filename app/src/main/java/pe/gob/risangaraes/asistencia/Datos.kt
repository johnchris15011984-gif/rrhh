package pe.gob.risangaraes.asistencia

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Mismas reglas que nucleo.R
val ZONA: ZoneId = ZoneId.of("America/Lima")
val TIPOS = listOf("Ingreso", "Salida", "Retorno", "Salida final")
val GUARDIAS_NOCTURNAS = setOf("GNO", "GNF")
val NOMBRE_TURNO = mapOf(
    "M" to "Mañana (6 h)",
    "MT" to "Mañana y tarde (12 h)",
    "GDO" to "Guardia diurna ordinaria (12 h)",
    "GDF" to "Guardia diurna domingo/feriado (12 h)",
    "GNO" to "Guardia nocturna ordinaria (12 h)",
    "GNF" to "Guardia nocturna domingo/feriado (12 h)",
    "V" to "Vacaciones",
)
private val LOCALE_PE = Locale("es", "PE")
val FORMATO_FECHA_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", LOCALE_PE)
val FORMATO_FECHA_LARGA: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy · HH:mm", LOCALE_PE)

data class Horario(val tipo: String, val desde: String, val hasta: String)

/** Perfil que devuelve api.R (/vincular y /perfil) y que se guarda en el teléfono para trabajar sin señal. */
data class Perfil(
    val dni: String,
    val nombres: String,
    val condicion: String,
    val ipressNombre: String,
    val ipressCodigo: String?,
    val distrito: String?,
    val categoria: String?,
    val lat: Double?,
    val lon: Double?,
    val radioM: Double?,
    val programacion: Map<String, String>,          // "2026-10-07" -> "MT"
    val horarios: Map<String, List<Horario>>,       // "MT" -> ventanas
) {
    fun turnoDe(fecha: LocalDate): String? = programacion[fecha.toString()]

    /** Marcas que corresponden ahora según la programación (incluye la salida de una guardia nocturna de ayer). */
    fun tiposSugeridos(ahora: ZonedDateTime): Set<String> {
        val s = mutableSetOf<String>()
        val hoy = ahora.toLocalDate()
        turnoDe(hoy)?.let { t -> horarios[t]?.forEach { s += it.tipo } }
        if (ahora.hour < 12 && turnoDe(hoy.minusDays(1)) in GUARDIAS_NOCTURNAS) s += "Salida final"
        return s
    }

    companion object {
        fun desdeJson(o: JSONObject): Perfil {
            val ip = o.optJSONObject("ipress") ?: JSONObject()
            val prog = mutableMapOf<String, String>()
            o.optJSONArray("programacion")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    val f = r.texto("fecha"); val t = r.texto("turno")
                    if (f != null && t != null) prog[f] = t
                }
            }
            val hor = mutableMapOf<String, List<Horario>>()
            o.optJSONObject("horarios")?.let { h ->     // si no hay horarios, R envía [] y se ignora
                for (turno in h.keys()) {
                    val a = h.optJSONArray(turno) ?: continue
                    hor[turno] = (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { Horario(it.optString("tipo"), it.optString("desde"), it.optString("hasta")) }
                    }
                }
            }
            return Perfil(
                dni = o.texto("dni") ?: "",
                nombres = o.texto("nombres") ?: "",
                condicion = o.texto("condicion") ?: "",
                ipressNombre = ip.texto("nombre") ?: "",
                ipressCodigo = ip.texto("codigo"),
                distrito = ip.texto("distrito"),
                categoria = ip.texto("categoria"),
                lat = ip.numero("lat"),
                lon = ip.numero("lon"),
                radioM = ip.numero("radio_m"),
                programacion = prog,
                horarios = hor,
            )
        }
    }
}

/** Texto de un campo JSON aunque R lo haya enviado como número; null si falta o es null. */
fun JSONObject.texto(k: String): String? = if (!has(k) || isNull(k)) null else get(k).toString().takeIf { it.isNotBlank() }
fun JSONObject.numero(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
fun JSONArray.objetos(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Distancia en metros (Haversine), igual que dist_m() de nucleo.R */
fun distanciaM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p = Math.PI / 180
    val a = sin((lat2 - lat1) * p / 2).let { it * it } +
        cos(lat1 * p) * cos(lat2 * p) * sin((lon2 - lon1) * p / 2).let { it * it }
    return 2 * 6371000 * asin(sqrt(a))
}
