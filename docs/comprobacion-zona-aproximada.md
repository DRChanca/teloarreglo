# Comprobación de la zona aproximada de la cita — #38

Fecha: 23 de noviembre de 2026. Contrato: [citas-contrato.md](citas-contrato.md#zona-aproximada--38).

No se ha desplegado ni escrito en la base alojada. No se ha cambiado nada en Google Cloud.

## Ejecutado localmente

- pgTAP `supabase/tests/appointment_area.test.sql`: 48 comprobaciones superadas.
  - Geometría, con 4000 centros generados por `private.approximate_center`:
    - ninguno se aleja más de 300 m;
    - todos son distintos;
    - la media de d² es R²/2 ± 10 %, como corresponde a un reparto uniforme en el disco;
    - una cuarta parte queda a menos de 150 m;
    - los cuatro cuadrantes salen con la misma frecuencia.
  - Centro guardado una vez: está a ≤ 300 m de la casa y un reintento de la solicitud lo conserva.
    Confirmar no lo mueve, y sobrescribirlo directamente no surte efecto. Solo se recalcula si cambia
    el punto exacto.
  - Antes de confirmar, el profesional no puede leer la dirección ni las coordenadas exactas por
    ninguna de estas vías:
    - la tabla, con `address`, `latitude`, `longitude`, `select *`, fila completa o filtros sobre
      esas columnas (todas responden `42501`);
    - `appointment_places`, que le devuelve `exact = false` y nulos;
    - `request_appointment`;
    - el resultado de `propose_appointment`;
    - `appointment_proposals` y `appointment_events`;
    - `conversations` y `chat_messages`;
    - `conversation_inbox()` y `my_notices()`;
    - `home_addresses`.

    Sí lee el centro, el radio de 400 m y el concejo.
  - Una tercera cuenta recibe 0 filas y `anon` no puede llamar a la función.
  - Después de confirmar, `appointment_places` entrega al profesional la dirección y las coordenadas
    exactas y queda registrado `location_shared_at`, que ya no se puede borrar. El aviso `accepted`,
    `my_agenda()` y `agenda_reservations` siguen sin contener el lugar exacto.
- `appointments.test.sql` (#20) se ha adaptado. Antes comprobaba que el profesional leía la dirección
  exacta nada más recibir la solicitud. Ahora comprueba que solo lee la zona.
- Suite completa tras unir `spec/11-demo` (`db-check.sh`): 407 comprobaciones en 18 archivos,
  superadas.
- Contratos `--local` en la misma ejecución, todos superados: `agenda`, `appointments`, `catalogue`,
  `chat`, `chat_photos`, `chat_unread`, `chat_videos`, `covers`, `home`, `identity`, `map`,
  `profile_editing`, `profile_review` y `subscription`.
  - `contract_appointments.py` lee como Android, con columnas explícitas y `appointment_places`.
    Comprueba que `select=*`, `address`, `latitude` y `longitude` responden 401/403 a las dos partes,
    que el profesional recibe `exact = false` sin dirección y que otra cuenta recibe `[]`.
  - `contract_agenda.py` comprueba que, tras confirmar, el profesional recibe `exact = true` y que las
    solicitudes pendientes de la misma conversación siguen ocultas.
- Pruebas unitarias en JVM:
  - `AppointmentsTest` (11): la lista nunca pide `*`, `address`, `latitude` ni `longitude`, llama a
    `appointment_places` con la conversación y resuelve `Exact` o `Approximate`. Sin dirección nunca
    hay lugar exacto, y sin respuesta el lugar queda desconocido.
  - `AgendaTest` se ha adaptado a la segunda llamada.
  - Total del módulo: 135 pruebas en 20 clases, sin fallos.
- `python3 scripts/android.py check` (compilación, pruebas unitarias y lint): superado.
  `:app:compilePruebasDebugAndroidTestKotlin`: superado.

No se han ejecutado pruebas instrumentadas ni se ha visto el círculo en un emulador.

## Pendiente antes de dar por aceptada la issue

1. Con dos emuladores desechables, no emulator-5554, y la app de pruebas contra la pila local o tras
   desplegar:
   1. El cliente solicita una cita. En su detalle ve la dirección, las coordenadas, «Cómo llegar» y el
      aviso «Hasta confirmar la cita, el profesional solo ve una zona aproximada de 400 m».
   2. El profesional abre la cita. Ve «Zona aproximada de 400 m en <concejo>» y, con clave de Maps, el
      círculo sin marcador. No ve la dirección, las coordenadas ni «Cómo llegar».
   3. Tras proponer una visita gratuita y aceptarla el cliente, el profesional ve la dirección exacta y
      «Cómo llegar».
   4. Con la clave de Maps vacía, el profesional solo ve el texto de la zona.
2. Revisar los textos nuevos en ES, EN, FR y DE:
   - las claves `area_*`;
   - el nuevo texto de `appointment_address_privacy`;
   - «Cómo llegar» (`map_open_appointment`).
3. Desplegar la migración en la base alojada como paso revisado de David. Hasta que llegue el APK
   nuevo, la versión anterior instalada deja de cargar las citas, porque pide `select=*`.
