package pe.gob.risangaraes.asistencia

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Punto(val lat: Double, val lon: Double, val acc: Float, val simulada: Boolean)

private const val PRECISION_BUENA_M = 30f

private fun Location.aPunto() = Punto(
    latitude, longitude, if (hasAccuracy()) accuracy else 999f,
    simulada = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)

/**
 * Obtiene la ubicación con el GPS del teléfono (no necesita internet ni Google Play).
 * Espera hasta [timeoutMs] una lectura con precisión de 30 m o mejor; si no llega, usa la mejor obtenida.
 * Devuelve null si la ubicación está apagada o no hubo ninguna lectura.
 */
@SuppressLint("MissingPermission")
suspend fun obtenerUbicacion(ctx: Context, timeoutMs: Long = 25_000): Punto? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (proveedores.isEmpty()) return null

    var mejor: Location? = proveedores
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 60_000 }
        .minByOrNull { it.accuracy }
    mejor?.let { if (it.accuracy <= PRECISION_BUENA_M) return it.aPunto() }

    val buena = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Location> { cont ->
            val escucha = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    if (mejor == null || l.accuracy < mejor!!.accuracy) mejor = l
                    if (l.accuracy <= PRECISION_BUENA_M && cont.isActive) {
                        lm.removeUpdates(this)
                        cont.resume(l)
                    }
                }
                // Se implementan todos los métodos para que funcione en Android 7 a 10
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                proveedores.forEach { lm.requestLocationUpdates(it, 1000L, 0f, escucha, Looper.getMainLooper()) }
            } catch (e: SecurityException) {
                lm.removeUpdates(escucha)
            }
            cont.invokeOnCancellation { lm.removeUpdates(escucha) }
        }
    }
    return (buena ?: mejor)?.aPunto()
}
