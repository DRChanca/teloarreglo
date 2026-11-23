# Comprobación de cambios con el trabajo comenzado y precios guardados en el chat

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#cambios-con-el-trabajo-comenzado-y-precios-guardados-en-el-chat--decisiones-del-23-de-noviembre-de-2026).
Decisiones: [especificacion.md](especificacion.md#actualización-decisiones-de-david-del-23-de-noviembre-de-2026).

No se ha desplegado ni escrito en la base alojada. La migración `20261123012500_changes_during_job.sql` solo se
ha aplicado a la pila Supabase local (rama `fix/cambios-y-precio` sobre `spec/11-demo`). El dinero es simulado.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointment_changes_started.test.sql` (nuevo) | 23/23 (suite completa: 614/614 en 25 archivos) | El profesional lee los precios guardados con la solicitud (3500) aunque el perfil publique 9900. Al comenzar: un cambio pendiente solo de precio sigue `pending`; uno que mueve la hora y una petición de corrección pasan a `superseded`. Con la cita `started`: el cliente acepta el aumento (8500 → 9900), la cita sigue `started`, condiciones nuevas vigentes, saldo `9900/9900/9900` y un movimiento `paid:1400`; el cliente propone más duración y el profesional la acepta (reserva de 90 min); una reducción a 8000 devuelve 1900 (`9900/8000/1900/8000`); otra hora de inicio, otra dirección o una corrección de dirección → `already_started` sin dejar nada pendiente; mismas condiciones → `no_change`; un cambio durante el trabajo se rechaza, se conservan las condiciones y se avisa al autor |
| pgTAP `appointment_start_cancel.test.sql` (#24, ajustado) | 40/40 | La comprobación «tras el inicio no hay cambios» pasa a «tras el inicio la hora de inicio no se mueve» (`already_started`); el resto sin cambios (cancelar sigue cerrando el cambio pendiente; una cita cancelada no cambia) |
| `python3 scripts/contract_start_cancel.py --local` | 2 pruebas OK | Prueba nueva con Auth y PostgREST reales: «Comenzar trabajo» y aceptar un cambio que mueve la hora, a la vez → el trabajo siempre comienza; se observaron los dos resultados (`accepted` si la aceptación llegó antes, `superseded` y 409 si llegó después). Trabajo extra tras comenzar: el profesional propone 95 €, el cliente acepta y lee `paid/pending/agreed = 9500`; otra hora de inicio → 409. Los dos participantes leen `reference_prices` = 3500 tras cambiar el perfil a 9900, una tercera cuenta no ve la fila y una solicitud nueva guarda 9900. La prueba de #24 sigue pasando |
| Resto de contratos `--local` | OK los 18 `contract_*.py` | Incluidos `changes`, `appointments`, `payments` y `agenda` |
| `AppointmentsTest` (MockEngine) | 31/31 (3 nuevas) | La lista pide `reference_prices` y la lee como `referencePrices` (vacía en filas sin ella); con `STARTED` ambos participantes pueden proponer cambios, la hora y la dirección no cambian, no hay corrección de dirección y el aviso es `DECIDE_CHANGE`/`AWAITING_CHANGE` con un cambio pendiente o `STARTED` sin él; con `CONFIRMED` todo sigue como en #23; `CANCELLED` y `PENDING` no admiten cambios; `already_started` en `propose_change` → `ALREADY_STARTED` |
| Unitarias Android | 170/170 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 7 claves nuevas `kept_prices_*` y `running_change_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +1: tras comenzar, la tarjeta muestra los precios guardados y el aviso de cambios en curso, sin «Pedir corrección»; el formulario muestra el inicio fijo sin campo de hora; el profesional propone 95,50 €, el cliente acepta y queda pagado 95,50 € con la misma hora de inicio |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo. La evidencia contra un
servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable. No se ha usado emulator-5554.
- `panel/`: no se ha modificado.

## Pendiente antes de dar por aceptado

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores y una cita pagada (integrado): el profesional pulsa «Comenzar trabajo» y después «Proponer un
   cambio». Comprobar que el formulario no deja cambiar la hora ni la dirección. Proponer un importe mayor. El
   cliente ve «Antes → Propuesto» y «Se cobrará la diferencia…», acepta, y las dos cuentas ven el saldo nuevo.
   Repetir con una reducción (devolución simulada) y con pago directo (solo se registra el acuerdo).
3. Con un cambio de precio pendiente, pulsar «Comenzar trabajo». El cambio sigue pendiente y se puede aceptar. Con
   un cambio de hora pendiente, comenzar lo cierra.
4. Enviar una solicitud y después cambiar los precios del perfil. En el chat, la tarjeta de la solicitud sigue
   mostrando los precios de cuando se envió, y el catálogo muestra los nuevos.
5. Revisar los textos `kept_prices_*` y `running_change_*` en ES/EN/FR/DE a 320×640 dp.
