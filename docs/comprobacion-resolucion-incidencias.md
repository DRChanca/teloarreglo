# Comprobación de la resolución de incidencias — #31

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#resolución-administrativa-de-incidencias--31).

No se ha desplegado ni escrito en la base alojada. La migración `20261123013100_incident_resolution.sql` solo se ha aplicado
a la pila Supabase local, en la rama `ticket/31` con `spec/11-demo` (incluidos #27 y #28) ya fusionada. El panel no se ha
desplegado en Cloudflare. El dinero es simulado.

## Ejecutado localmente

### pgTAP `supabase/tests/incident_resolution.test.sql`

Resultado: 65/65. La suite completa da 930/930 en 33 archivos.

Fixtures: seis trabajos (integrado `done` con queja de calidad, integrado `started` con petición de cancelación, integrado
`done` con disputa de realización, integrado ya entregado, directo y gratuito).

- **Permisos.** Ni los participantes ni terceros listan ni deciden (`permission_denied`). El anónimo tampoco. Un
  administrador que es parte de la cita no la decide ni la ve en su lista.
- **Lectura.** La revisión muestra condiciones, saldo, movimientos, etapa y pruebas con roles. No contiene la dirección,
  las coordenadas ni el mensaje no aportado. El administrador sigue sin leer `chat_messages` ni `appointments`.
- **Reparto.** Motivo obligatorio. Parcial = pendiente, mayor que el pendiente o 0 → `invalid_settlement`. Saldo distinto
  del revisado → `balance_changed`.
  - `split` 30 € / 55 €: el saldo queda `8500/0/3000/5500` con un `refunded` y un `delivered` del administrador, y la cita
    queda `completed/administration`.
  - Repetir la misma decisión la devuelve; otra decisión → `decision_already_recorded`. No hay movimientos duplicados.
  - Una confirmación tardía del cliente devuelve el cierre sin otra entrega.
- **Realización.** Es independiente del dinero.
  - Devolución total en una cancelación tras el inicio con «consta realizado»: la cita queda cerrada sin marcas de
    «Realizado».
  - «Consta no realizado» primero deja la incidencia abierta y bloqueando la entrega; después, «entregar todo» la resuelve
    y conserva `not_performed`.
- **Auditoría.** Actor, fecha y motivo. Ni con SQL de confianza se reescribe un reparto ni se reabre la incidencia
  (`incident_resolution_locked`). Cada decisión genera dos avisos `incident_decision`. Una incidencia resuelta no admite
  pruebas.
- **Sin saldo.** Tras la entrega, con pago directo y con visita gratuita → `nothing_pending`, y sí se concluye la
  realización. Lo entregado queda intacto. La directa `started` sigue `started`. La gratuita `done` se cierra al resolverse
  (`completed/administration`).

### `supabase/tests/reviews.test.sql` (#28)

Una línea del fixture cambiaba una decisión de realización ya ejecutada, y ahora el disparador lo impide. Se hace con
`session_replication_role = replica` solo en ese paso, con un comentario; el resto de #28 no cambia.

### `python3 scripts/contract_incident_resolution.py --local`

Resultado: 5 pruebas OK. Usa Auth y PostgREST reales con cinco cuentas desechables (cliente, profesional, tercero y dos
administradores).

- **Permisos.** Anónimo, participantes y tercero reciben 401 o 403 en las tres RPC. Un PATCH del participante no cambia
  nada. La revisión no contiene dirección, coordenadas ni chat no aportado.
- **Dos administradores a la vez** (`refund` y `split`, 3 repeticiones): siempre 200 + 409 y un saldo coherente con el
  ganador.
- **Reparto y confirmación del cliente a la vez** (3 repeticiones): una sola entrega y la cita `completed/administration`.
- **Reloj.** El reloj real de #27 (`private.run_completion_clock()`), con el plazo vencido, salta la cita mientras la
  incidencia está abierta, también con la realización ya decidida. Tras el reparto no hay segunda entrega ni cierre
  `no_answer`. Ambas partes leen las decisiones y su motivo y tienen 2 avisos `incident_decision` en `my_notices`.
- **Adaptador real del panel** (`panel/src/incidents.local.test.ts`, 2 pruebas): una cuenta normal es rechazada; el
  administrador recibe `balance_changed` con un saldo viejo, reparte 25 €/55 €, decide «consta realizado» y no puede
  repartir otra vez.

### Otros contratos y comprobaciones

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| Los 24 `contract_*.py --local` | Todos con código de salida 0 | Una sola pasada con la base recién reiniciada; incluye `incidents`, `completion`, `clock`, `reviews` y `payments` |
| Panel `npm run check` | `tsc` correcto; 22 pruebas Vitest (4 locales omitidas fuera del contrato) | Ver detalle abajo |
| `npm run build` | `dist/` de 189 kB sin claves secretas | — |
| `python3 scripts/android.py check` | OK; 202 pruebas unitarias en `pruebas` | `IncidentsTest` 11/11, 2 nuevas (ver detalle abajo); lint con las 11 claves `resolution_*` en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK | Solo compilación |

Detalle de las 7 pruebas nuevas de `Incidents.test.tsx`:

- La revisión muestra cita, condiciones, saldo, mensaje aportado y aviso de privacidad, y la foto se pide como URL firmada.
- El reparto parcial exige un importe válido y un motivo, y muestra un resumen antes de ejecutar. Envía los céntimos y el
  saldo esperado.
- La realización se registra aparte.
- Tras la entrega, con pago directo o con visita gratuita no hay botones de dinero, y se explica por qué.
- Las decisiones ejecutadas aparecen sin acciones.
- Un rechazo del servidor se muestra como error, no como confirmación.

Además, el adaptador tiene 3 pruebas nuevas sobre la ruta, el cuerpo y los errores estables.

Detalle de las 2 pruebas nuevas de `IncidentsTest`:

- Una incidencia resuelta conserva por separado el reparto y la realización.
- Una incidencia con reparto ya no bloquea, la cita `administration` da su propio aviso y se pueden aportar pruebas hasta
  decidir la realización.

MockEngine y los servicios simulados comprueban la app y el panel, no el recorrido completo. La evidencia contra un
servidor real es pgTAP y el contrato HTTP local.

## No ejecutado

- Recorrido panel→Android con dos emuladores: no se ha asignado emulador desechable y no se ha usado emulator-5554.
- Despliegue del panel y de la migración en el proyecto alojado.

## Pendiente antes de dar por aceptada la issue

1. David despliega la migración en la base alojada como paso revisado.
2. Se prepara una cita integrada, pagada y comenzada con dos emuladores desechables. El cliente marca una incidencia y
   aporta una foto.
3. En el panel, como administrador, se abre «Incidencias». Hay que comprobar que solo aparecen la cita, las condiciones,
   el saldo, los movimientos y la foto aportada.
4. El administrador elige «Devolver una parte y entregar el resto», escribe el importe y el motivo, revisa el resumen y
   confirma.
5. En Android, ambas cuentas ven el reparto con su motivo, el aviso «Novedad en una incidencia» y «Cerrada por
   administración».
6. El administrador registra «Consta realizado» con motivo. En Android aparece la decisión y la incidencia pasa a
   «Revisada». El cliente puede reseñar (#28).
7. Repetir con una incidencia tras el cierre. El panel no ofrece botones de dinero; solo se concluye la realización.
8. Repetir con una cita de pago directo y con una visita gratuita.
9. Revisar los textos del panel y los `resolution_*` en ES/EN/FR/DE, incluido el panel a ancho de móvil. Ningún texto
   promete un plazo de atención.
