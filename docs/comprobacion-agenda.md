# Comprobación de agenda y confirmación de visitas gratuitas — #21

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#agenda-y-confirmación-gratuita--21).

No se ha desplegado ni escrito en la base alojada. La migración
`20261123002100_agenda_confirmation.sql` solo se ha aplicado a la pila Supabase local
(rama `ticket/21` con `spec/11-demo` fusionada).

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/agenda.test.sql` | 41/41 (suite completa: 275/275 en 14 archivos) | Sin perfil no hay agenda; duración y franjas semanales; guardar sustituye el horario; franjas, días y duración no válidos sin efecto; propuestas pendientes no reservan; solo el cliente de la cita acepta; aceptación gratuita confirma y reserva; reintento idempotente con un solo aviso; propuesta solapada de otro cliente → `slot_unavailable` sin efectos parciales y la solicitud sigue pendiente; otra hora contigua `[10:00, 11:00)` tras `[09:00, 10:00)` se confirma; versión sustituida → `stale_version`; fuera de disponibilidad, bloqueado, con coste (`payment_required`) y caducado (`proposal_expired`) rechazados; bloqueo idempotente por clave, `retry_conflict`, sin cubrir una cita confirmada, retirada idempotente; sin escritura directa de etapa, estado o reservas; tercera cuenta (también administradora) no lee agenda ni bloquea; anónimo sin acceso |
| `python3 scripts/contract_agenda.py --local` | 1 prueba OK | Auth y PostgREST reales: 8 rondas en las que dos clientes aceptan a la vez (hilos con barrera) propuestas solapadas → siempre exactamente un 200 y un 409, la perdedora sigue `pending/pending` y hay una reserva por ronda; el mismo cliente acepta dos veces a la vez → dos 200 y un único aviso `accepted`; ambas cuentas leen `confirmed`; contigua aceptada; fuera de horario 409; bloqueo por clave, solape 409, aceptar dentro del bloqueo 409 y tras retirarlo 200; sin POST/PATCH/DELETE directos; nadie más lee la agenda |
| Resto de contratos `--local` | OK: identity, catalogue, covers, chat, chat_photos, chat_unread, map, appointments, profile_review, subscription | `contract_profile_review.py` necesita `panel/node_modules`; en un worktree nuevo falla por esa razón y pasa enlazando las dependencias del checkout principal |
| `AgendaTest` (MockEngine) | 9/9 | RPC y cuerpo de `accept_proposal`, `my_agenda`, `save_agenda`, `add_agenda_block`, `remove_agenda_block`; errores a `AppointmentFailure`/`AgendaFailure`; cita confirmada con aviso `CONFIRMED` para ambos roles; solo propuestas gratuitas son aceptables sin pago |
| Unitarias Android | 96/96 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 25 claves nuevas (`appointment_*`, `agenda_*`) en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` (+2) y `AgendaUiTest` (2) |

MockEngine y los servicios simulados comprueban la app, no el recorrido de
extremo a extremo; la evidencia contra servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` (aceptar visita gratuita y verla confirmada; hora ocupada
  explicada sin confirmar) y `AgendaUiTest` (guardar disponibilidad; bloqueo
  solapado no mostrado como guardado). Requieren un emulador desechable; no se ha
  usado emulator-5554.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores (cliente y profesional): el profesional guarda horario y
   duración y bloquea una hora; el cliente solicita, recibe una propuesta gratuita,
   la acepta, y ambas cuentas ven «Cita confirmada» y la hora reservada.
3. Con dos clientes y propuestas solapadas: la segunda aceptación muestra que la
   hora ya no está libre y la solicitud sigue abierta; el profesional envía otra hora.
4. Revisar los textos nuevos en ES/EN/FR/DE y el selector de idioma a 320×640 dp.
