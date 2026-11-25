# Dirección de casa — #37

## Interfaz compartida

Migración nueva: `20261123003700_home_addresses.sql`.
No modifica migraciones anteriores ni carga, borra o reinicia datos preparados.
No se ha desplegado ni escrito en la base alojada.

### Tabla `public.home_addresses` (una fila por cuenta)

| Columna | Tipo | Notas |
| --- | --- | --- |
| `user_id` | `uuid` PK → `auth.users` | `on delete cascade` |
| `address` | `text` | 1–300 caracteres, sin espacios a los lados |
| `lat`, `lng` | `double precision` | WGS84, rangos válidos |
| `ine_code` | `text` → `public.concejos` | concejo que contiene el punto (o a ≤ 250 m); `null` fuera de los concejos cargados |
| `source` | `text` | `places`, `pin` o `gps` |
| `place_id` | `text` | solo con `source = 'places'` (restricción `home_addresses_place_id_source_check`) |
| `updated_at` | `timestamptz` | hora del servidor |

Las coordenadas son `lat`/`lng` en `double precision`, como `approved_profiles.business_lat/lng`
(#17) y `appointments.latitude/longitude` (#20). Para PostGIS:
`extensions.st_setsrid(extensions.st_makepoint(lng, lat), 4326)::extensions.geography`.

RLS: `authenticated` solo puede leer su fila (`user_id = auth.uid()`). Sin `INSERT`, `UPDATE` ni
`DELETE` para las cuentas de la app. `anon` no tiene ningún permiso. No hay excepción administrativa,
y ninguna vista pública, ni el catálogo, la búsqueda o la Edge Function leen esta tabla.

### RPC (`security definer`, actor `auth.uid()`, solo `authenticated`)

- `home_address()` → 0 o 1 filas `address, lat, lng, ine_code, concejo_name, source, place_id, updated_at`.
- `set_home_address(p_address text, p_lat double precision, p_lng double precision, p_source text,
  p_place_id text default null)` → la misma fila. Crea o sustituye la casa. El concejo lo calcula el
  servidor a partir del punto, nunca a partir del texto ni del cliente. Con `pin` o `gps` se descarta
  `place_id`. Repetir la misma llamada es inocuo.

| HTTP | SQLSTATE | `message` | Android (`HomeFailure`) |
| --- | --- | --- | --- |
| 401 | 28000 | `authentication_required` | `ACCESS` |
| 401/403 | 42501 | sin permiso (`anon`) | `ACCESS` |
| 400 | 22023 | `invalid_home_address` | `INVALID` |

Los fallos de red se propagan sin traducir: la pantalla conserva lo escrito y permite reintentar.

### Para #38 (círculo de la cita)

Leer la casa del cliente con `home_address()` desde su propia sesión, o dentro de una función
`security definer` que justifique el acceso. La cita guarda su propia copia de dirección y punto
(`appointments.address/latitude/longitude`). Si se cambia la casa después, la cita no cambia. El
profesional no puede leer `home_addresses`. Si #38 necesita mostrarle un círculo aproximado, debe
derivarlo de los datos de la cita, que el cliente ya ha compartido, o añadir una función explícita
que solo devuelva el centro difuminado.

## Android

Paquete `es.teloarreglo.home`:

- `HomeAddresses` / `SupabaseHomeAddresses`: `current()` y `save(HomeDraft)`.
- `HomeSession`: estado `Unknown | Missing | Present | Failed`. Solo `Missing`, confirmado por el
  servidor, hace obligatorio el paso. Un fallo de red no bloquea la app y se reintenta en `onStart`.
  `askIfAway(point)` indica si hay que preguntar: más de 5 km (`AWAY_FROM_HOME_M`, distancia
  haversine) y una sola vez por sesión. Se reinicia al cambiar de cuenta o cerrar sesión.
- `AddressAutocomplete`: frontera de Places. La implementación real es `GooglePlacesAutocomplete`
  (Places SDK 4.4.1, `initializeWithNewPlacesApiEnabled`, país ES y sesgo a Asturias, un token de
  sesión por dirección elegida). `AddressLookup` la envuelve: menos de 3 caracteres no llaman a
  Places. Tras el primer fallo (sin clave, API no habilitada, cuota o red) queda no disponible para
  el resto de la pantalla y se ofrecen pin, GPS y texto manual.

Pantallas (`HomeAddressScreen.kt`):

- `HomeAddressScreen`: obligatoria tras entrar si no hay casa, con opción de cerrar sesión, y
  editable desde la cuenta. Con Places: sugerencias y dirección editable, por ejemplo para añadir el
  piso. Sin Places: aviso, «Usar mi ubicación» (un solo punto, se rechaza si es impreciso),
  «Marcar en el mapa» si hay mapa y la dirección escrita a mano.
- `HomeSummaryCard` en la cuenta (`AccessScreen(homeSlot=…)`).
- `AwayFromHomeDialog`: «Buscar desde aquí», «Cambiar dirección» o «Seguir con casa». Sustituido el 25-11-2026:
  banner no bloqueante, una vez por sesión, con la comprobación en segundo plano.

Integración:

- Búsqueda cercana (`NearbySearchScreen(home, askIfAway, onChangeHome)`; desde el 25-11-2026 la pantalla «Cerca de mí»
  desaparece y esto lo hace la búsqueda única de la pestaña «Buscar», ver [búsqueda](busqueda-contrato.md)): busca desde casa al abrir
  (`{lat, lng, ine_code}` de la casa), sin pedir el GPS. Solo si el permiso ya estaba concedido toma
  un punto en silencio para el aviso de lejanía. «Usar mi ubicación» y la elección manual de #17
  siguen funcionando.
- Cita (`PinSource.home`): el formulario de solicitud empieza con la dirección y el punto de casa,
  que se pueden editar, y muestra un texto que lo explica.

## Ampliación #41: campos españoles, sugerencias tras 7 caracteres y sin coordenadas

Migración nueva: `20261124000100_home_address_parts.sql` (aditiva; no se ha aplicado en la base alojada).

- `home_addresses.address_parts jsonb` (opcional): `street` (obligatorio, ≤ 160), `number`, `block`, `portal`,
  `staircase`, `floor`, `door` (≤ 20 cada uno) y `locality` (código postal y localidad, ≤ 100). Se guardan sin
  espacios a los lados y sin campos vacíos. Otra clave o un valor que no sea texto → `22023 invalid_home_address`.
- `set_home_address(..., p_parts jsonb default null)`: la llamada antigua de cinco argumentos sigue funcionando y
  guarda `address_parts = null`. `home_address()` y `set_home_address` devuelven además `address_parts`.
- `address` sigue siendo la línea legible que usan la búsqueda, la cuenta y la solicitud de cita. Android la
  compone con `AddressParts.text()`: «Calle Uría 10, bloque 2, portal 3, esc. A, 4º B, 33003 Oviedo, Asturias».

Android (`home/AddressParts.kt`, `HomeAddressScreen`):

- El campo «Calle» es también la búsqueda de Places: las sugerencias empiezan con más de 7 caracteres
  (`MIN_ADDRESS_QUERY = 8`). Al elegir una, Places (campo `addressComponents`, mismo grupo Essentials que
  `formattedAddress`) rellena calle, número y localidad sin repetir el número.
- Debajo: Número, Bloque, Portal, Escalera, Piso y Puerta, y «Código postal y localidad». Solo la calle es obligatoria.
  Una casa guardada antes abre con toda su dirección en «Calle».
- La pantalla ya no muestra coordenadas: indica en palabras de dónde sale la ubicación (dirección elegida, mapa o
  GPS). Lo mismo en la solicitud y el cambio de dirección de una cita (`PointFromLine`); allí los campos de latitud
  y longitud solo aparecen como alternativa cuando no hay mapa. El detalle de la cita y la búsqueda manual dejan de
  mostrar coordenadas.
- 24-11-2026 (David): la pantalla de casa deja de mostrar la línea «Ubicación: …» con el origen del punto; solo avisa
  cuando falta (`home_point_missing`). Se mantienen «Usar mi ubicación» y «Marcar en el mapa». La solicitud de cita
  (`PointFromLine`) no cambia.

## Places en Google Cloud (paso humano)

La clave es la misma `GOOGLE_MAPS_API` de `.env` (→ `BuildConfig.MAPS_API_KEY`). Nunca se escribe en
Git ni en documentos. Pasos pendientes, sin cambios hechos por agentes:

1. APIs y servicios → Biblioteca → habilitar **Places API (New)** en el proyecto de la clave.
2. Credenciales → esa clave → «Restricciones de API»: añadir **Places API (New)** junto a Maps SDK
   for Android. Las restricciones de aplicación Android (paquetes `.pruebas` y `.presentacion` con
   el SHA-1 de `app/debug.keystore`) también valen para el SDK de Places.
3. Places es de pago por uso (Autocomplete por sesión + Place Details con campos
   `id, formattedAddress, location`). Revisar cuotas y alerta de presupuesto antes de la
   presentación. No se ha activado ni cambiado la facturación.
4. Hasta completar los pasos 1 y 2, la app muestra el aviso «El autocompletado de direcciones no
   está disponible» y permite el pin o el GPS con el texto escrito a mano.

## Comprobación reproducible

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_home.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.HomeAddressTest' --tests 'es.teloarreglo.HomeSessionTest' --tests 'es.teloarreglo.AddressLookupTest'
python3 scripts/android.py check
```

Resultados: [comprobación](comprobacion-casa.md).
