# Comprobación de vídeos en el chat — #19

Fecha: 23 de noviembre de 2026. Separar lo probado en local de la aceptación
alojada. No se ha desplegado ni escrito en la base alojada.

## Decisiones aplicadas

- Mismo recorrido privado que las fotos (#18): reserva con UUID de petición,
  subida al bucket `chat-attachments`, confirmación idempotente y URL firmada de
  10 minutos. No hay bucket, tabla ni RPC nuevos.
- Se acepta cualquier vídeo que el teléfono sepa leer; siempre se envía una
  **copia MP4 nueva** H.264/AAC hecha con Media3 Transformer 1.6.1: lado corto
  ≤ 720 px sin ampliar vídeos menores, proporción y orientación conservadas,
  sin metadatos de ubicación y ≤ 20 MiB. Bitrate calculado para que el clip más
  largo quepa con margen (≈ 2,2 Mbit/s de vídeo + 128 kbit/s de audio, máximo
  2,5 Mbit/s y nunca por encima del original). Si el codificador se pasa, se
  repite una vez al 60 %; si aún no cabe, se avisa y no se envía nada.
- Duración máxima **40 segundos** (decisión de David del 2026-11-23; antes un
  minuto), comprobada **antes** de comprimir y otra vez sobre la copia. Se
  admiten hasta 499 ms de más para que un clip que la galería muestra como
  «0:40» no se rechace.
- El resultado se vuelve a leer antes de subirlo (contenedor MP4, tamaño,
  duración y resolución). Un fallo de compresión o un resultado incompatible
  se explica y **nunca** se sustituye por el original. El original solo se lee;
  la copia temporal vive en la caché de la app y se borra siempre.
- Un adjunto a la vez por conversación (foto o vídeo). Progreso de compresión
  y de subida en porcentaje; «Cancelar» detiene cualquiera de los dos y no
  crea mensaje.
- Reproducción con ExoPlayer a pantalla completa; el enlace firmado se pide al
  tocar el vídeo, así que reabrir el chat siempre vuelve a poder reproducirlo.
- Igual que con las fotos: salir del chat o girar la pantalla no cancela el
  envío; cerrar la app sí lo pierde. Un objeto subido sin confirmar queda
  huérfano e ilegible para la otra cuenta.

## Ejecutado localmente

- pgTAP `chat_videos.test.sql`: 20 comprobaciones correctas (suite completa
  317 en 16 archivos, tras integrar `spec/11-demo`). Cubre bucket único y
  privado de 20 MiB con MP4, fotos que siguen en 2 MiB, tercera cuenta
  rechazada, solo `video/mp4` y nunca como foto, reserva antes de cualquier
  respuesta con ruta opaca `.mp4`, sin mensaje antes del objeto, sin lectura
  previa por la otra cuenta, confirmación idempotente sin duplicado, lectura
  del profesional, tercera cuenta y administrador sin acceso, y más de 20 MiB
  rechazado al confirmar. `chat_photos.test.sql` se ha adaptado: el límite de
  2 MiB se comprueba en la función y la prueba de «vídeo deshabilitado» pasa a
  «un MP4 no puede enviarse como foto».
- `contract_chat_videos.py --local` (2 pruebas, Auth + PostgREST + Storage
  reales): el cliente envía un MP4 real antes de cualquier respuesta; Storage
  rechaza 413 (más de 20 MiB) y 415 (`video/quicktime`); el profesional no
  obtiene enlace antes de confirmar y después descarga exactamente los bytes
  enviados; visitante, tercera cuenta y administrador no firman ni descargan;
  sin ruta pública. Un vídeo de 5 MiB cabe, y un archivo de 5 MiB reservado
  como foto se rechaza al confirmar sin crear mensaje.
  `contract_chat_photos.py` ajusta su prueba de tamaño al nuevo límite del
  bucket. Los 13 contratos `--local` pasan.
- Unitarias JVM: 126 correctas en 19 clases (tras integrar `spec/11-demo`).
  Nuevas o cambiadas: `ChatVideoTest` (9: límites, duración y tolerancia,
  archivo ilegible, 720p con rotación y proporción, sin ampliar, bitrate,
  reintento al 60 % y rechazo sin original, MP4 por contenido, comprobación del
  resultado), `ChatAttachmentSenderTest` (11, antes `ChatPhotoSenderTest`:
  progreso de compresión, envío como `video/mp4`, vídeo largo sin subir, error
  de compresión sin original, un adjunto a la vez, reintento con el mismo UUID
  y bytes, cancelación en compresión y subida) y `ChatAttachmentsTest` (+1:
  vídeo por la misma ruta privada con su tipo). No acreditan la base alojada.
- `python3 scripts/android.py check` y
  `:app:compilePruebasDebugAndroidTestKotlin :app:assemblePruebasDebug`:
  correctos. Lint sin avisos en los archivos nuevos.

## Escrito pero no ejecutado

- `ChatVideoPrepareTest` (Transformer real: 1080p → 720p MP4 con progreso y
  original idéntico byte a byte, 360p sin ampliar, 62 s rechazado, archivo no
  vídeo rechazado) y `ChatVideoUiTest` (la otra cuenta ve el vídeo y el enlace
  se pide solo al abrirlo). Necesitan un emulador **desechable** asignado; no se
  ha usado emulator-5554. Es la «validación temprana» de compatibilidad en
  emulador y API mínima (26) que pide la issue: está pendiente.
- Fixtures sintéticos propios (sin material de terceros) generados con
  `swift scripts/make_video_fixture.swift <salida> [ancho alto segundos]`.

## Pendiente antes de dar por aceptada la issue

1. Ejecutar `ChatVideoPrepareTest` y `ChatVideoUiTest` en un emulador
   desechable con API 35 y en otro con API 26 (mínima).
2. Desplegar de forma revisada `20261123001900_chat_videos.sql` en la base
   alojada, después de `20261122001800_chat_photos.sql`. Sin `db reset`, seed
   ni borrados. Comprobar antes la cuota de Storage del plan Free y que su
   límite global de subida admite 20 MiB.
3. En dos emuladores con el APK del mismo commit y dos cuentas verificadas:
   1. Como cliente, abrir un perfil, «Enviar mensaje» y «Enviar vídeo» con un
      vídeo de la cámara de unos 30 s: se ve «Comprimiendo el vídeo… N %» y
      después «Enviando vídeo… N %».
   2. Cancelar durante la compresión y durante la subida: no aparece mensaje.
   3. Enviar de nuevo; como profesional, sin haber respondido, abrir la
      conversación, tocar el vídeo y reproducirlo con sonido.
   4. Cerrar y reabrir la app en ambas cuentas: el vídeo sigue y se reproduce.
   5. Modo avión durante la subida: aviso; reintentar con red y comprobar que
      aparece un solo vídeo.
   6. Elegir un vídeo de más de 40 segundos (por ejemplo de 45 s): aviso de
      duración y nada enviado. Uno de 40 s se envía.
   7. Comprobar en la galería que el original no ha cambiado.
   8. Revisar textos y disposición en ES, EN, FR y DE (también a 320×640 dp:
      los dos botones se reparten en filas si no caben).
4. Confirmar en un teléfono real (o grabación de cámara del emulador) que un
   vídeo vertical se envía y reproduce derecho.

## Corrección del 23 de noviembre de 2026: máximo de 40 segundos

David redujo la duración máxima a 40 segundos con el mismo margen de 499 ms.
Solo cambia la app (`VIDEO_MAX_DURATION_MS = 40_000`): el servidor nunca validó
la duración, así que no hay migración. Textos de límite y de error en ES, EN,
FR y DE. Con 40 s el bitrate de vídeo queda en el máximo de 2,5 Mbit/s y el
clip más largo ocupa unos 13 MB, por debajo de 20 MiB.

- `ChatVideoTest` (JVM): 9 pruebas correctas; 40 000 y 40 499 ms se aceptan,
  40 500 ms se rechaza antes de comprimir y en la copia comprimida.
- `ChatVideoPrepareTest` (instrumentada) sigue usando el vídeo de 62 s y ahora
  se llama `aVideoLongerThanFortySecondsIsRefusedBeforeCompressing`; compila,
  **no ejecutada** (sin emulador desechable asignado).
- Pendiente: repetir el paso 3.6 del recorrido con un vídeo de 45 s y otro de
  40 s, y revisar los textos nuevos en los cuatro idiomas.

