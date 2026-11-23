# Comprobación de incidencias y pruebas privadas — #26

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#incidencias-y-pruebas-privadas--26).

No se ha desplegado ni escrito en la base alojada. La migración `20261123012700_incidents.sql` solo se ha aplicado a la
pila Supabase local (rama `ticket/26` sobre `spec/11-demo`). El dinero es simulado: ninguna incidencia mueve saldo.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/incidents.test.sql` | 58/58 (suite completa: 718/718 en 27 archivos) | Sin aviso de visibilidad no se abre (`visibility_not_acknowledged`); tipo y descripción obligatorios; antes del inicio no hay incidencia (`incompatible_state`) y la petición de cancelación solo existe con el trabajo comenzado; terceros y citas inexistentes `permission_denied`. Cancelación tras el inicio: incidencia `disputed`, la cita sigue `started`, saldo `8500/0/0`, ningún movimiento nuevo; reintento con la misma clave devuelve la misma; misma clave con otro contenido `retry_conflict`; un aviso `incident_opened` al profesional; el profesional ve la incidencia y no puede abrir otra (`incident_open`). Bloqueo: `confirm_done` y el cierre sin respuesta ya vencido (`complete_appointment(…, 'no_answer')`) fallan con `incident_blocking` y no entregan. Queja de calidad en `done`: `acknowledged`, bloquea. Tras el cierre: `disputed`, `blocks_delivery = false`, lo entregado sigue entregado. Directo y gratuito: sin dinero gestionado; el profesional también abre; una gratuita con incidencia no finaliza. Pruebas: aviso obligatorio; mensaje o nota, no ambos ni vacíos; un mensaje de otra conversación se rechaza; el cliente aporta un texto (copia del contenido), el profesional una foto y una nota; reintentos y mensajes repetidos no duplican; aviso `incident_evidence`; sin UPDATE/DELETE. Terceros: 0 incidencias, 0 pruebas, sin lectura de la foto. Administrador: ve las incidencias y las 3 pruebas, 0 mensajes del chat, 0 citas, solo el objeto de Storage aportado (no el otro de la misma conversación), `can_read_chat_attachment` sigue falso, no aporta ni abre. Revisada: sin más pruebas (`incident_closed`) y ni el SQL de confianza reescribe pruebas (`evidence_locked`). Anónimo sin acceso |
| `python3 scripts/contract_incidents.py --local` | 3 pruebas OK | Auth, PostgREST y Storage reales con cuatro cuentas desechables (cliente, profesional, tercero, administrador). Apertura simultánea por ambas partes → una 200 y otra 409; saldo y etapa intactos para ambas. Foto y texto aportados, otro texto y otra foto no; PATCH de pruebas rechazado; la lista embebida que usa la app trae las 3 pruebas. El administrador obtiene URL firmada y descarga solo la foto aportada; la otra foto, el chat y los terceros quedan denegados. «Realizado» + confirmación → 409 y nada entregado. Incidencia y confirmación simultáneas (3 repeticiones): o la confirmación entrega y la incidencia queda como comunicación tras el cierre, o la incidencia bloquea y no se entrega; nunca más de una entrega. Directo y gratuito sin movimientos |
| Resto de contratos `--local` | OK los 20 `contract_*.py` | Incluidos `completion`, `payments`, `start_cancel` y los de adjuntos, sin cambios de comportamiento |
| `IncidentsTest` (MockEngine) | 8/8 | Llamadas y cuerpos de `open_incident` y `add_incident_evidence` (siempre con el aviso aceptado), mensaje frente a nota, errores estables (`ACCESS`, `ALREADY_OPEN`, `CLOSED`, `STALE`, `RETRY_CONFLICT`, `INVALID`), la lista pide `incidents(*,incident_evidence(*))`, quién puede abrir y en qué etapas, tipos por etapa, la incidencia bloqueante oculta «Confirmar» y muestra `INCIDENT_OPEN` a ambas partes, la comunicación tras el cierre no |
| Unitarias Android | 183/183 en `pruebas` | `AppointmentsTest` ajustado: la lista ahora termina con las incidencias embebidas |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 43 claves `incident_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` +1: el cliente abre una incidencia tras el inicio, el envío está desactivado hasta aceptar el aviso de visibilidad, desaparece «Comunicar una incidencia», el saldo sigue pendiente; el profesional elige un mensaje concreto y lo aporta tras el aviso |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la evidencia contra
servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.
- Panel de administración (#31): aquí solo existe la lectura protegida de incidencias, pruebas y archivos aportados; `panel/` no
  se ha modificado. La resolución (reparto y decisión de realización) y la lectura administrativa de la cita, condiciones y
  movimientos pertenecen a #31.
- Push (#32): los avisos quedan en `appointment_events`; no se envía ninguna notificación.
- Reloj (#27): el bloqueo se ha comprobado llamando a la invariante compartida con fechas controladas, no con Cron.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores y una cita pagada (integrado) y comenzada: el cliente pulsa «Comunicar una incidencia», elige «Pedir la
   cancelación tras el inicio», lee que no hay devolución automática y el aviso de visibilidad, marca «Entiendo quién lo verá» y
   envía. Ambas cuentas ven la incidencia abierta, el saldo aún pendiente y el aviso «Hay una incidencia abierta».
3. El profesional pulsa «Añadir prueba», elige una foto concreta del chat y la aporta; después añade una nota. El cliente ve las
   dos pruebas; ninguno puede editarlas ni borrarlas.
4. El profesional marca realizado: el cliente no ve «Confirmar que se ha realizado» mientras la incidencia esté abierta.
5. Repetir con una cita finalizada (queja tras el cierre: «no revierte lo ya entregado»), con pago directo y con una visita
   gratuita.
6. Revisar los textos `incident_*` en ES/EN/FR/DE y los diálogos a 320×640 dp.
