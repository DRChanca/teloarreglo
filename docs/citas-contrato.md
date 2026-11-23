# Solicitudes de cita y propuestas — #20

## Interfaz compartida

Migración nueva: `20261122002000_appointments.sql` (bloque reservado de #20).
No modifica migraciones anteriores ni carga, borra o reinicia datos preparados.
No se ha desplegado ni escrito en la base alojada.

Tablas (todas con RLS, sin INSERT/UPDATE/DELETE para las cuentas de la app):

- `public.appointments`: `id`, `conversation_id`, `profile_id`, `client_id`,
  `professional_id`, `request_id`, `problem`, `requested_start`,
  `requested_end` (intervalo `[inicio, fin)` en UTC), `address`, `latitude`,
  `longitude`, `version` (token de concurrencia), `stage`, `created_at`.
  Lectura solo para cliente y profesional de la cita.
- `public.appointment_proposals`: `id`, `appointment_id`, `author_id`,
  `version` (1, 2, 3… por cita), `request_id`, `scope`, `start_at`, `end_at`,
  `duration_minutes`, `amount_cents`, `visit_fee_cents` (null = sin coste de
  visita aparte), `materials_included`, `travel_included`, `status`,
  `created_at`, `decided_at`. Lectura para los participantes de su cita.
  Como máximo una propuesta `pending` por cita (índice único parcial).
- `public.appointment_events`: avisos persistidos `requested`, `proposed`,
  `rejected` con `actor_id`, `recipient_id`, `proposal_id` y hora de servidor.
  Lectura para actor y destinatario. Pensado para que #32 entregue push a
  partir de eventos ya confirmados; no es fuente de verdad del estado.

Restricciones con nombre para que otros tickets las amplíen con
`alter table … drop constraint … add constraint …` en una migración nueva:
`appointments_stage_check` (hoy solo `pending`),
`appointment_proposals_status_check` (`pending`, `rejected`, `superseded`) y
`appointment_events_kind_check`. Aceptar (#21/#22) añadirá `accepted` y las
etapas confirmadas. Nada de #20 reserva tiempo: no hay restricción de solape
sobre solicitudes ni propuestas; la reserva y su exclusión pertenecen a #21.

### RPC (todas `security definer`, actor derivado de `auth.uid()`, sin acceso anónimo)

- `request_appointment(p_conversation_id uuid, p_request_id uuid, p_problem text,
  p_start timestamptz, p_end timestamptz, p_address text, p_lat double precision,
  p_lng double precision)` → fila de `appointments`.
  Solo el cliente de la conversación. Sin precio. Inicio futuro según la hora del
  servidor, fin posterior y como mucho 24 h. El punto debe estar en un concejo
  (o a ≤ 250 m) cubierto por la versión publicada del perfil. Mismo
  `p_request_id` y datos → misma cita; datos distintos → `retry_conflict`.
- `propose_appointment(p_appointment_id uuid, p_expected_version integer,
  p_request_id uuid, p_scope text, p_start timestamptz, p_duration_minutes integer,
  p_amount_cents integer, p_visit_fee_cents integer, p_materials_included boolean,
  p_travel_included boolean)` → fila de `appointment_proposals`.
  Solo el profesional de la cita. Importe 0–1 000 000 € en céntimos
  (0 = visita gratuita), duración 15–1440 min, inicio futuro. Requiere
  `p_expected_version = appointments.version`; si no coincide → `stale_version`.
  Una versión nueva sustituye (`superseded`) a la pendiente; no confirma ni
  reserva. Repetir la misma clave y contenido devuelve la misma propuesta,
  aunque la cita ya haya avanzado.
- `reject_proposal(p_proposal_id uuid)` → propuesta `rejected`.
  Solo el cliente. Idempotente; rechazar una versión sustituida → `stale_version`.
  La cita sigue `pending` y sin reserva; el historial se conserva.

Cada operación con efecto incrementa `appointments.version` y escribe un evento.

### Errores estables

| HTTP | SQLSTATE | `message` | Android (`AppointmentFailure`) |
| --- | --- | --- | --- |
| 401 | 28000 | `authentication_required` | `ACCESS` |
| 403 | 42501 | `permission_denied` (cita o conversación ajena, rol equivocado) | `ACCESS` |
| 400 | 22023 | `invalid_appointment` / `invalid_proposal` | `INVALID` |
| 400 | 22023 | `out_of_coverage` | `OUT_OF_COVERAGE` |
| 400 | 22023 | `retry_conflict` | `RETRY_CONFLICT` |
| 409 | PT409 | `stale_version` / `incompatible_state` | `STALE` |

`PT409` hace que PostgREST responda 409; `40001` produciría un 500.
Los fallos de red se propagan sin traducir: la app conserva la clave y no
muestra la operación como enviada.

### Lectura desde Android

`GET appointments?select=*,appointment_proposals(*)&conversation_id=eq.UUID&order=created_at.desc`.
La frontera Kotlin es `es.teloarreglo.appointments.Appointments`
(`list`, `request`, `propose`, `reject`), con `Appointment.currentProposal` y
`Appointment.notice(accountId)` para el estado que corresponde a cada rol.
`parseEuroCents` convierte la entrada del formulario en céntimos sin coma flotante.

## Privacidad de la dirección

La dirección exacta y las coordenadas solo existen en `appointments`, creada al
solicitar. `conversations`, `approved_profiles`, la búsqueda y la Edge Function
no la contienen ni la consultan. El rol administrativo no tiene excepción: la
revisión de incidencias (#26/#30) deberá añadir su propia frontera explícita.

## Interfaz Android

`AppointmentsPanel` se inserta en la conversación abierta mediante el parámetro
opcional `appointmentsSlot` de `ChatRoute`/`ChatScreen`. Muestra el aviso vigente
y abre un diálogo a pantalla completa con:

- cliente: solicitar (problema, fecha, franja, dirección, coordenadas por GPS
  puntual o escritas a mano; sin precio) y rechazar la versión vigente con
  confirmación;
- profesional: enviar propuesta o nueva versión (alcance, fecha, hora,
  duración, importe, coste de visita opcional, materiales y desplazamiento);
- ambos: historial de versiones con «Vigente · pendiente», «Rechazada» o
  «Sustituida» y el texto fijo «Pendiente: no está confirmada ni reserva la hora».

Consulta el servidor cada 2,5 s mientras está visible, como el chat. Una
respuesta perdida conserva la clave en `rememberSaveable` y bloquea el
formulario hasta reintentar o encontrar esa clave en el historial. A diferencia
del chat, esta clave no se cifra en disco: si el sistema mata el proceso
durante un envío incierto, el usuario verá el historial real del servidor al
volver y no se duplicará nada ya registrado. El selector de punto en mapa queda
para #17; hoy la alternativa al GPS es escribir las coordenadas.

## Comprobación reproducible

```bash
supabase db reset --local && supabase test db
python3 scripts/contract_appointments.py --local
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE   # incluye AppointmentsUiTest
```

Resultados: [comprobación](comprobacion-citas.md).
