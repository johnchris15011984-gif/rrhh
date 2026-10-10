# ============================================================
# api.R - API que usa el celular (captura.html) para vincularse y enviar marcaciones
# Ejecutar desde la carpeta de la app (puede iniciarse antes o después de app.R):
#   Rscript -e "plumber::pr_run(plumber::pr('api.R'), host='127.0.0.1', port=8000)"
# ============================================================
library(plumber); library(jsonlite)
# Carga archivos .R como UTF-8 sin depender del idioma del sistema (evita dañar tildes y ñ)
cargar <- function(f) for (e in parse(f, encoding = "UTF-8")) eval(e, globalenv())
cargar("nucleo.R")
init_db()

# Cabeceras: sin caché; CORS solo si se define TURNOS_CORS (por defecto no hace falta:
# la página y la API se publican bajo el mismo dominio).
#* @filter cabeceras
function(req, res) {
  res$setHeader("Cache-Control", "no-store")
  cors <- Sys.getenv("TURNOS_CORS", "")
  if (nzchar(cors)) {
    res$setHeader("Access-Control-Allow-Origin", cors)
    res$setHeader("Access-Control-Allow-Headers", "Content-Type")
    res$setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    if (req$REQUEST_METHOD == "OPTIONS") { res$status <- 204; return(list()) }
  }
  largo <- suppressWarnings(as.numeric(req$HTTP_CONTENT_LENGTH %||% 0))
  if (!is.na(largo) && largo > 8 * 1024^2) { res$status <- 413; return(list(ok = FALSE, codigo = "TAMANO", msg = "Envío demasiado grande")) }
  plumber::forward()
}

cuerpo <- function(req) tryCatch(fromJSON(req$postBody, simplifyVector = TRUE), error = function(e) NULL)
num <- function(x) { v <- suppressWarnings(as.numeric(x)); if (length(v) != 1 || is.na(v)) NA_real_ else v }
txt <- function(x) if (is.character(x) && length(x) == 1) x else NULL

# Perfil que se guarda en el teléfono: datos del trabajador, su IPRESS, su programación y los horarios
perfil <- function(dni) {
  p <- q("SELECT dni, nombres, condicion, ipress FROM personal WHERE dni=?", dni)
  ip <- q("SELECT codigo, nombre, distrito, categoria, lat, lon, radio_m FROM ipress WHERE nombre=?", p$ipress)
  desde <- Sys.Date() - 1; hasta <- Sys.Date() + 45
  pr <- q("SELECT anio, mes, dia, turno FROM programacion WHERE dni=? AND anio*10000+mes*100+dia BETWEEN ? AND ?",
          dni, as.integer(format(desde, "%Y%m%d")), as.integer(format(hasta, "%Y%m%d")))
  prog <- if (nrow(pr)) data.frame(fecha = sprintf("%04d-%02d-%02d", pr$anio, pr$mes, pr$dia), turno = pr$turno) else
    data.frame(fecha = character(), turno = character())
  H <- HORARIOS[HORARIOS$cond %in% c("*", p$condicion), ]
  hor_l <- lapply(split(H, H$turno), function(h) {
    h <- h[order(match(h$tipo, TIPOS), h$cond == "*"), ]; h <- h[!duplicated(h$tipo), ]   # la condición propia pisa a "*"
    lapply(seq_len(nrow(h)), function(i) list(tipo = h$tipo[i], desde = h$desde[i], hasta = h$hasta[i]))
  })
  list(dni = p$dni, nombres = p$nombres, condicion = p$condicion,
       ipress = if (nrow(ip)) as.list(ip[1, ]) else list(nombre = p$ipress),
       programacion = prog, horarios = hor_l, tolerancia_min = TOLERANCIA_MIN,
       gps_precision_max_m = GPS_PRECISION_MAX_M, servidor_utc = format(Sys.time(), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"))
}

#* Comprobar que el servidor responde (el teléfono lo usa para saber si hay conexión)
#* @get /ping
#* @serializer json list(auto_unbox = TRUE)
function() list(ok = TRUE, servidor_utc = format(Sys.time(), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"))

#* Vincular el teléfono: DNI + PIN (una sola vez, con conexión). Devuelve un token y el perfil
#* @post /vincular
#* @serializer json list(auto_unbox = TRUE, na = "null", digits = NA)
function(req, res) {
  b <- cuerpo(req)
  if (is.null(b)) { res$status <- 400; return(list(ok = FALSE, codigo = "JSON", msg = "Datos inválidos")) }
  v <- verificar_pin(b$dni, txt(b$pin))
  if (!isTRUE(v$ok)) { res$status <- if (v$codigo == "BLOQUEO") 429 else 401; return(v) }
  tok <- crear_dispositivo(v$dni, txt(b$equipo))
  list(ok = TRUE, token = tok, perfil = perfil(v$dni))
}

#* Actualizar el perfil (programación, horarios, ubicación de la IPRESS)
#* @post /perfil
#* @serializer json list(auto_unbox = TRUE, na = "null", digits = NA)
function(req, res) {
  b <- cuerpo(req); dni <- dni_de_token(txt(b$token))
  if (is.na(dni)) { res$status <- 401; return(list(ok = FALSE, codigo = "TOKEN", msg = "Teléfono no vinculado: vuelva a vincularlo con su DNI y PIN")) }
  list(ok = TRUE, perfil = perfil(dni))
}

#* Recibir una marcación: token, tipo, ts (ISO UTC), lat, lon, acc, foto (JPEG base64), uuid, en_linea, qr, ts_envio
#* @post /sync
#* @serializer json list(auto_unbox = TRUE, na = "null", digits = NA)
function(req, res) {
  b <- cuerpo(req)
  if (is.null(b)) { res$status <- 400; return(list(ok = FALSE, codigo = "JSON", msg = "Datos inválidos", definitivo = TRUE)) }
  dni <- dni_de_token(txt(b$token))
  if (is.na(dni)) { res$status <- 401; return(list(ok = FALSE, codigo = "TOKEN", msg = "Teléfono no vinculado: vuelva a vincularlo con su DNI y PIN")) }
  r <- tryCatch(
    registrar_marcacion(dni, txt(b$tipo), txt(b$ts), num(b$lat), num(b$lon), num(b$acc), txt(b$foto), txt(b$uuid),
                        isTRUE(b$en_linea), txt(b$qr), txt(b$ts_envio)),
    error = function(e) { message("Error /sync: ", conditionMessage(e)); res$status <- 500
      list(ok = FALSE, codigo = "ERROR", msg = "Error del servidor; se reintentará", definitivo = FALSE) })
  if (!isTRUE(r$ok) && isTRUE(r$definitivo)) res$status <- 422
  r
}

#* Códigos QR de las IPRESS para imprimir desde la app Android (usuario y clave del sistema web).
#* El secreto de los QR nunca sale del servidor: el teléfono solo recibe el texto ya firmado.
#* Admin recibe todas las IPRESS; RRHH solo la suya. Periodo: mes actual o siguiente (AAAAMM).
#* @post /qr
#* @serializer json list(auto_unbox = TRUE, na = "null", digits = NA)
function(req, res) {
  b <- cuerpo(req)
  if (is.null(b)) { res$status <- 400; return(list(ok = FALSE, codigo = "JSON", msg = "Datos inválidos")) }
  usr <- trimws(txt(b$usuario) %||% ""); clave <- txt(b$clave)
  llave <- paste0("usr:", usr)   # reutiliza la tabla de intentos fallidos (bloqueo por fuerza bruta)
  desde <- format(Sys.time() - BLOQUEO_MIN * 60, "%Y-%m-%d %H:%M:%S")
  if (q("SELECT COUNT(*) n FROM intentos_pin WHERE dni=? AND ts>=?", llave, desde)$n >= MAX_INTENTOS_PIN) {
    res$status <- 429
    return(list(ok = FALSE, codigo = "BLOQUEO", msg = sprintf("Demasiados intentos fallidos. Espere %d minutos.", BLOQUEO_MIN)))
  }
  u <- q("SELECT usuario, clave, rol, ipress FROM usuarios WHERE usuario=?", usr)
  ok <- nzchar(usr) && nrow(u) == 1 && !is.null(clave) && nzchar(clave) &&
        isTRUE(tryCatch(password_verify(u$clave, clave), error = function(e) FALSE))
  if (!ok) {
    ex("INSERT INTO intentos_pin VALUES(?,?)", llave, ahora())
    res$status <- 401; return(list(ok = FALSE, codigo = "AUTH", msg = "Usuario o clave incorrectos"))
  }
  ex("DELETE FROM intentos_pin WHERE dni=?", llave)
  permitidos <- format(Sys.Date() %m+% months(0:1), "%Y%m")
  per <- txt(b$periodo) %||% permitidos[1]
  if (!(per %in% permitidos)) { res$status <- 422; return(list(ok = FALSE, codigo = "PERIODO", msg = "Solo se generan códigos del mes actual o del siguiente")) }
  d <- q("SELECT codigo, nombre, distrito, categoria FROM ipress WHERE codigo IS NOT NULL AND codigo<>'' ORDER BY distrito, nombre")
  if (!identical(u$rol, "admin")) d <- d[d$nombre %in% u$ipress, ]
  list(ok = TRUE, periodo = per, usuario = u$usuario, rol = u$rol,
       items = lapply(seq_len(nrow(d)), function(i) list(codigo = as.character(d$codigo[i]), nombre = d$nombre[i],
         distrito = d$distrito[i] %||% "", categoria = d$categoria[i] %||% "", texto = qr_texto(d$codigo[i], per))))
}
