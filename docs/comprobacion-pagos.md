# Comprobación de confirmación con pago simulado o pago directo — #22

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#confirmación-con-pago-simulado-o-pago-directo--22).

No se ha desplegado ni escrito en la base alojada. La migración
`20261123002200_paid_confirmation.sql` solo se ha aplicado a la pila Supabase local
(rama `ticket/22` con `spec/11-demo` fusionada, incluidas #38 y #40). El pago es simulado: no se ha
contratado ningún servicio de pago ni se piden datos bancarios.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/payments.test.sql` | 39/39 (suite completa: 465/465 en 20 archivos) | Sin columnas bancarias; el profesional y otro cliente no confirman; modalidad o clave no válidas; pago integrado: `paid = pending = 8500`, nada entregado ni devuelto, cita `confirmed/accepted`, un movimiento `paid`; misma clave y clave nueva con la misma intención devuelven el resultado original sin segundo movimiento ni aviso; clave reutilizada con otra modalidad o propuesta → `retry_conflict`; pasar a directo una cita pagada → `incompatible_state`; propuesta gratuita → `payment_not_required` y `accept_proposal` sigue confirmándola sin pago; propuesta con coste sigue rechazada por `accept_proposal`; solape → `slot_unavailable` sin pago, movimiento ni cambio de estado; versión sustituida → `stale_version`; pago directo confirma con dinero integrado 0 y sin movimientos; solo coste de visita → se paga ese importe; el profesional lee los pagos y movimientos de sus citas, nadie más (tampoco administración); sin escrituras directas; propuesta caducada no se cobra; anónimo sin acceso. `agenda.test.sql` (41/41) valida que `accept_proposal` redefinida conserva su comportamiento |
| `python3 scripts/contract_payments.py --local` | 1 prueba OK | Auth y PostgREST reales: 6 rondas en las que dos clientes pagan a la vez propuestas solapadas → siempre un 200 y un 409, el perdedor sigue `pending`, sin pago ni movimientos; la misma clave enviada dos veces a la vez, repetida y con clave nueva → un pago y un movimiento; clave con otra modalidad → 400; ambas cuentas leen el saldo y otro cliente nada; tras pagar, `appointment_places` da al profesional la dirección exacta y una cita sin confirmar sigue aproximada (#38); pago directo sin movimientos y con reserva; gratuita → 400 y `accept_proposal` 200; anónimo, profesional y otro cliente no confirman; sin POST/PATCH directos |
| Resto de contratos `--local` | OK: account_names, agenda, appointments, catalogue, chat, chat_photos, chat_unread, chat_videos, covers, home, identity, map, profile_editing, profile_review, subscription | — |
| `AppointmentsTest` (MockEngine) | 16/16 | RPC `confirm_proposal` con propuesta, clave y modalidad; `appointment_payments(*)` en la lectura; saldo en céntimos; citas gratuitas sin pago; importe a pagar = precio o coste de visita; errores a `AppointmentFailure` |
| Unitarias Android | 150/150 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 11 claves nuevas `payment_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +3: pagar con respuesta perdida sin segundo cobro, pago directo sin dinero integrado, hora ocupada sin cobro |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la
evidencia contra servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores: el profesional propone 85,50 €; el cliente ve alcance, hora e importe, paga
   (simulado) y ambas cuentas ven «Cita confirmada» y «Pagado: 85,50 € · Pendiente de entrega: 85,50 €».
   El profesional ve entonces la dirección exacta.
3. Otra cita con pago directo: ambas cuentas ven «Pago directo acordado» sin importe pagado.
4. Dos clientes con propuestas solapadas: el segundo pago muestra que la hora ya no está libre y no
   aparece ningún importe pagado.
5. Revisar los textos `payment_*` en ES/EN/FR/DE y el selector de idioma a 320×640 dp.
