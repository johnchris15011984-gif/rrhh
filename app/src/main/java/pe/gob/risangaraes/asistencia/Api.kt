package pe.gob.risangaraes.asistencia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Respuesta de api.R: siempre JSON con ok, codigo y msg. */
class RespuestaApi(val http: Int, val json: JSONObject?) {
    val ok: Boolean get() = json?.optBoolean("ok") == true
    val codigo: String get() = json?.optString("codigo").orEmpty()
    val msg: String get() = json?.optString("msg").orEmpty()
    val definitivo: Boolean get() = json?.optBoolean("definitivo") == true
}

/** Conexión con api.R (plumber). Lanza IOException cuando no hay conexión con el servidor. */
object Api {
    suspend fun post(servidor: String, ruta: String, cuerpo: JSONObject, timeoutMs: Int = 30_000): RespuestaApi =
        withContext(Dispatchers.IO) {
            if (servidor.isBlank()) throw IOException("Falta configurar la dirección del servidor")
            val c = URL(servidor.trimEnd('/') + ruta).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
                leer(c)
            } finally {
                c.disconnect()
            }
        }

    /** Devuelve la hora UTC del servidor si responde, o null si no hay conexión. */
    suspend fun ping(servidor: String): String? = withContext(Dispatchers.IO) {
        if (servidor.isBlank()) return@withContext null
        runCatching {
            val c = URL(servidor.trimEnd('/') + "/ping").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5_000
                c.readTimeout = 5_000
                val r = leer(c)
                if (r.ok) r.json?.optString("servidor_utc")?.ifBlank { "?" } else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    private fun leer(c: HttpURLConnection): RespuestaApi {
        val code = c.responseCode
        val flujo = if (code >= 400) c.errorStream else c.inputStream
        val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return RespuestaApi(code, runCatching { JSONObject(texto) }.getOrNull())
    }
}
