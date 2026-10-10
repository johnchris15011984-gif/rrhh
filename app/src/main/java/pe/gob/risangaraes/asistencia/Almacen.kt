package pe.gob.risangaraes.asistencia

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Lo que se guarda dentro del teléfono: dirección del servidor, token de vinculación, perfil,
 * la cola de marcaciones pendientes (un archivo por marcación) y el historial de envíos.
 */
class Almacen(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("asistencia", Context.MODE_PRIVATE)
    private val dirPend = File(app.filesDir, "pendientes").apply { mkdirs() }

    var servidor: String
        get() = p.getString("servidor", null) ?: BuildConfig.SERVIDOR_DEFECTO
        set(v) = p.edit().putString("servidor", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()

    /** true cuando el servidor rechazó el token (RRHH desvinculó el teléfono o dio de baja a la persona). */
    var tokenRechazado: Boolean
        get() = p.getBoolean("token_rechazado", false)
        set(v) = p.edit().putBoolean("token_rechazado", v).apply()

    var perfilJson: String?
        get() = p.getString("perfil", null)
        set(v) = p.edit().putString("perfil", v).apply()

    val perfil: Perfil?
        get() = perfilJson?.let { runCatching { Perfil.desdeJson(JSONObject(it)) }.getOrNull() }

    fun desvincular() {
        p.edit().remove("token").remove("perfil").remove("token_rechazado").apply()
    }

    // ---------- Reloj: detecta si alguien cambió la hora del teléfono ----------
    fun guardarReloj(servidorUtc: String?) {
        val t = servidorUtc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        p.edit().putLong("srv_ms", t).putLong("srv_elapsed", SystemClock.elapsedRealtime()).apply()
    }

    /** Minutos de diferencia entre la hora del teléfono y la del servidor (estimada desde el último contacto). */
    fun desfaseRelojMin(): Long? {
        val srv = p.getLong("srv_ms", 0L); val el = p.getLong("srv_elapsed", 0L)
        val ahoraEl = SystemClock.elapsedRealtime()
        if (srv == 0L || ahoraEl < el) return null           // el teléfono se reinició: no se puede estimar
        val estimado = srv + (ahoraEl - el)
        return (System.currentTimeMillis() - estimado) / 60_000
    }

    // ---------- Cola de marcaciones pendientes ----------
    @Synchronized
    fun encolar(m: JSONObject) {
        val f = File(dirPend, m.getString("uuid") + ".json")
        val tmp = File(dirPend, f.name + ".tmp")
        tmp.writeText(m.toString())
        if (!tmp.renameTo(f)) { f.writeText(m.toString()); tmp.delete() }
    }

    fun pendientes(): List<File> =
        (dirPend.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.lastModified() }

    fun cantidadPendientes(): Int = pendientes().size

    /** Pendientes sin la foto (para mostrarlas en pantalla). */
    fun resumenPendientes(): List<JSONObject> = pendientes().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).apply { remove("foto"); remove("qr") } }.getOrNull()
    }

    // ---------- Historial de envíos (últimos 80) ----------
    @Synchronized
    fun agregarHistorial(m: JSONObject, estado: String, resultado: String) {
        val nuevo = JSONObject()
            .put("uuid", m.optString("uuid")).put("tipo", m.optString("tipo"))
            .put("ts", m.optString("ts")).put("estado", estado).put("resultado", resultado)
        val viejo = historial()
        val a = JSONArray().put(nuevo)
        viejo.take(79).forEach { a.put(it) }
        p.edit().putString("historial", a.toString()).apply()
    }

    fun historial(): List<JSONObject> =
        runCatching { JSONArray(p.getString("historial", "[]")).objetos() }.getOrDefault(emptyList())

    fun historialDe(uuid: String): JSONObject? = historial().firstOrNull { it.optString("uuid") == uuid }
}
