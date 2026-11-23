# Comprobación de marcar realizado y confirmar la entrega manual — #25

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#marcar-realizado-y-confirmar-la-entrega-manual--25).

No se ha desplegado ni escrito en la base alojada. La migración
`20261123012600_appointment_completion.sql` solo se ha aplicado a la pila Supabase local
(rama `ticket/25` sobre `spec/11-demo`). Las entregas son simuladas: no hay pasarela, banco ni dinero real.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointment_completion.test.sql` | 46/46 (suite completa: 660/660 en 26 archivos) | El cliente no marca realizado (`permission_denied`); un tercero tampoco y una cita inexistente no se revela; una cita confirmada sin comenzar no se marca (`incompatible_state`); el cliente no confirma antes de la marca (`incompatible_state`); el profesional marca realizado: `done_at` = hora de servidor de la transacción y vencimientos a +24 h y +48 h; un cambio de precio pendiente queda `superseded`; marcar no entrega nada ni añade movimientos; un único aviso `done` al cliente; repetir devuelve la misma marca sin otro aviso; el profesional no confirma; tras la marca no hay cambios ni cancelación; un tercero no confirma ni ve la cita; el cliente ve la marca y el vencimiento; el cliente confirma → `completed`, `client_confirmed`, saldo `8500/0/0/8500`, movimientos `paid:8500, delivered:8500`, aviso `completed` al profesional; repetir devuelve el mismo resultado sin otra entrega; el profesional ve la finalización y la entrega; directo: `agreed_cents` intacto y ningún movimiento; gratuita: sin pago; una incidencia bloqueante (seam `private.delivery_blocked` redefinido en la transacción de prueba) rechaza la confirmación con `incident_blocking` y no entrega; invariante compartida: las cuentas de la app no la llaman, el cierre sin respuesta antes del vencimiento falla (`not_due`), después cierra como `no_answer`, entrega una sola vez aunque se repita y avisa a las dos partes sin actor; índice único: una sola entrega por cita; sin escritura directa de etapa ni saldo; anónimo sin acceso |
| `python3 scripts/contract_completion.py --local` | 2 pruebas OK | Auth y PostgREST reales con tres cuentas desechables: recorrido solicitud → propuesta → pago integrado simulado → comenzar → realizado → confirmación. Confirmación prematura (409) y marca del cliente (403). Dos «Marcar realizado» simultáneos → misma `done_at`, un aviso. Dos confirmaciones simultáneas del cliente → mismas respuestas, una sola entrega; ambas cuentas leen `completed/client_confirmed`, saldo `8000/0/8000`, movimientos `paid, delivered` y un aviso `completed`. Directo y gratuito finalizan con `delivered_cents = 0` y sin movimientos. Tercero y anónimo no leen ni actúan; ninguna cuenta hace PATCH de la etapa o del saldo |
| Resto de contratos `--local` | OK los 19 `contract_*.py` | Incluidos `appointments`, `agenda`, `payments`, `changes` y `start_cancel`, que siguen pasando con las etapas nuevas |
| `AppointmentsTest` (MockEngine) | 36/36 (5 nuevas) | Llamadas a `mark_done` y `confirm_done` y lectura de sus marcas; la lista pide `done_at,reminder_due_at,close_due_at,completed_at,completion`; avisos `CONFIRM_DONE` (cliente), `AWAITING_CONFIRMATION` (profesional), `COMPLETED` y `COMPLETED_NO_ANSWER`; solo el profesional marca un trabajo comenzado y solo el cliente confirma; tras la marca no hay cambios ni cancelación; efecto de confirmar (entrega de lo pendiente, directo no gestionado, gratuita sin dinero); `incident_blocking` → `INCIDENT_BLOCKING`, `incompatible_state` → `STALE`, 403 → `ACCESS` |
| Unitarias Android | 175/175 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 22 claves nuevas `completion_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +1: el cliente no ve «Marcar como realizado»; el profesional marca, aparece el vencimiento y sigue pendiente el pago; el cliente ve la entrega en el diálogo, confirma y queda «Entregado al profesional» |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la evidencia contra
servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.
- Recordatorio a 24 h y cierre a 48 h: pertenecen a #27. Aquí solo se persisten los vencimientos y se ofrece la
  invariante `private.complete_appointment(…, 'no_answer')`, probada con fechas controladas en pgTAP, no con Cron.
- Incidencias: pertenecen a #26. Aquí solo existe el punto de extensión `private.delivery_blocked`, que hoy devuelve
  siempre `false`; el bloqueo se ha comprobado redefiniéndolo dentro de la transacción de prueba.
- Push (#32): los avisos quedan en `appointment_events`; no se envía ninguna notificación.
- `panel/`: no se ha modificado.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores y una cita pagada (integrado) y comenzada: el profesional pulsa «Marcar como realizado» y lee
   que el pago no se entrega todavía; el cliente ve «Trabajo marcado como realizado: confirma si se ha hecho», la
   fecha de cierre automático y el saldo aún pendiente.
3. El cliente pulsa «Confirmar que se ha realizado», lee «Se entregará al profesional lo pendiente: … (simulado)» y
   confirma. Ambas cuentas ven «Cita finalizada», «Entregado al profesional» y el saldo con Pendiente 0; cerrar y
   reabrir la app conserva el estado.
4. Repetir con pago directo (la app no entrega nada) y con una visita gratuita (sin dinero).
5. Revisar los textos `completion_*` en ES/EN/FR/DE y los diálogos a 320×640 dp.
