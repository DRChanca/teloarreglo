# Mapa y ubicación manual — #17

## Interfaz compartida

Migración nueva: `20261122001700_map_locations.sql` (bloque reservado de #17).
No modifica migraciones anteriores ni carga, borra o reinicia datos preparados.
No se ha desplegado ni escrito en la base alojada.

- `public.approved_profiles.business_lat`, `business_lng` (nulos por defecto): punto de la
  **dirección comercial aprobada**. Restricción `approved_profiles_business_point_check`: los dos o
  ninguno, rangos válidos y solo con `business_address` no nula. Si cambia la dirección aprobada sin
  cambiar el punto, el disparador `approved_profiles_drop_stale_business_point` lo borra para no
  señalar un lugar equivocado. `catalogue()` los devuelve sin cambios (`select p.*`).
  Ninguna vía de la app los escribe; se rellenan en una actualización de contenido revisada, igual
  que `business_address`. La revisión de perfiles (#29) no los toca.
- `public.concejo_anchors()` → `ine_code`, `name`, `lat`, `lng` (`st_pointonsurface`, siempre dentro
  del concejo), ordenado por nombre. `security definer`; `anon` y `authenticated` pueden ejecutarla.
  El límite (`boundary`) sigue sin ser legible.
- Sin cambios en la Edge Function `search-professionals`: la elección manual envía
  `{lat, lng, ine_code}` (pin del mapa o, sin mapa, el punto interior del concejo). El servidor sigue
  aplicando cobertura y orden.

Kotlin (`es.teloarreglo.search.MapLocation.kt`): `GeoPoint`, `ConcejoAnchor`, `ConcejoDirectory`
(`SupabaseConcejoDirectory`), `PublicProfile.publicLocation()`, `mapView(rows)` (marcadores con el
mismo orden que la lista y recuento de los que solo están en la lista), `mapAvailability`,
`externalMapsUri`. Composables en `NearbyMap.kt`: `ResultsMap`, `MapPointPicker`,
`MapUnavailableNotice`, `openExternalMaps`, `deviceMapAvailability`.
`PinSource` (citas, #20) admite `mapsReady`; con mapa, el formulario de cita ofrece «Elegir el punto
en el mapa» y conserva las coordenadas escritas como alternativa.

## Reglas de privacidad

1. El mapa solo muestra el punto comercial aprobado. La ubicación de búsqueda del profesional
   (`private.professional_locations`) nunca sale del servidor.
2. El punto desde el que busca el cliente solo se dibuja en su dispositivo y solo se envía a la
   búsqueda, como antes.
3. Navegación externa: `https://www.google.com/maps/dir/?api=1&destination=lat,lng`, sin `origin`.
   Destinos: punto comercial aprobado (ficha del perfil) y punto de una cita, que el cliente comparte
   con ese profesional al solicitarla. Buscar o abrir un chat no revela la dirección del cliente.
4. El pin no sustituye la dirección: la cita exige calle y número escritos aparte.
5. No se usan geocodificación ni rutas internas. Mapa sin map ID. Places (New) solo se usa para el
   autocompletado de la dirección de casa (#37, [casa-contrato.md](casa-contrato.md)).

## Clave de Google Maps

Gradle lee `GOOGLE_MAPS_API` de `.env` (o del entorno o `local.properties`), como el resto de
configuración pública, y la pone en el manifiesto (`com.google.android.geo.API_KEY`) y en
`BuildConfig.MAPS_API_KEY`. Una clave de Maps para Android viaja en el APK por diseño; su
protección son las restricciones en Google Cloud. Nunca se escribe en Git ni en documentos.
Si está vacía, o el dispositivo no tiene Google Play services, el mapa no se crea: se explica con un
texto y la lista y la elección manual (concejo sin pin) siguen funcionando.

Comprobación pendiente en Google Cloud (humana, sin cambiar nada desde los agentes):

1. Credenciales → la clave usada en `GOOGLE_MAPS_API` → «Restricciones de aplicaciones»:
   «Aplicaciones para Android», con una entrada por variante: `es.teloarreglo.pruebas` y
   `es.teloarreglo.presentacion`, ambas con el SHA-1 de `app/debug.keystore`
   (`keytool -list -v -keystore app/debug.keystore -alias androiddebugkey -storepass android`).
   Añadir el SHA-1 de una firma de publicación solo si algún día existe.
2. «Restricciones de API»: **Maps SDK for Android** y, desde #37, **Places API (New)**.
   Geocoding, Directions y Routes deben quedar fuera.
3. Facturación: revisar explícitamente la cuenta de facturación, las cuotas y una alerta de
   presupuesto antes de la presentación. Maps SDK para Android con mapas sin map ID no genera cargos
   por carga de mapa en móvil, pero la cuenta de facturación es obligatoria.
4. Cargar con la app de pruebas en un emulador desechable y comprobar que se ven teselas; si no,
   `adb logcat | grep -i "Google Maps Android API"` muestra el motivo (sin imprimir la clave).

## Datos de demostración

Los 18 perfiles preparados tienen `business_address` pero aún no `business_lat/lng`: hasta una
actualización de contenido revisada por David, el mapa muestra el punto de búsqueda y el texto
«Ningún resultado tiene ubicación comercial pública», y la lista sigue completa. La actualización
consiste en un `update public.approved_profiles set business_lat = …, business_lng = … where
profile_id = …` por perfil, con coordenadas elegidas a mano sobre la calle comercial ficticia (sin
geocodificar), revisado antes de ejecutarlo en la base compartida.

## Comprobación reproducible

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> python3 scripts/contract_map.py --local
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.MapLocationTest'
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE   # NearbySearchUiTest incluye la elección manual
```

Resultados: [comprobación](comprobacion-mapa.md).
