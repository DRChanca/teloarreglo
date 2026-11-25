# Avisos y notificaciones push — #32

Los avisos persistidos son la fuente de verdad; FCM solo los anuncia. Un aviso se escribe en la misma transacción
que la operación de dominio confirmada. El envío push ocurre después y por separado. Un permiso denegado, un token
inválido o un fallo de FCM no revierte una cita ni un pago y no borra el aviso de la app.

## Interfaz compartida

Migraciones:

- `20261123013200_notices.sql`: productores, lectura y marca de leído.
- `20261123013210_push_delivery.sql`: dispositivos y cola de envío.

### Productores (sin llamadas explícitas)

`private.notices` (creada en #29) es el almacén único por cuenta. La alimentan disparadores:

| Origen | `kind` | Destinatario | `payload` |
| --- | --- | --- | --- |
| `public.chat_messages` (texto, foto o vídeo) | `message` | el otro participante | `conversation_id` |
| `public.appointment_events` de **cualquier** tipo | `appointment` | `recipient_id` del evento | `appointment_id`, `conversation_id`, `event` (= tipo del evento) |
| Decisión sobre un perfil (#29, ya existente) | `profile_review` | propietario | `status`, `reason`, … |
| Rutina `private.run_call_notices()` (#77, «Aviso para llamar») | `call_client` | profesional de la cita | `appointment_id`, `conversation_id` |

Un productor nuevo solo inserta su evento en `appointment_events` con un tipo propio. Por ejemplo, `incident_opened`
o `incident_evidence` de #26, o `reminder` del reloj de #27. Ese evento aparece como aviso y como push sin tocar
esta capa. Android traduce los tipos conocidos. Los que empiezan por `incident` se muestran como «Novedad en una
incidencia» y los desconocidos como «Novedad en una cita».

Los avisos no llevan cuerpos de mensaje, direcciones, coordenadas ni nombres. Los eventos de cita anteriores a la
migración se copian como historial ya leído y no generan push.

### RPC para cuentas (`authenticated`)

| RPC | Resultado |
| --- | --- |
| `my_notices()` | Hasta 100 avisos propios, del más reciente al más antiguo: `id, kind, payload, created_at, read, count`. Los mensajes de una conversación se agrupan en su aviso más reciente, con `count` = número de mensajes sin leer. Un grupo queda leído al abrir la conversación (`mark_conversation_read`, #39) o al marcar el aviso. Forma compatible con #29. |
| `mark_notices_read(p_ids uuid[])` | Marca solo avisos propios; ignora los demás identificadores. Marcar el aviso de un grupo marca también los mensajes anteriores de ese grupo. |
| `register_push_device(p_installation_id uuid, p_token text, p_enabled boolean)` | `{installation_id, enabled}`. La instalación pasa a la cuenta que llama. Si el token estaba en otra instalación, se mueve. `enabled=false` corresponde a permiso Android denegado o a notificaciones desactivadas en la app. Error `22023 invalid_token`. |
| `unregister_push_device(p_installation_id uuid)` | Se llama antes de cerrar sesión. Solo la cuenta propietaria puede borrar el registro. |

### RPC del despachador (solo `service_role`)

| RPC | Comportamiento |
| --- | --- |
| `claim_push_deliveries(p_limit)` | Hace caducar (`expired`) las entregas de más de 1 día. Descarta (`dropped`) las de dispositivos que ya no son del destinatario o que están desactivados o inválidos. Reserva las vencidas durante 2 min con `for update skip locked`. Devuelve `delivery_id, token, recipient_id, notice_id, kind, event, conversation_id, appointment_id, created_at`. |
| `report_push_delivery(p_delivery_id, p_outcome, p_error)` | `sent` es definitivo. `retry` reintenta con espera exponencial (2^intentos min) y pasa a `failed` tras 5 intentos. `invalid_token` desactiva el dispositivo y descarta sus pendientes. `failed` es definitivo. Solo cambia una entrega en curso, así que un informe tardío o repetido se ignora. |

Tablas privadas sin acceso de cliente: `private.push_devices` (una fila por instalación, `token` único) y
`private.push_deliveries` (única por aviso e instalación). Un reintento reutiliza la misma fila y nunca repite la
operación de dominio.

### Edge Function `deliver-push`

- `POST /functions/v1/deliver-push` con `Authorization: Bearer <PUSH_DISPATCH_SECRET>` (`verify_jwt = false`).
- Llama a `claim_push_deliveries`, envía con FCM HTTP v1 y llama a `report_push_delivery`.
- El mensaje es solo de datos, de prioridad alta y TTL de 1 día. La app escribe el texto en ES/EN/FR/DE.
- Clasificación de respuestas de FCM:
  - 404/`UNREGISTERED` → `invalid_token`.
  - 400 por token → `invalid_token`.
  - 429, 5xx, 401 y 403 → `retry`.
  - Otros 4xx → `failed`.
- Sin `FCM_SERVICE_ACCOUNT` responde `{configured:false}` y no reserva nada. Los avisos siguen en la app y sus
  entregas caducan.

Secretos, solo en Supabase y nunca en Git ni en la APK:

- `FCM_SERVICE_ACCOUNT`: JSON de una cuenta de servicio con permiso de envío FCM.
- `PUSH_DISPATCH_SECRET`: valor aleatorio largo.

Programación cada minuto con Supabase Cron. Es un paso de David y no está en migraciones porque depende del
proyecto:

```sql
select vault.create_secret('<PUSH_DISPATCH_SECRET>', 'push_dispatch_secret');
select cron.schedule('deliver-push', '* * * * *', $$
  select net.http_post(
    url := 'https://<ref>.supabase.co/functions/v1/deliver-push',
    headers := jsonb_build_object('Authorization', 'Bearer ' ||
      (select decrypted_secret from vault.decrypted_secrets where name = 'push_dispatch_secret')),
    body := '{}'::jsonb)
$$);
```

### Android

- Interfaces en `notices/Notices.kt`:
  - `Notices`: `list()` y `markRead()`.
  - `PushDevices`: `register()` y `unregister()`.
  - `topicOf(kind, event)`: tema independiente del idioma.
- `notices/PushRegistration.kt`:
  - Registra la instalación para la cuenta conectada.
  - Expone el estado: `ACTIVE`, `OFF`, `PERMISSION_NEEDED`, `NOT_CONFIGURED`, `FAILED`.
  - Desregistra antes de cerrar sesión.
  - `accepts(recipient)` descarta un push dirigido a otra cuenta, también sin conexión.
- `notices/AndroidPush.kt`:
  - Firebase se inicializa con identificadores públicos de cliente, sin `google-services.json`:
    - `FCM_PROJECT_ID`, `FCM_API_KEY` y `FCM_SENDER_ID`.
    - `PRESENTATION_FCM_APP_ID`. Solo se usa la variante `es.teloarreglo.presentacion`
      (decisión del 23 de noviembre en `especificacion.md`); `TEST_FCM_APP_ID` puede quedar vacío.
  - Esos valores se leen de `.env`. Si falta alguno, no hay push.
  - `TeloMessagingService` muestra la notificación con etiqueta por conversación o aviso, así que un push
    repetido sustituye al anterior.
- `NoticesScreen.kt` contiene la lista persistida y los ajustes de notificación del dispositivo. Se abre con
  «Avisos (n)» en el catálogo o al tocar una notificación.

## Aviso para llamar — #77

Migración `20261126071121_call_notices.sql`. Una hora antes del inicio de cada cita confirmada, el profesional recibe
un aviso `call_client` para llamar al cliente. Llega como push aunque la app esté cerrada y aparece en Avisos.

- `private.run_call_notices(p_limit integer default 200)` devuelve `{created}`. Crea el aviso para cada cita en
  `confirmed` cuyo inicio vigente (`lower(during)` de su reserva en `agenda_reservations`, que mueve un cambio de
  hora aceptado) cae en `(now(), now() + 60 min]`. Una cita confirmada con menos margen lo recibe en la siguiente
  ejecución. Las citas pendientes, comenzadas, realizadas, finalizadas o canceladas no lo reciben.
- Una sola vez por cita: el propio aviso es la marca (único por `kind`, `source_id` = cita, destinatario).
- El aviso se inserta sin leer, así que el reparto push existente crea sus entregas. El `payload` lleva solo
  identificadores; nunca el teléfono del cliente, que la acción «Llamar» (#81) pide en ese momento a la función
  protegida (#79).
- Solo la ejecutan `service_role` y el cron (rol `postgres`); `authenticated` y `anon` no.
- Es independiente del reloj de cierre (#27, `private.run_completion_clock()`) y de «Ventana de llegada» (#72).
- Android: tema `CALL_CLIENT` («Tu cita empieza pronto: llama al cliente»). En Avisos lleva a la conversación de la
  cita, donde se muestra. Al tocar la notificación se abre directamente esa conversación (`pushDestination`) y el
  aviso queda leído; el resto de notificaciones siguen abriendo Avisos.

Programación cada minuto con Supabase Cron, paso manual por proyecto (como el reloj de #27):

```sql
create extension if not exists pg_cron;   -- o activar «Cron» en Integrations
select cron.schedule('call-notices', '* * * * *', 'select private.run_call_notices()');
-- Comprobación:
select jobid, schedule, active from cron.job where jobname = 'call-notices';
select status, return_message, start_time from cron.job_run_details
 where jobid = (select jobid from cron.job where jobname = 'call-notices') order by start_time desc limit 5;
-- Avisos creados:
select created_at, recipient_id, payload from private.notices where kind = 'call_client' order by created_at desc limit 20;
```

## Envío inmediato y primer plano

Migración `20261123013310_push_dispatch_now.sql`. Antes, las entregas solo salían con el cron de cada minuto. Ahora
cada sentencia que encola entregas en `private.push_deliveries` llama al momento a `deliver-push` con
`net.http_post`. La petición sale cuando se confirma la transacción. El cron sigue como respaldo.

- La URL se lee del secreto de Vault `push_dispatch_url`. El portador sale de `push_dispatch_secret`, el mismo que
  usa el cron.
- Sin `push_dispatch_url` no se llama a nada. Es el caso de las bases locales y de prueba.
- Cualquier error (sin `pg_net`, Vault no disponible) se ignora. Despertar al despachador nunca revierte un mensaje,
  una cita ni un pago.
- Llamadas simultáneas del disparador y del cron no duplican envíos: `claim_push_deliveries` reserva con
  `for update skip locked`.

Paso único en el proyecto alojado, pendiente de aprobación de David. `push_dispatch_secret` ya existe por el cron:

```sql
select vault.create_secret('https://<ref>.supabase.co/functions/v1/deliver-push', 'push_dispatch_url');
```

Para desactivarlo, se borra ese secreto (`delete from vault.secrets where name = 'push_dispatch_url'`).

En Android, `TeloMessagingService`:

- Si la conversación del push está abierta en pantalla (`OpenConversation`), no muestra notificación y refresca
  el chat al momento.
- En cualquier otro caso muestra la notificación, también con la app en primer plano.
- Con un push de mensaje marca la conversación como entregada (`mark_conversation_delivered`), así quien escribe
  ve el doble check gris.
