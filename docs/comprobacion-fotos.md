# Comprobación de fotografías en el chat — #18

Fecha: 23 de noviembre de 2026. Separar lo probado en local de la aceptación
alojada. No se ha desplegado ni escrito en la base alojada.

## Decisiones aplicadas

- Se aceptan JPEG y PNG (detectados por contenido, no por nombre). Se envía
  siempre una **copia JPEG nueva**: lado mayor ≤ 1600 px sin ampliar imágenes
  menores, orientación EXIF aplicada, transparencia sobre blanco, sin EXIF ni
  ubicación y ≤ 2 MB (calidad 85→45; si no cabe, se rechaza). El original del
  teléfono solo se lee.
- El mensaje se crea solo tras confirmar el objeto en Storage. Cancelar, un fallo
  o un rechazo nunca envían nada, y tampoco envían el original.
- Se puede enviar desde el primer contacto, sin cita ni respuesta profesional.
- Salir del chat o girar la pantalla no cancela la subida en curso (ViewModel de
  la actividad). Cerrar la app sí la pierde: no se guarda la copia en disco. Un
  objeto subido pero no confirmado queda huérfano e ilegible para la otra
  cuenta; no hay borrado desde la app (sin políticas de borrado).

## Ejecutado localmente

- pgTAP `chat_photos.test.sql`: 29 comprobaciones correctas (suite completa
  102 en 7 archivos). Cubre: bucket privado de 2 MiB, visitante y tercera
  cuenta rechazados, solo JPEG/PNG (GIF y vídeo rechazados), adjunto antes de
  respuesta o cita, ruta opaca estable al reintentar, conflicto al reutilizar
  UUID con otro contenido, sin mensaje antes del objeto, la otra cuenta no
  escribe ni lee una subida sin confirmar, confirmación idempotente sin
  duplicado, recuperación de confirmación perdida, objeto confirmado inmutable,
  UUID de foto no reutilizable como texto, lectura del profesional vía RLS de
  Storage, sin edición ni borrado, tercera cuenta y administrador sin acceso, y
  rechazo de más de 2 MiB al confirmar.
- `contract_chat_photos.py --local`: contrato real Auth + PostgREST + Storage
  correcto. Cliente y profesional intercambian una foto real; Storage rechaza
  413 (más de 2 MiB), 415 (GIF), 409 (subida repetida) y PUT de
  sobrescritura; el profesional no obtiene enlace antes de la confirmación y sí
  después, y descarga exactamente los bytes enviados; visitante, tercera cuenta
  y administrador no firman ni descargan; la ruta pública no sirve el objeto.
  Solo crea y elimina sus propias cuentas y filas aleatorias en loopback.
- Unitarias JVM (`./gradlew :app:testPruebasDebugUnitTest`): 57 correctas, de
  ellas 24 nuevas. `ChatAttachmentsTest` (12, HTTP simulado con los cuerpos
  reales de Storage medidos en el contrato): orden reserva→subida→confirmación,
  progreso, confirmación perdida sin resubir, 409 tras respuesta perdida,
  tamaño excesivo, formato, cuota, RLS y permisos diferenciados de fallo de red,
  cancelación sin confirmar y URL firmada breve. `ChatPhotoTest` (5): detección
  JPEG/PNG, 1600 px sin ampliar, bajada de calidad y rechazo si no cabe.
  `ChatPhotoSenderTest` (7): progreso, reintento con el mismo UUID, nueva foto con
  UUID nuevo, cancelación durante compresión y subida, errores explicados.
  No acreditan la base alojada.
- `python3 scripts/android.py check` (compilación, unitarias y lint con las
  cadenas ES/EN/FR/DE completas) y `:app:compilePruebasDebugAndroidTestKotlin
  :app:assemblePruebasDebug`: correctos.

## Escrito pero no ejecutado

- `ChatPhotoPrepareTest` (decodificador real: 4000×3000 → 1600×1200 sin GPS y
  original idéntico byte a byte; PNG 640×480 sin ampliar; WebP rechazado) y
  `ChatPhotoUiTest` (la otra cuenta ve la foto y se pide su URL). Necesitan un
  emulador **desechable** asignado; no se ha usado emulator-5554.

## Pendiente antes de dar por aceptada la issue

1. Ejecutar las pruebas instrumentadas en un emulador desechable.
2. Desplegar de forma revisada `20261122001800_chat_photos.sql` en la base
   alojada (crea el bucket `chat-attachments`). Sin `db reset`, seed ni borrados.
   Comprobar antes la cuota de Storage del plan Free.
3. En dos emuladores con el APK del mismo commit y dos cuentas verificadas:
   1. Como cliente, abrir un perfil, «Enviar mensaje» y «Enviar foto» con una
      foto grande de la cámara: se ve «Comprimiendo…» y el porcentaje.
   2. Cancelar a mitad: no aparece mensaje en ninguna cuenta.
   3. Enviar de nuevo; como profesional, abrir la conversación, ver la miniatura
      y abrirla a pantalla completa sin haber respondido.
   4. Con modo avión durante la subida: aviso de fallo; reintentar al volver la
      red y comprobar que aparece una sola foto.
   5. Elegir una imagen HEIC/WebP o GIF: aviso de formato y nada enviado.
   6. Comprobar en la galería que el original no ha cambiado.
   7. Revisar textos y disposición en ES, EN, FR y DE (también a 320×640 dp).
4. El mensaje de cuota se dispara por el texto «quota» de Storage; no se ha
   podido provocar una cuota real en local. Verificarlo solo si David lo decide.
