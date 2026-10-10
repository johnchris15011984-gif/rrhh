package pe.gob.risangaraes.asistencia

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Envía las marcaciones guardadas a /sync de api.R, una por una, y actualiza el perfil. */
object Sincronizador {
    private val candado = Mutex()

    data class Resultado(val enviadas: Int, val rechazadas: Int, val quedan: Int, val error: String?)

    suspend fun sincronizar(ctx: Context): Resultado {
        candado.withLock {
        val a = Almacen(ctx)
        val tok = a.token
        val per = a.perfil
        if (tok == null || per == null) return Resultado(0, 0, a.cantidadPendientes(), "Teléfono no vinculado")
        var enviadas = 0
        var rechazadas = 0
        for (f in a.pendientes()) {
            val m = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (m == null) { f.delete(); continue }
            if (m.optString("dni") != per.dni) continue          // es de otra persona que usó este teléfono
            val cuerpo = JSONObject(m.toString()).apply {
                remove("dni")
                put("token", tok)
                put("ts_envio", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
            }
            val r = try {
                Api.post(a.servidor, "/sync", cuerpo, timeoutMs = 60_000)
            } catch (e: IOException) {
                return Resultado(enviadas, rechazadas, a.cantidadPendientes(), "Sin conexión con el servidor")
            }
            when {
                r.ok -> {
                    val estado = if (r.codigo.startsWith("DUP")) "Ya registrada" else "Enviada"
                    a.agregarHistorial(m, estado, r.msg.ifBlank { "Registrada" })
                    f.delete(); enviadas++
                }
                r.http == 401 || r.codigo == "TOKEN" -> {
                    a.tokenRechazado = true
                    return Resultado(enviadas, rechazadas, a.cantidadPendientes(), r.msg.ifBlank { "Teléfono no vinculado" })
                }
                r.definitivo || r.http == 422 || r.http == 413 -> {
                    a.agregarHistorial(m, "Rechazada", r.msg.ifBlank { "Rechazada por el servidor" })
                    f.delete(); rechazadas++
                }
                else -> return Resultado(enviadas, rechazadas, a.cantidadPendientes(),
                    r.msg.ifBlank { "Error del servidor (${r.http}); se reintentará" })
            }
        }
        actualizarPerfil(ctx)
        return Resultado(enviadas, rechazadas, a.cantidadPendientes(), null)
        }
    }

    /** Descarga programación y horarios actualizados. Devuelve false si no hubo conexión. */
    suspend fun actualizarPerfil(ctx: Context): Boolean {
        val a = Almacen(ctx)
        val tok = a.token ?: return false
        return try {
            val r = Api.post(a.servidor, "/perfil", JSONObject().put("token", tok))
            if (r.ok) {
                r.json?.optJSONObject("perfil")?.let {
                    a.perfilJson = it.toString()
                    a.guardarReloj(it.texto("servidor_utc"))
                }
                a.tokenRechazado = false
            } else if (r.http == 401) a.tokenRechazado = true
            true
        } catch (e: IOException) {
            false
        }
    }
}

/** Trabajo en segundo plano: Android lo ejecuta cuando hay internet, aunque la app esté cerrada. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = Sincronizador.sincronizar(applicationContext)
        val reintentar = r.error != null && r.quedan > 0 && !Almacen(applicationContext).tokenRechazado
        return if (reintentar) Result.retry() else Result.success()
    }

    companion object {
        private val conRed = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun programar(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(conRed).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync_periodico", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun ahora(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(conRed)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync_ahora", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
