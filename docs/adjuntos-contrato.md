# Adjuntos privados del chat (fotografías) — #18

## Interfaz compartida

Migración nueva, posterior a búsqueda: `20261122001800_chat_photos.sql`.
No modifica migraciones aplicadas ni carga, borra o reinicia datos preparados.
#19 (vídeos) reutiliza esta misma frontera; no crea otro bucket ni otra tabla.

- **Bucket** `chat-attachments`: privado, `file_size_limit` 2 MiB,
  `allowed_mime_types` `image/jpeg`, `image/png`. #19 debe ampliar el límite
  del bucket (p. ej. 20 MiB) y añadir `video/mp4` con `update storage.buckets`;
  el límite por tipo lo sigue imponiendo la base (ver más abajo).
- `public.chat_messages` gana columnas `attachment_kind` (`photo`/`video`),
  `attachment_path` (única), `attachment_type` (MIME) y `attachment_size`.
  La restricción `chat_messages_body_check` se sustituye por
  `chat_messages_content`: un texto sigue necesitando 1–4000 caracteres; un
  adjunto lleva `body = ''`. `send_text` no cambia.
- `private.chat_uploads`: reserva de subida por conversación/autor/UUID de
  petición, con tipo, MIME, SHA-256 y ruta. Sin permisos para la app.
- Punto de extensión: `private.chat_attachment_limit(kind, mime) → bigint`
  (null = no admitido). Hoy solo `('photo','image/jpeg'|'image/png') → 2097152`.
  #19 lo reemplaza con `create or replace` añadiendo `('video','video/mp4')`.
  `private.chat_attachment_extension(mime)` ya conoce `.mp4`.

RPC (solo `authenticated`, cuenta verificada, participante de la conversación;
el autor sale de Auth):

- `request_chat_attachment(p_conversation_id uuid, p_request_id uuid, p_kind text,
  p_mime text, p_sha256 text)` → `{object_path, status, message}`.
  `status` = `uploading` (subir a `object_path`) o `sent` (ya confirmado;
  `message` trae el mensaje y no hay que volver a subir). Ruta opaca
  `<conversation_id>/<request_id>.<ext>`, sin UUID de cuenta.
  Errores: `42501 permission_denied`, `22023 invalid_attachment`,
  `22023 unsupported_attachment`, `22023 retry_conflict` (mismo UUID con otro
  contenido/tipo o ya usado para texto).
- `submit_chat_attachment(p_conversation_id uuid, p_request_id uuid)` → mensaje
  JSON (`id`, `conversation_id`, `author_id`, `request_id`, `body`, `created_at`,
  `attachment_kind`, `attachment_path`, `attachment_type`, `attachment_size`).
  Solo crea el mensaje si Storage contiene el objeto con el MIME reservado y
  tamaño 1..límite. Idempotente: reintentar devuelve el mismo mensaje.
  Errores: `42501`, `22023 upload_not_found`, `upload_not_complete`,
  `unsupported_attachment`, `attachment_too_large`, `retry_conflict`.
- Políticas de `storage.objects` (bucket `chat-attachments`):
  INSERT si `can_write_chat_attachment(name)` (autor, reserva viva, aún sin
  mensaje); SELECT si `can_read_chat_attachment(name)` (participante y adjunto
  ya confirmado). Sin UPDATE/DELETE ni `upsert`: objetos inmutables. Sin acceso
  anónimo ni excepción administrativa; conocer los identificadores no basta.
- Lectura: URL firmada de 10 minutos (`createSignedUrl`). Nunca URL pública.

Frontera Kotlin `chat/ChatAttachments.kt`:
`send(conversationId, requestId, kind, mime, bytes): Flow<AttachmentProgress>`
(`Uploading(sent,total)` … `Sent(message)`; cancelar el colector cancela la
subida) y `url(path)`. Rechazos definitivos como
`ChatAttachmentException(AttachmentFailure.ACCESS|TOO_LARGE|UNSUPPORTED|QUOTA|CONFLICT)`;
cualquier otra excepción es entrega incierta y se reintenta con el mismo UUID.
`ChatPhotoSender` (ViewModel) guarda la copia comprimida y su UUID hasta
confirmar o cancelar. `chat/ChatPhoto.kt` comprime (`preparePhoto`).

## Comprobación reproducible

```bash
supabase test db                       # incluye chat_photos.test.sql
python3 scripts/contract_chat_photos.py --local
python3 scripts/android.py check
```

Resultados y recorrido pendiente: [comprobación](comprobacion-fotos.md).

## Vídeos — #19

Migración nueva: `20261123001900_chat_videos.sql`. No modifica migraciones
aplicadas ni datos preparados. Reutiliza la frontera de #18 sin cambios de
RPC, tabla, políticas ni bucket:

- `private.chat_attachment_limit` (con `create or replace`) añade
  `('video','video/mp4') → 20971520` (20 MiB). Fotos siguen en 2 MiB.
- Bucket `chat-attachments`: `file_size_limit` 20 MiB y
  `allowed_mime_types` `image/jpeg`, `image/png`, `video/mp4`. Storage rechaza
  más de 20 MiB (413) y otros tipos (415); el límite de 2 MiB de las fotos se
  impone al confirmar (`attachment_too_large`), así que una foto grande que
  Storage acepte nunca llega a ser mensaje.
- El servidor no puede comprobar duración ni resolución: 40 segundos (más un
  margen de 499 ms; antes un minuto, decisión de David del 2026-11-23) y 720p
  los garantiza la compresión de la app y su comprobación del resultado.
- Lectura igual que las fotos: URL firmada de 10 minutos pedida al abrir el
  vídeo, nunca URL pública; sin acceso de terceros ni administrativo.

Kotlin: `AttachmentKind.VIDEO`, `AttachmentFailure.TOO_LONG` (solo en el
dispositivo), `PreparedAttachment` (foto o vídeo) y `ChatAttachmentSender`
(antes `ChatPhotoSender`; estados `AttachmentSendState` con tipo y progreso de
compresión). `chat/ChatVideo.kt` comprime con Media3 Transformer
(`prepareVideo`). La interfaz está en `ChatAttachmentUi.kt`.

```bash
supabase test db                       # incluye chat_videos.test.sql
python3 scripts/contract_chat_videos.py --local
```

Resultados y recorrido pendiente: [comprobación](comprobacion-videos.md).
