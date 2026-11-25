# Comprobación de cambios de condiciones — #23

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#cambios-de-condiciones--23).

> Nota del 25-11-2026: registro histórico. `out_of_coverage` significa ahora «fuera de Asturias», no fuera de la
> zona de trabajo ([ADR 0001](adr/0001-zona-de-trabajo-informa.md), [citas](citas-contrato.md#zona-de-trabajo-informativa--25-de-noviembre-de-2026)).

No se ha desplegado ni escrito en la base alojada. La migración
`20261123012300_appointment_changes.sql` solo se ha aplicado a la pila Supabase local
(rama `ticket/23` sobre `spec/11-demo`). Los pagos y devoluciones son simulados: no hay pasarela ni datos bancarios.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointment_changes.test.sql` | 45/45 (suite completa: 551/551 en 23 archivos) | Cambio basado en versión antigua → `stale_version`; mientras está pendiente no cambian propuesta aceptada, reserva ni pago; el autor no acepta su propio cambio; rechazo idempotente que conserva condiciones e historial; aceptar una versión rechazada o sustituida → `stale_version`; historial `1:rejected, 2:superseded, 3:pending`; aceptar un aumento integrado crea la nueva propuesta `accepted`, deja la anterior `replaced`, mueve la reserva a 90 min y paga la diferencia (12000/12000); aceptar otra vez no repite el movimiento (`paid:8500, paid:3500`); cambio a una hora ocupada → `slot_unavailable` con reserva intacta y cambio pendiente; condiciones idénticas → `no_change`; reducción aceptada por el profesional → devolución (`refunded:5000`, pendiente 7000); el profesional no puede poner dirección (`address_owner_only`) pero sí pedir corrección; la petición no se puede «aceptar» (`invalid_change`); dirección fuera de cobertura → `out_of_coverage`; el cambio de dirección del cliente sustituye la petición, la dirección anterior sigue vigente hasta aceptar y después `appointment_places` da la nueva al profesional, con la anterior en el historial; cita gratuita que pasa a tener coste: sin modo → `invalid_payment`, con pago directo se registra el acuerdo sin dinero integrado, y una reducción directa no crea movimientos; una cita sin confirmar no admite cambios (`incompatible_state`); cada propuesta y decisión deja un aviso; otra cuenta no lee ni decide; sin escrituras directas; anónimo sin acceso |
| `python3 scripts/contract_changes.py --local` | 1 prueba OK | Auth y PostgREST reales: 3 rondas en las que dos clientes aceptan a la vez el traslado de sus citas a la misma hora → siempre un 200 y un 409; el perdedor conserva propuesta, hora reservada y cambio pendiente; el mismo aumento aceptado dos veces a la vez y otra más → un solo movimiento de diferencia; propuesta repetida con la misma clave → mismo cambio, con otro contenido → 400; versión sustituida → 409 y la vigente 200 con devolución; dirección del profesional → 403; fuera de cobertura → 400; otra cuenta no lee la cita ni decide; anónimo tampoco; sin PATCH directo; la lectura embebida `appointment_changes(*)` funciona |
| Resto de contratos `--local` | OK: account_names, agenda, appointments, catalogue, chat, chat_photos, chat_unread, chat_videos, covers, home, identity, map, payments, profile_editing, profile_review, subscription | `profile_editing` y `profile_review` necesitan `npm ci` en `panel/` para su adaptador |
| `AppointmentsTest` (MockEngine) | 23/23 (7 nuevas) | Lectura con `appointment_changes(*)`, cambio pendiente sin tocar lo vigente, avisos por rol, petición de corrección, efecto en el dinero (diferencia, devolución, directo, elegir modo, modo ya elegido, sin cambio), cuerpo de `propose_change` con versión, clave, dirección y modo, dirección sin cambio enviada como `null`, llamadas de aceptar/rechazar/pedir corrección, errores a `AppointmentFailure` |
| Unitarias Android | 162/162 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 49 claves nuevas `change_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +3: el cliente ve antes/después y el efecto antes de aceptar; el profesional no decide su propio cambio; hora ocupada conserva la cita anterior |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la
evidencia contra servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores y una cita pagada (integrado): el profesional propone más importe y más duración;
   el cliente ve «Antes → Propuesto» y «Al aceptar se paga la diferencia», acepta y ambas cuentas ven el
   nuevo saldo y la nueva hora. Repetir con una reducción aceptada por el profesional (devolución).
3. Proponer un cambio a una hora ocupada por otra cita: al aceptar aparece que la hora no está libre y la
   cita sigue igual.
4. El profesional pide corregir la dirección; el cliente la corrige dentro de la zona y el profesional
   acepta y ve la nueva dirección. Comprobar que el profesional no tiene campos de dirección.
5. Cita con pago directo: un cambio de importe solo actualiza «Pago directo acordado».
6. Revisar los textos `change_*` en ES/EN/FR/DE y el diálogo a 320×640 dp.
