# Comprobación de comenzar trabajo y cancelar antes del inicio — #24

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#comenzar-trabajo-y-cancelar-antes-del-inicio--24).

No se ha desplegado ni escrito en la base alojada. La migración
`20261123012400_appointment_start_cancel.sql` solo se ha aplicado a la pila Supabase local
(rama `ticket/24` sobre `spec/11-demo`). Las devoluciones son simuladas: no hay pasarela ni datos bancarios.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointment_start_cancel.test.sql` | 40/40 (suite completa: 591/591 en 24 archivos) | El cliente no comienza (`permission_denied`); una cita inexistente o ajena no se revela; una solicitud sin confirmar no comienza (`incompatible_state`); el profesional comienza y queda `started_at` de servidor; repetir devuelve la misma marca; un único aviso `started` al cliente; comenzar no mueve dinero ni añade movimientos; tras el inicio ni cliente ni profesional cancelan (`already_started`), no se pueden proponer cambios de coste (`incompatible_state`) y no se devuelve nada; el cliente ve la marca; un tercero no cancela; el cliente cancela una cita integrada (tras una reducción ya devuelta) → `cancelled`, hora y autor de servidor, saldo `8500/0/8500/0`, movimientos `paid:8500, refunded:1500, refunded:7000 (cancelación)`, reserva liberada, cambio pendiente `superseded`, aviso al profesional; repetir y la cancelación tardía del profesional devuelven la primera sin otra devolución ni aviso; una cita cancelada no comienza, no cambia y su cambio cerrado no se acepta; pago directo: sin devolución ni movimiento y `agreed_cents` intacto, aviso al cliente; gratuita: sin pago; solicitud sin confirmar no se cancela aquí; la hora liberada se vuelve a confirmar; sin escritura directa de la etapa; anónimo sin acceso |
| `python3 scripts/contract_start_cancel.py --local` | 1 prueba OK | Auth y PostgREST reales: 4 rondas con «Comenzar trabajo» (profesional) y «Cancelar» (cliente) enviados a la vez → siempre un 200 y un 409; se observaron los dos resultados (`cancelled` y `started`) y en ambos el estado que leen las dos cuentas es coherente (comenzada: pendiente 8000, sin devolución, reserva; cancelada: devuelto 8000 en un solo movimiento, sin reserva). Las dos partes cancelan a la vez y se reintenta → un solo `cancelled_by` y una sola devolución. Directo y gratuito: `refunded_cents = 0`, sin movimientos, y la hora liberada se reserva de nuevo. Un tercero y anónimo no leen ni actúan; el cliente no comienza (403); sin PATCH directo |
| Resto de contratos `--local` | OK los 18 `contract_*.py` | Incluidos `appointments`, `agenda`, `payments` y `changes`, que siguen pasando con las etapas nuevas |
| `AppointmentsTest` (MockEngine) | 28/28 (5 nuevas) | Llamadas a `start_work` y `cancel_appointment` y lectura de las marcas; la lista pide `started_at,cancelled_at,cancelled_by` y ambas cuentas ven los avisos `STARTED`/`CANCELLED`; solo el profesional comienza y nadie cancela tras el inicio ni sobre una solicitud pendiente; efecto de cancelar (devolución de todo lo pendiente, también tras una devolución parcial previa; directo no devuelto; gratuita sin dinero); `already_started` → `ALREADY_STARTED`, `incompatible_state` → `STALE`, 403 → `ACCESS` |
| Unitarias Android | 167/167 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 18 claves nuevas `start_cancel_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +2: solo el profesional ve «Comenzar trabajo» y después nadie ve «Cancelar la cita»; el cliente ve la devolución antes de cancelar y queda el saldo devuelto |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la evidencia contra
servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.
- `panel/`: no se ha modificado.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores y una cita pagada (integrado): el cliente pulsa «Cancelar la cita», lee «Se devolverá
   íntegramente al cliente…» y confirma; ambas cuentas ven «Cancelada por ti / por la otra parte», el saldo
   devuelto y el horario libre en la agenda del profesional.
3. Otra cita confirmada: el profesional pulsa «Comenzar trabajo»; el cliente ve «Trabajo comenzado el …» al
   reabrir y ninguna cuenta tiene ya «Cancelar la cita» ni «Proponer un cambio».
4. Repetir la cancelación con pago directo (la app no devuelve nada) y con una visita gratuita (sin dinero).
5. Revisar los textos `start_cancel_*` en ES/EN/FR/DE y los diálogos a 320×640 dp.
