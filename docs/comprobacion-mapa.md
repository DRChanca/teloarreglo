# Comprobación del mapa y la ubicación manual — #17

Fecha: 23 de noviembre de 2026. Contrato: [mapa-contrato.md](mapa-contrato.md).

No se ha desplegado ni escrito en la base alojada. No se ha creado ni modificado ninguna clave de
Google ni activado facturación.

## Ejecutado localmente

| Suite | Resultado |
| --- | --- |
| pgTAP `supabase/tests/map_locations.test.sql` (pila local, `db reset` + `test db`) | 11/11; suite completa 214/214 (tras unir `spec/11-demo` 6219a65) |
| `python3 scripts/contract_map.py --local` (PostgREST real, clave publicable sin sesión) | 3/3 |
| `MapLocationTest` (JVM; RPC con `MockEngine`, lógica de mapa y navegación) | 7/7 |
| Pruebas unitarias JVM completas (`:app:testPruebasDebugUnitTest`) | 84/84 |
| `python3 scripts/android.py check` (compilación, pruebas, lint con traducciones) | correcto |
| `:app:compilePruebasDebugAndroidTestKotlin` (nuevas pruebas de interfaz) | compila; **no ejecutadas** |

Qué cubren:

- Punto comercial público solo con dirección comercial aprobada; medio punto, rangos imposibles y
  punto sin dirección rechazados; cambiar la dirección borra el punto anterior; sin campo de
  dirección privada en el catálogo.
- `concejo_anchors()` para visitantes y cuentas, ordenado por nombre, con el punto dentro de su
  concejo y sin límite; `concejos.boundary` y `professional_locations` siguen cerrados (HTTP real).
- Coherencia: el mapa usa los mismos candidatos y orden que la lista; los perfiles sin punto público
  se cuentan y quedan en la lista.
- Navegación externa sin origen y sin abrir nada con coordenadas imposibles.
- Sin clave o sin Google Play services el mapa no se crea.
- Sin pin (sin mapa) se busca desde el punto interior del concejo elegido.
- `NearbySearchUiTest` (pendiente de emulador): permiso denegado → panel manual → concejo → búsqueda
  con ese `ine_code` y su punto; vista de mapa sin clave → aviso y misma lista con selección de perfil.

## Pendiente antes de dar por aceptada la issue

1. Revisar la clave en Google Cloud según [mapa-contrato.md](mapa-contrato.md#clave-de-google-maps)
   (paquetes + SHA-1, solo Maps SDK for Android, facturación revisada).
2. Ejecutar `python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE` en un emulador
   desechable asignado (nunca emulator-5554).
3. En un emulador con Google Play y la clave: búsqueda por GPS → «Mapa» → teselas visibles, punto de
   búsqueda azul, marcadores de perfiles con punto comercial y apertura de la ficha desde la etiqueta.
4. Permiso denegado y posición imprecisa → elección manual con pin en el mapa; trabajo en otro concejo.
5. Cita: «Elegir el punto en el mapa» rellena las coordenadas; la dirección sigue siendo obligatoria.
   «Abrir el punto en Google Maps» desde la cita y «Cómo llegar» desde una ficha con punto comercial.
6. Sin Google Maps ni navegador (o deshabilitados): aviso «No hay ninguna aplicación…», sin falso éxito.
7. Revisar pantallas y errores en ES/EN/FR/DE a 320×640 dp.
8. Cargar, tras revisión, los puntos comerciales de los perfiles preparados (ver «Datos de
   demostración» en el contrato) y desplegar la migración en la base alojada como paso revisado.
