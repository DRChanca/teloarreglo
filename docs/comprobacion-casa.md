# Comprobación de la dirección de casa — #37

Fecha: 23 de noviembre de 2026. Contrato: [casa-contrato.md](casa-contrato.md).

> Nota del 25-11-2026: registro histórico. La pantalla «Cerca de mí» y el diálogo de lejanía ya no existen:
> la búsqueda única de «Buscar» parte de casa y avisa con un banner ([ADR 0001](adr/0001-zona-de-trabajo-informa.md), [búsqueda](busqueda-contrato.md)).

No se ha desplegado ni escrito en la base alojada. No se ha cambiado nada en Google Cloud.

## Ejecutado localmente

- pgTAP `supabase/tests/home_addresses.test.sql`: 22 comprobaciones superadas. Cubren el acceso de
  visitantes, la sesión sin sujeto, el guardado con concejo calculado en servidor, el borde a 250 m,
  una casa fuera de los concejos, la sustitución, las entradas inválidas, la ausencia de escritura
  directa, que otra cuenta no puede leer ni borrar y el borrado en cascada con la cuenta.
  Suite completa tras unir `spec/11-demo`: 297 comprobaciones en 15 archivos superadas
  (`db-check.sh`).
- `scripts/contract_home.py --local`: superado, con Auth y PostgREST reales en loopback. Comprueba
  que el propietario guarda y lee, que visitantes y otra cuenta no pueden, que PATCH, DELETE e
  INSERT directos se rechazan y que el catálogo no contiene la dirección.
- Resto de contratos `--local` en la misma ejecución:
  - Superados: `appointments`, `catalogue`, `chat`, `chat_photos`, `chat_unread`, `covers`,
    `identity`, `map` y `subscription`.
  - `contract_agenda.py` falló una vez (500 en lugar de 409 en la prueba de concurrencia) y pasó al
    repetirlo. No está relacionado con #37.
  - `contract_profile_review.py` falla en este worktree porque `panel/` no tiene `node_modules`
    (`npx --no-install vitest`). Es un problema del entorno, ajeno a #37.
- Pruebas unitarias en JVM: `HomeAddressTest` (7), `HomeSessionTest` (3) y `AddressLookupTest` (6).
  Cubren las RPC y el cuerpo enviado, la traducción de errores, la distancia y el umbral de 5 km, la
  pregunta una vez por sesión, que el paso solo es obligatorio cuando el servidor confirma que
  falta la casa, que un fallo de red no bloquea y el respaldo cuando Places falla. Places se
  sustituye por un doble de prueba. Total del módulo: 112 pruebas en 18 clases, sin fallos.
- `python3 scripts/android.py check`: compilación, pruebas unitarias y lint superados. Lint ignora
  solo `NotificationPermission` de Glide, que llega como dependencia de Places (`app/lint.xml`).
- `:app:compilePruebasDebugAndroidTestKotlin` y `:app:assemblePruebasDebug`: superados.

No se han ejecutado pruebas instrumentadas ni se ha probado Places real, porque la API aún no está
habilitada en la clave.

## Pendiente antes de dar por aceptada la issue

1. Paso humano en Google Cloud: habilitar **Places API (New)** y añadirla a las restricciones de API
   de la clave de `GOOGLE_MAPS_API`. Revisar la facturación y las cuotas
   ([casa-contrato.md](casa-contrato.md#places-en-google-cloud-paso-humano)).
2. Desplegar la migración `20261123003700_home_addresses.sql` en la base alojada, como paso revisado
   por David.
3. En un emulador desechable, nunca emulator-5554:
   1. Entrar con una cuenta sin casa. Debe aparecer «¿Dónde está tu casa?» antes del catálogo.
      Escribir «Calle Uría 10», elegir una sugerencia, añadir el piso y guardar.
   2. Repetir con Places sin habilitar o sin red. Debe aparecer el aviso de autocompletado no
      disponible. Guardar con «Marcar en el mapa» y con «Usar mi ubicación», escribiendo la
      dirección a mano.
   3. Abrir «Cerca de mí». Debe buscar desde casa sin pedir permiso. La elección manual de concejo
      y pin (#17) debe seguir funcionando.
   4. Con el emulador situado a más de 5 km (por ejemplo, Gijón con una casa en Oviedo), debe
      aparecer «Estás lejos de casa» una sola vez. Volver a entrar en la búsqueda no debe repetir el
      aviso. Al cerrar sesión y volver a entrar, debe aparecer de nuevo.
   5. Solicitar una cita. Dirección y punto deben venir rellenos con la casa, con el aviso, y deben
      poder editarse.
   6. Cambiar la dirección desde la cuenta.
   7. Revisar los textos en ES, EN, FR y DE en esas pantallas, incluido 320×640 dp.
