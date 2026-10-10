# Asistencia RIS Angaraes · App Android (Kotlin)

App para que el personal registre su asistencia con **código QR del establecimiento + foto + GPS**,
con o sin internet. Usa el mismo servidor de siempre (`api.R` + `nucleo.R`); el panel de RRHH
sigue siendo `app.R` (Shiny).

## 1. Servidor (una sola vez)

1. Reemplace `api.R` por el de la carpeta `servidor/`. Solo agrega la ruta `POST /qr`
   (usuario y clave de RRHH → textos QR firmados). No cambia nada de lo existente.
2. Reinicie la API:
   `Rscript -e "plumber::pr_run(plumber::pr('api.R'), host='127.0.0.1', port=8000)"`
3. Los celulares deben poder llegar a la API. Si hoy `captura.html` ya funciona desde los
   teléfonos, use esa misma dirección (por ejemplo `https://su-dominio/api`). Si no hay un
   proxy (nginx/Apache) delante, inicie la API con `host='0.0.0.0'` y use `http://IP:8000`.
   **Recomendado: https**, porque viajan el PIN y la clave de RRHH.

Prueba rápida desde el navegador de un celular: `https://su-dominio/api/ping` debe responder `{"ok":true,...}`.

## 2. Generar el APK

1. Instale **Android Studio** (gratuito) en una computadora con internet.
2. *File > Open* → elija la carpeta `AsistenciaRIS`. Espere a que descargue Gradle y las librerías.
3. (Opcional) En `app/build.gradle.kts` escriba la dirección del servidor en `SERVIDOR_DEFECTO`
   para que el personal no tenga que escribirla.
4. *Build > Build App Bundle(s) / APK(s) > Build APK(s)*. El archivo queda en
   `app/build/outputs/apk/debug/app-debug.apk`.
5. Para distribuirlo oficialmente: *Build > Generate Signed App Bundle / APK* (cree una llave y
   guárdela bien: la necesitará para cada actualización).
6. Envíe el APK por WhatsApp o USB. En el teléfono, permita "instalar apps de origen desconocido".

## 3. Uso

**Personal asistencial y administrativo**
1. Primera vez (con señal): dirección del servidor → DNI y PIN (los mismos de `captura.html`).
2. Cada marcación: pulse *Ingreso / Salida / Retorno / Salida final* → escanee el QR de la pared →
   tómese la foto. La ubicación GPS se toma sola. Los botones resaltados son los que corresponden
   a su turno de hoy.
3. Sin señal, la marcación queda guardada y se envía sola cuando vuelve la conexión
   (también puede pulsar *Enviar ahora*).

**RRHH / Dirección**
- *Códigos QR* → usuario y clave del sistema web → elija el mes → *Imprimir* o *Compartir PDF*
  (una hoja A4 por establecimiento). La Dirección (admin) obtiene todas las IPRESS; RRHH, la suya.
- *Probar lectura* comprueba una hoja ya pegada (firma y vigencia).
- Imprima la hoja del mes siguiente en la última semana del mes: cada QR se acepta en su mes,
  el anterior y el siguiente.

## 4. Controles que aplica la app

- El QR debe ser de la RIS, de **su** establecimiento y estar vigente (la firma la verifica el servidor).
- Foto obligatoria con marca de agua (nombre, DNI, fecha y hora, GPS, QR).
- Con conexión, GPS obligatorio (regla de `nucleo.R`). Rechaza ubicaciones simuladas (apps de GPS falso).
- Avisa si la persona está fuera del radio de la geocerca y si la hora del teléfono fue alterada.
- Las marcaciones pendientes de otra persona que usó el mismo teléfono no se envían con el DNI equivocado.

## 5. Estructura

```
servidor/api.R                 API con la nueva ruta /qr
app/src/main/java/.../
  MainActivity.kt              navegación y componentes comunes
  PantallaMarcar.kt            flujo QR → foto → GPS → envío o cola
  PantallaQr.kt                generar, imprimir, compartir y probar QR (RRHH)
  Pantallas.kt                 servidor, vincular, historial, ajustes
  Qr.kt                        lectura/validación, imagen QR, PDF e impresión
  Sincronizador.kt             envío a /sync y trabajo en segundo plano
  Almacen.kt                   datos guardados en el teléfono y cola sin conexión
  Ubicacion.kt, Foto.kt, Api.kt, Datos.kt
```
