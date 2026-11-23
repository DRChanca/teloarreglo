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

`GET appointments?select=<columnas legibles>,appointment_proposals(*)&conversation_id=eq.UUID&order=created_at.desc`
y después `rpc/appointment_places` para el lugar. Desde #38, `select=*` responde 403 porque `address`,
`latitude` y `longitude` no son columnas legibles (ver «Zona aproximada — #38»).
La frontera Kotlin es `es.teloarreglo.appointments.Appointments`
(`list`, `request`, `propose`, `reject`), con `Appointment.currentProposal` y
`Appointment.notice(accountId)` para el estado que corresponde a cada rol.
`parseEuroCents` convierte la entrada del formulario en céntimos sin coma flotante.

## Privacidad de la dirección

La dirección exacta y las coordenadas solo existen en `appointments`, creada al
solicitar, y desde #38 solo se leen con `appointment_places` (abajo). `conversations`, `approved_profiles`, la búsqueda y la Edge Function
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
volver y no se duplicará nada ya registrado. El selector de punto en mapa llegó con #17
(«Elegir el punto en el mapa» cuando hay mapa; sin mapa, coordenadas escritas). Ver
[mapa-contrato.md](mapa-contrato.md).

## Comprobación reproducible

```bash
supabase db reset --local && supabase test db
python3 scripts/contract_appointments.py --local
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE   # incluye AppointmentsUiTest
```

Resultados: [comprobación](comprobacion-citas.md).

## Agenda y confirmación gratuita — #21

Migración nueva: `20261123002100_agenda_confirmation.sql`. Amplía por nombre
`appointments_stage_check` (+`confirmed`), `appointment_proposals_status_check`
(+`accepted`) y `appointment_events_kind_check` (+`accepted`). Activa `btree_gist`.

Tablas (RLS, lectura solo para el profesional dueño, sin escritura directa):

- `public.agenda_settings`: una agenda por perfil; `default_duration_minutes`
  (15–1440) y `timezone` fijo `Europe/Madrid`.
- `public.availability_windows`: franjas semanales `weekday` ISO 1–7,
  `start_time` < `end_time` (hora local de Madrid, como mucho hasta 23:59).
- `public.agenda_reservations`: todo lo que ocupa la agenda, `kind`
  `appointment` (con `appointment_id` único y `proposal_id`) o `block` (con
  `request_id`). `during tstzrange` semiabierto `[inicio, fin)` y restricción
  `agenda_reservations_no_overlap exclude using gist (profile_id with =, during with &&)`:
  citas confirmadas y bloqueos no se solapan entre sí; contiguos sí se tocan.
  Pago integrado (#22) y cambios (#23) deben reservar por esta tabla.

RPC (`security definer`, actor de `auth.uid()`, sin acceso anónimo):

- `my_agenda()` → `{profile_id, timezone, default_duration_minutes, windows[{weekday,start,end}],
  blocks[{id,start_at,end_at,reason}], bookings[{appointment_id,start_at,end_at}]}` (bloqueos y
  citas futuros). Sin perfil profesional → `42501 permission_denied`.
- `save_agenda(p_default_duration_minutes integer, p_windows jsonb)` → igual que `my_agenda`.
  Sustituye todo el horario semanal (reintento idempotente). Errores `22023 invalid_agenda`.
  Las citas ya confirmadas conservan su reserva aunque cambie el horario.
- `add_agenda_block(p_request_id uuid, p_start, p_end, p_reason text)` → fila de reserva.
  Máximo 31 días; misma clave y datos → mismo bloqueo; otros datos → `retry_conflict`;
  solape → `PT409 slot_unavailable`; datos no válidos → `22023 invalid_block`.
- `remove_agenda_block(p_block_id uuid)` → `{id, removed: true}`. Idempotente; solo
  borra bloqueos propios.
- `accept_proposal(p_proposal_id uuid)` → propuesta `accepted`. Solo el cliente de la
  cita. Requiere propuesta vigente (`pending`; si no, `PT409 stale_version`), cita
  `pending` (`incompatible_state`), importe 0 y sin coste de visita (`22023 payment_required`),
  inicio futuro (`PT409 proposal_expired`) e intervalo dentro de una franja de
  disponibilidad y sin solapes (`PT409 slot_unavailable`). Bloquea la cita
  (`for update`) y después la agenda del perfil (`private.lock_agenda`, bloqueo consultivo
  de transacción que también toman `save_agenda` y los bloqueos); propuesta, etapa
  `confirmed`, `version + 1`, reserva y evento `accepted` al profesional se aplican
  juntos o nada. Aceptar de nuevo devuelve el mismo resultado sin duplicar avisos.
  Las demás solicitudes siguen `pending` y pueden recibir otra versión.

Android (`AppointmentFailure`): `slot_unavailable` → `SLOT_UNAVAILABLE`;
`proposal_expired` → `STALE`; `payment_required` → `INVALID` (la app no ofrece aceptar
propuestas con coste). Frontera nueva `es.teloarreglo.appointments.Agenda`
(`load`, `save`, `addBlock`, `removeBlock`) con `AgendaFailure`
(`ACCESS`, `INVALID`, `SLOT_UNAVAILABLE`, `RETRY_CONFLICT`).

Interfaz: `AgendaCard` en la pantalla de cuenta del profesional (duración habitual,
una franja por día de la semana, bloqueos con clave de reintento y citas confirmadas).
En `AppointmentsPanel` el cliente ve «Aceptar y confirmar la visita» solo en propuestas
gratuitas, con diálogo que explica que se reservará el horario; ambas cuentas ven
«Cita confirmada» y «Confirmada: fecha, min. El horario está reservado.».

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> python3 scripts/contract_agenda.py --local
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AgendaTest'
```

Resultados: [comprobación de agenda](comprobacion-agenda.md).

## Zona aproximada — #38

Migración nueva: `20261123003800_approximate_appointment_area.sql`.
No modifica migraciones anteriores ni carga, borra o reinicia datos preparados.
No se ha desplegado ni escrito en la base alojada.

Regla: mientras la cita no está confirmada, el profesional solo conoce un círculo de 400 m y el concejo.
La dirección y las coordenadas exactas se le entregan cuando la etapa pasa a `confirmed`.

Columnas nuevas de `public.appointments` (legibles por los dos participantes):

| Columna | Notas |
| --- | --- |
| `area_lat`, `area_lng` | Centro desplazado al azar, uniforme en un disco de 300 m alrededor de `latitude/longitude` (`private.approximate_center`, `st_project` sobre el esferoide). La casa queda siempre dentro del círculo |
| `area_radius_m` | Siempre 400 |
| `area_ine_code` | Concejo del punto: el cubierto por el perfil que admitió la solicitud o, si no, el más cercano a ≤ 250 m |
| `location_shared_at` | Primera vez que la etapa fue `confirmed`. No se borra en etapas posteriores |

El disparador `appointments_area` (`before insert or update`) calcula el centro una sola vez al crear la
fila. Lo conserva en cualquier actualización, aunque se intente sobrescribir, y solo lo recalcula si
cambian `latitude/longitude`, por ejemplo con un cambio de dirección aceptado (#23). También fija
`location_shared_at` con la primera confirmación. Una confirmación pagada (#22) que ponga
`stage = 'confirmed'` revela el lugar sin más cambios. Una etapa previa nueva, como «pendiente de
pago», no lo revela.

Privilegios de columna: `authenticated` ya no tiene `select` sobre la tabla entera, sino sobre todas
las columnas salvo `address`, `latitude` y `longitude`. La política `participants_read` no cambia.
Por eso `select *`, `to_jsonb(fila)` y los filtros sobre esas columnas responden `42501` (HTTP 403)
para cualquier cuenta, también para el cliente y también tras confirmar. Las funciones
`security definer` (#20–#25) siguen leyéndolas sin cambios.

- `appointment_places(p_conversation_id uuid)` → filas `appointment_id, exact, address, latitude,
  longitude, area_lat, area_lng, area_radius_m, ine_code, concejo_name`, de las citas de esa
  conversación en las que participa quien llama (las más recientes primero). `exact` es verdadero para
  el cliente y, con `location_shared_at`, para el profesional. Si es falso, `address`, `latitude` y
  `longitude` son `null`. Una tercera cuenta recibe 0 filas; `anon` no puede llamarla (`42501`).

Vías revisadas (pgTAP `appointment_area.test.sql`): tabla `appointments` (columnas, `*`, fila
completa, filtros), `request_appointment` (solo el cliente), `propose_appointment`,
`appointment_proposals`, `appointment_events` (incluido el aviso `accepted`), `conversations`,
`chat_messages`, `conversation_inbox()`, `my_notices()`, `my_agenda()`, `agenda_reservations` y
`home_addresses`. Ninguna contiene la dirección ni las coordenadas exactas para el profesional antes
de confirmar.

Para otros tickets: cualquier vista, RPC o evento nuevo que devuelva datos de la cita al profesional
no debe incluir `address/latitude/longitude` si `location_shared_at` es nulo. Puede usar
`appointment_places` o las columnas `area_*`. La revisión administrativa de incidencias (#26/#30)
sigue sin excepción y debe añadir su propia frontera.

Android: `Appointments.list` pide las columnas legibles y después `appointment_places`, y rellena
`Appointment.place` (`AppointmentPlace`) y `Appointment.location`:
`AppointmentLocation.Exact(address, point)` o `Approximate(center, radiusMeters, concejoName)`.
`AppointmentLocationBlock` muestra:

- si es exacta, la dirección, las coordenadas y «Cómo llegar». El cliente, mientras la cita está
  pendiente, ve además «Hasta confirmar la cita, el profesional solo ve una zona aproximada de 400 m».
- si es aproximada, «Zona aproximada de 400 m en <concejo>», el círculo en Google Maps
  (`ApproximateAreaMap`, sin marcador) cuando hay mapa, y «La dirección exacta se mostrará cuando se
  confirme la cita». No ofrece «Cómo llegar».

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_appointments.py --local && python3 contract_agenda.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
```

Resultados: [comprobación de la zona aproximada](comprobacion-zona-aproximada.md).

## Confirmación con pago simulado o pago directo — #22

Migración nueva: `20261123002200_paid_confirmation.sql`. El pago es **simulado**: no hay pasarela,
proveedor, tarjeta, banco ni dinero real. No se ha desplegado ni escrito en la base alojada.

Tablas (RLS, lectura para cliente y profesional de la cita, sin escritura directa):

- `public.appointment_payments`: una fila por cita confirmada con coste. `appointment_id` (PK),
  `proposal_id`, `client_id`, `professional_id`, `request_id` (clave de reintento, única por cliente),
  `mode` (`integrated` | `direct`), y en céntimos `agreed_cents`, `paid_cents`, `pending_cents`,
  `refunded_cents`, `delivered_cents`; `simulated` siempre `true`; `created_at`. Restricciones:
  `paid = pending + refunded + delivered` (`appointment_payments_balance`) y en `direct` todo el
  dinero integrado es 0 (`appointment_payments_direct_check`). Sin columnas de tarjeta, banco o factura
  (pgTAP `columns_are`).
- `public.payment_movements`: historial solo de inserción. `kind` `paid` | `refunded` | `delivered`
  (`payment_movements_kind_check`), `amount_cents > 0`, `actor_id`, `created_at`. #22 solo escribe
  `paid`; devoluciones (#23/#24/#26) y entregas (#25/#27) deben actualizar el saldo y añadir su
  movimiento en la misma transacción.

RPC (`security definer`, actor de `auth.uid()`, sin acceso anónimo):

- `confirm_proposal(p_proposal_id uuid, p_request_id uuid, p_mode text)` → fila de
  `appointment_payments`. Solo el cliente de la cita, sobre la versión vigente. Toma el bloqueo de la
  cita y después `private.lock_agenda`, como `accept_proposal`. Aceptación (`accepted`), etapa
  `confirmed`, `version + 1`, reserva en `agenda_reservations`, aviso `accepted` al profesional, fila
  de pago y, en `integrated`, el movimiento `paid` se aplican juntos o nada.
  - Importe: `greatest(amount_cents, visit_fee_cents)`, es decir, el precio acordado o el coste de
    visita si solo se propuso ese. `integrated`: `paid = pending = importe`, nada entregado ni devuelto.
    `direct`: se registra `agreed_cents` y ningún movimiento; la app no cobra ni certifica el pago.
  - Idempotencia: la clave identifica una intención (propuesta + modalidad). Misma clave → mismo
    resultado; misma clave con otra propuesta o modalidad → `22023 retry_conflict`. Una clave nueva
    para la misma propuesta ya confirmada con la misma modalidad recupera el resultado original (una
    respuesta perdida nunca crea un segundo pago); con otra modalidad → `PT409 incompatible_state`.
  - Errores: `22023 invalid_payment` (modalidad o clave nulas o desconocidas),
    `22023 payment_not_required` (propuesta gratuita: usar `accept_proposal`),
    `PT409 stale_version`, `incompatible_state`, `proposal_expired`, `slot_unavailable`,
    `42501 permission_denied`. Un conflicto no cobra ni deja efectos parciales.

`accept_proposal` se redefine con el mismo comportamiento; la comprobación de agenda y la reserva
pasan a `private.reserve_proposal(appointments, appointment_proposals)`, que también usa #22 y deben
usar los cambios de #23. Al pasar a `confirmed`, el disparador de #38 comparte la dirección exacta con
el profesional igual que en la confirmación gratuita.

Android: `Appointments.confirm(proposalId, requestId, PaymentMode)` → `AppointmentPayment`.
`list` añade `appointment_payments(*)` y rellena `Appointment.payment`. `AppointmentProposal.payableCents`
da el importe que se paga. `payment_not_required` e `invalid_payment` → `INVALID`.
Interfaz (`AppointmentsPanel`): ante una versión con coste, el cliente ve «Pagar X y confirmar» y
«Pagaré X directamente al profesional». El diálogo muestra alcance, fecha, duración e importe vigentes
y explica la modalidad antes de aceptar. La clave se guarda en `rememberSaveable` y, si la respuesta se
pierde, solo se puede reintentar la misma modalidad; la siguiente consulta que vea la cita confirmada
libera la clave. Ambas cuentas ven el saldo («Pagado · Pendiente de entrega · Devuelto · Entregado») o
«Pago directo acordado». Textos `payment_*` en ES/EN/FR/DE. No hay banner global de demo: la
simulación se explica por voz.

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_payments.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
```

Resultados: [comprobación de pagos](comprobacion-pagos.md).

## Precio guardado en la cita — decisión del 23 de noviembre de 2026

Regla: un precio pactado con un cliente no cambia para ese cliente. Migración nueva
`20261123004300_appointment_kept_prices.sql`; no edita las anteriores ni cambia firmas de RPC.

Revisión del estado anterior (#20, #21, #22): ninguna RPC de citas leía el precio del perfil en vivo.
`request_appointment` no guardaba precio y `propose_appointment` guarda `amount_cents` y
`visit_fee_cents` en la propia propuesta; `accept_proposal` y `confirm_proposal` (#22) solo leen la
propuesta (`greatest(amount_cents, visit_fee_cents)`) y guardan el importe en `appointment_payments`.

- `public.appointments.reference_prices jsonb` (columna nueva, legible por los dos participantes, como
  las demás salvo el lugar exacto): copia de los precios orientativos publicados del perfil **en el
  momento de la solicitud**, la fija el disparador `appointments_kept_prices` al insertar. Las solicitudes
  existentes reciben los precios aprobados actuales: hasta este cambio los propietarios no podían
  editarlos, así que son los vigentes al solicitar. `request_appointment` la devuelve en su JSON.
- Cambiarla después falla siempre, incluso con SQL de confianza: `42501 price_locked`.
- Una propuesta decidida (`accepted`, `rejected`, `superseded`) no puede cambiar `amount_cents` ni
  `visit_fee_cents` (`42501 price_locked`). Ninguna RPC reescribe importes: un cambio de precio es una
  propuesta nueva con aceptación expresa (#23).
- `save_professional_offer` y las versiones aprobadas solo tocan el perfil: nunca citas, solicitudes ni
  propuestas.

**Para #22 (pago simulado) y siguientes:** el cobro usa el importe guardado en la propuesta aceptada
(`appointment_proposals.amount_cents` y `visit_fee_cents`, copiado a `appointment_payments.agreed_cents`),
nunca `approved_profiles.prices`. Así lo hace ya `confirm_proposal`; #23–#27 deben seguir igual. Cuando el
cliente pide sin propuesta, la referencia es `appointments.reference_prices`. Un movimiento simulado no
debe leer el precio del perfil.

Android no muestra todavía `reference_prices` (la consulta de `Appointments.list` no la pide).

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree>   # incluye appointment_prices.test.sql
```


## Cambios de condiciones — #23

Migración nueva: `20261123012300_appointment_changes.sql`. No edita migraciones anteriores ni cambia firmas
de RPC existentes. No se ha desplegado ni escrito en la base alojada. El dinero sigue siendo simulado.

Regla: una cita confirmada solo cambia cuando la otra parte acepta expresamente una versión concreta. Mientras
el cambio está pendiente, o si se rechaza o falla, siguen vigentes la propuesta aceptada, la reserva de agenda,
la dirección y el pago. El profesional nunca sustituye la dirección del cliente; solo puede pedir que la corrija.

Cambios en el esquema:

- `appointment_proposals_status_check` + `replaced`: el acuerdo que estaba vigente hasta que un cambio aceptado
  lo sustituyó. Sigue habiendo como máximo una propuesta `accepted` por cita: la de las condiciones vigentes.
- `public.appointment_changes` (RLS: lectura para cliente y profesional de citas ya confirmadas, sin escritura
  directa). `kind` `change` (condiciones nuevas completas: `scope`, `start_at`, `end_at`, `duration_minutes`,
  `amount_cents`, `visit_fee_cents`, `materials_included`, `travel_included`; `address/latitude/longitude` solo
  si el cliente cambia la dirección, con `previous_*` para el historial) o `address_correction` (solo `note`).
  `version` 1, 2, 3… por cita, `request_id` único por autor, `base_proposal_id` (condiciones que modifica),
  `payment_mode` (si una cita gratuita pasa a tener coste), `status` `pending | accepted | rejected | superseded`,
  `result_proposal_id`. Como máximo un cambio `pending` por cita (índice parcial).
- `appointment_events` + columna `change_id` y tipos `change_proposed`, `change_accepted`, `change_rejected`,
  `address_correction_requested`.
- `payment_movements` + columna `change_id` única: como mucho un movimiento por cambio.
- `appointment_payments.agreed_cents` admite 0 (una reducción puede devolverlo todo).

RPC (`security definer`, actor de `auth.uid()`, sin acceso anónimo):

- `propose_change(p_appointment_id, p_expected_version, p_request_id, p_scope, p_start, p_duration_minutes,
  p_amount_cents, p_visit_fee_cents, p_materials_included, p_travel_included, p_address = null, p_lat = null,
  p_lng = null, p_note = null, p_mode = null)` → fila de `appointment_changes`. Cualquiera de los dos
  participantes, solo con la cita `confirmed` (`PT409 incompatible_state`) y `p_expected_version` vigente
  (`PT409 stale_version`). Dirección solo del cliente (`42501 address_owner_only`) y dentro de la cobertura
  (`22023 out_of_coverage`). Condiciones idénticas → `22023 no_change`; datos no válidos o nueva hora pasada →
  `22023 invalid_change`. Si la cita no tenía pago y el cliente añade coste, `p_mode` es obligatorio
  (`22023 invalid_payment`). Misma clave y contenido → mismo cambio; otro contenido → `retry_conflict`.
  Sustituye (`superseded`) el cambio pendiente; no toca nada vigente.
- `request_address_correction(p_appointment_id, p_expected_version, p_request_id, p_note)` → cambio
  `address_correction`. Solo el profesional. El cliente responde proponiendo un cambio con dirección (que la
  sustituye) o la descarta con `reject_change`.
- `accept_change(p_change_id, p_mode = null)` → cambio `accepted`. Solo la otra parte (el autor recibe
  `42501`). Toma el bloqueo de la cita y después `private.lock_agenda`, como `accept_proposal`. Versión no
  vigente → `PT409 stale_version`; petición de corrección → `22023 invalid_change`. Si cambia el intervalo:
  nueva hora futura (`PT409 proposal_expired`), dentro de una franja y sin solapes (`PT409 slot_unavailable`).
  Si cambia la dirección: cobertura (`22023 out_of_coverage`). Después, en la misma transacción: propuesta
  nueva `accepted` con las condiciones del cambio (la anterior pasa a `replaced`), reserva movida, dirección
  actualizada (el disparador de #38 recalcula la zona aproximada), pago y aviso `change_accepted`. Cualquier
  fallo deja la cita anterior íntegra y el cambio `pending`. Aceptar otra vez devuelve el mismo resultado.
  - Integrado: aumento → movimiento `paid` por la diferencia (`paid` y `pending` suben) antes del trabajo
    adicional; reducción → movimiento `refunded` (`pending` baja, `refunded` sube). Una reducción mayor que lo
    pendiente de entrega → `PT409 incompatible_state`.
  - Directo: solo cambia `agreed_cents`; ningún movimiento.
  - Cita gratuita que pasa a tener coste: se crea el pago con el modo del cliente (`payment_mode` de su
    cambio o `p_mode` al aceptar el del profesional; sin modo → `22023 invalid_payment`).
- `reject_change(p_change_id)` → cambio `rejected`. Solo la otra parte. Idempotente; versión no vigente →
  `stale_version`. Conserva historial y condiciones.

Android: `Appointments.proposeChange`, `requestAddressCorrection`, `acceptChange(changeId, mode?)`,
`rejectChange`; `list` añade `appointment_changes(*)` y rellena `Appointment.changes` y `pendingChange`.
`Appointment.changeEffect(change)` → `ChangeEffect` (`PayDifference`, `Refund`, `DirectAgreement`,
`ChooseMode`, `NewPayment`, `None`) para explicar el dinero antes de decidir. Avisos nuevos
`DECIDE_CHANGE`, `AWAITING_CHANGE`, `CORRECT_ADDRESS`. `no_change` e `invalid_change` → `INVALID`;
`address_owner_only` (403) → `ACCESS`.

Interfaz (`AppointmentChangesUi.kt`, dentro de la tarjeta de una cita confirmada): «Proponer un cambio» abre un
formulario con las condiciones vigentes; el cliente puede marcar «Cambiar la dirección» y, si añade coste a una
cita gratuita, elegir cómo pagará. El profesional tiene además «Pedir al cliente que corrija la dirección». El
cambio pendiente muestra solo los campos que cambian («Antes: … → Propuesto: …»), el efecto en el dinero y que
lo acordado sigue vigente; la otra parte acepta o rechaza con confirmación. Historial «Cambio N · estado».
Textos `change_*` en ES/EN/FR/DE.

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_changes.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
```

Resultados: [comprobación de cambios](comprobacion-cambios.md).

## Comenzar trabajo y cancelar antes del inicio — #24

Migración nueva: `20261123012400_appointment_start_cancel.sql`. No edita migraciones anteriores ni cambia firmas
de RPC existentes. No se ha desplegado ni escrito en la base alojada. El dinero sigue siendo simulado.

Regla: «Comenzar trabajo» separa una cita confirmada de un trabajo iniciado. Antes del inicio cualquiera de las dos
partes cancela sin penalización; después no hay cancelación con devolución automática y un problema se tramita
como incidencia (#26), sin inventar resolución.

Cambios en el esquema:

- `appointments_stage_check` + `started` y `cancelled`. Columnas nuevas legibles por los dos participantes (privilegio
  de columna, como #38): `started_at`, `cancelled_at`, `cancelled_by` (hora y cuenta de servidor).
- `appointment_events_kind_check` + `started` (al cliente) y `cancelled` (a la otra parte).
- `payment_movements.cancellation boolean`: marca la devolución de una cancelación; índice único parcial
  `payment_movements_one_cancellation`, como mucho una por cita.

RPC (`security definer`, actor de `auth.uid()`, sin acceso anónimo). Ambas devuelven
`{id, stage, version, started_at, cancelled_at, cancelled_by, refunded_cents}` (nunca el lugar exacto):

- `start_work(p_appointment_id uuid)`. Solo el profesional de la cita (`42501 permission_denied`, también para citas
  ajenas o inexistentes) y solo con la cita `confirmed` (`PT409 incompatible_state`). Pone `started`, `started_at`,
  `version + 1`, cierra como `superseded` un cambio pendiente (comenzar no acepta costes nuevos) y avisa al cliente.
  No mueve dinero. Repetirla devuelve la misma marca. Tras el inicio, los cambios de #23 responden
  `incompatible_state`.
- `cancel_appointment(p_appointment_id uuid)`. Cliente o profesional, cita `confirmed`. Toma el bloqueo de la cita y
  después `private.lock_agenda`, como `accept_proposal`. En una sola transacción: borra la reserva de
  `agenda_reservations` (la hora vuelve a estar libre), cierra un cambio pendiente, y si el pago es integrado con
  pendiente > 0 añade un movimiento `refunded` con `cancellation = true` por todo lo pendiente y deja `pending = 0`.
  Pago directo: no se toca (`agreed_cents` se conserva, ningún movimiento). Gratuita: no se crea pago. Pone
  `cancelled`, `cancelled_at`, `cancelled_by`, `version + 1` y avisa a la otra parte. Repetirla (por cualquiera de
  las dos partes) devuelve la primera cancelación sin otra devolución ni otro aviso. Errores:
  `PT409 already_started` si ya comenzó, `PT409 incompatible_state` si no está confirmada.

Carrera inicio/cancelación: las dos operaciones bloquean la fila de la cita, así que gana la primera y la otra
recibe 409 (`already_started` o `incompatible_state`) sin efectos parciales.

Android: `Appointments.startWork(appointmentId)` y `cancel(appointmentId)` → `AppointmentMarks`. `AppointmentStage`
+ `STARTED`, `CANCELLED`; `Appointment.startedAt/cancelledAt/cancelledBy`, `canStart(account)`, `canCancel(account)`
y `cancellationEffect` (`CancellationEffect.Refund`, `DirectNotRefunded`, `None`). Avisos `STARTED` y `CANCELLED`.
`already_started` → `AppointmentFailure.ALREADY_STARTED`.

Interfaz (`AppointmentStartCancelUi.kt`, dentro de la tarjeta de la cita): el profesional ve «Comenzar trabajo» con
un diálogo que explica que después no habrá cancelación con devolución automática y que comenzar no autoriza
costes. Ambas partes ven «Cancelar la cita» antes del inicio; el diálogo muestra lo que se devolverá (o que el pago
directo no lo devuelve la app, o que no hay dinero) y que se libera la hora. Ambas cuentas ven «Trabajo comenzado
el …» o «Cancelada por ti / por la otra parte el …». Textos `start_cancel_*` en ES/EN/FR/DE.

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_start_cancel.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AppointmentsTest'
```

Resultados: [comprobación de inicio y cancelación](comprobacion-inicio-cancelacion.md).
