# Comprobación de solicitudes de cita y propuestas — #20

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md).

> Nota del 25-11-2026: registro histórico. Las solicitudes ya no exigen la zona de trabajo (cobertura): fuera
> de Asturias fallan con `outside_area` ([ADR 0001](adr/0001-zona-de-trabajo-informa.md), [citas](citas-contrato.md#zona-de-trabajo-informativa--25-de-noviembre-de-2026)).

No se ha desplegado ni escrito en la base alojada. La migración
`20261122002000_appointments.sql` solo se ha aplicado a la pila Supabase local.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointments.test.sql` | 37/37 (suite completa: 110/110 en 7 archivos) | Chat sin dirección; solicitud pendiente sin precio; actor de sesión; reintento idempotente y `retry_conflict`; dos solicitudes y dos propuestas pendientes en la misma hora; fuera de cobertura; hora pasada e intervalo invertido; el cliente no propone y el profesional no solicita ni rechaza; importe en céntimos e intervalo inicio+duración; versión desactualizada; importe negativo; sin escritura directa de estado ni propuestas; rechazo idempotente que conserva historial y deja la cita sin confirmar; aviso persistido por propuesta; versión 2 tras rechazo y versión 3 que sustituye a la 2; decidir una versión sustituida falla; tercera cuenta (también administradora) no lee citas, propuestas ni avisos ni propone; anónimo sin acceso |
| `python3 scripts/contract_appointments.py --local` | 1 prueba OK | Auth y PostgREST reales en loopback: solicitar, reintentar, conflicto 400, tercera cuenta 401/403, propuesta, reintento, versión desactualizada 409, rechazo solo por el cliente, versión 2, lectura con historial embebido por ambas cuentas tras el recorrido, sin lectura anónima ni de terceros, secuencia de avisos `requested → proposed → rejected → proposed`, sin PATCH/DELETE/POST directos y sin dirección en el perfil público |
| `AppointmentsTest` (HTTP simulado con MockEngine) | 8/8 | Ruta RPC y cuerpo (instantes UTC, dirección y pin, sin precio); lectura con `appointment_proposals(*)`; versión vigente e historial; errores 403/409/400 a `AppointmentFailure`; reintento con la misma clave tras perder la respuesta; aviso según rol; euros a céntimos sin coma flotante |
| Unitarias Android completas | 41/41 en `pruebas` y 41/41 en `presentacion` | — |
| `python3 scripts/android.py check` (`pruebas` y `presentacion`) | OK | Compilación, unitarias y lint, incluidas las traducciones ES/EN/FR/DE (62 claves `appointment_*` en cada idioma) |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `AppointmentsUiTest` |

Las pruebas con MockEngine y con el servicio simulado comprueban la app, no el
recorrido de extremo a extremo; la evidencia contra servidor real es pgTAP y el
contrato HTTP local.

## No ejecutado

- `AppointmentsUiTest` (3 pruebas: solicitud sin precio con respuesta perdida y
  reintento sin duplicado; propuesta de 85,50 € guardada como 8550 céntimos;
  rechazo de la primera versión que permanece en el historial). Requiere un
  emulador desechable asignado; no se ha usado emulator-5554.
- `LanguageTest` y el resto de pruebas instrumentadas.

## Pendiente antes de dar por aceptada la issue

1. Revisar y aplicar `20261122002000_appointments.sql` en la base alojada (paso de David).
2. Ejecutar `python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE`.
3. Recorrido manual en dos emuladores (cliente y profesional) contra la base
   alojada, con un profesional cuya cobertura incluya el punto elegido:
   1. El cliente abre el chat y comprueba que no se le pide ni muestra dirección.
   2. Solicita cita sin precio usando «Usar mi ubicación actual» y, en otro
      intento, coordenadas escritas a mano; ve «Pendiente: no está confirmada
      ni reserva la hora».
   3. El profesional ve el aviso «Nueva solicitud de cita», la dirección y las
      coordenadas, y envía una propuesta con importe, coste de visita,
      materiales y desplazamiento.
   4. Se envía una segunda solicitud para la misma hora y también se acepta.
   5. El cliente ve la versión 1 vigente, la rechaza y comprueba que sigue en
      el historial como «Rechazada»; el profesional ve el aviso y envía la versión 2.
   6. Se cierran y reabren ambas apps: el historial y los estados persisten.
   7. Modo avión durante un envío: aparece «No se ha confirmado» y al
      reintentar no se duplica.
   8. Revisar los textos en ES/EN/FR/DE, también a 320×640 dp.
4. Revisión visual de las primeras pantallas con David antes de extender el diseño.
