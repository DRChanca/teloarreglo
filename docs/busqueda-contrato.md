# Búsqueda por distancia con zona de trabajo informativa

Ticket: [Encontrar profesionales por GPS, cobertura y proximidad](https://github.com/DRChanca/teloarreglo/issues/16).
Rama: `feat/16-busqueda-gps` (desde `develop`). Responsable: Marcos.
Estado (21/11): **servidor desplegado y probado en la base compartida; pantalla Android pendiente.**

**Actualización del 25-11-2026** ([ADR 0001](adr/0001-zona-de-trabajo-informa.md), migración
`20261125000100_work_zone_informs.sql`): la zona de trabajo (`coverage`) ya no filtra. La búsqueda devuelve a
todos los perfiles aprobados del oficio por distancia, con `in_zone` en cada resultado y `method: "distance"`.
Es la única búsqueda de la app (pestaña «Buscar»); la pantalla «Cerca de mí» desaparece. Lo que sigue describe
el contrato vigente; la versión con cobertura y anillos H3 se conserva en «Historia».

## Interfaces

| Pieza | Quién la usa | Qué hace |
| --- | --- | --- |
| `public.concejos(ine_code, name, boundary)` | visitantes leen `ine_code` y `name` | Límites oficiales del IGN. La geometría no es legible directamente. |
| `resolve_concejo(p_lat, p_lng, p_tolerance_m = 250)` | visitantes y cuentas | Concejos que contienen o rozan el punto, `distance_m` 0 = dentro. Más de uno = frontera o posición incierta: confirmar. |
| `private.professional_locations(profile_id, location, h3_cell)` | nadie fuera del servidor | Punto registrado del profesional y su celda H3 (resolución 6; la búsqueda ya no la usa). |
| `search_all_candidates(p_ine_code, p_trade)` | solo `service_role` | Todos los perfiles aprobados del oficio (`null` = cualquiera) con ubicación registrada: ubicación exacta, `in_zone` (su zona incluye el concejo) y `featured`. |
| Edge Function `search-professionals` | app (clave publicable) | Búsqueda completa; respuesta sin coordenadas ni distancias exactas. |

`search_candidates` / `count_candidates` / `featured_candidates` (filtradas por cobertura) siguen en la base,
pero la Edge Function ya no las llama.

### Petición y respuesta

```text
POST /functions/v1/search-professionals
{ lat, lng, trade?, ine_code?, limit? (1–50, def. 20; la app pide 50) }

200 { status: "ok", concejo: {ine_code, name}, method: "distance",
      results: [{ profile_id, distance_band_m, featured, in_zone }] }
200 { status: "confirm_concejo", options: [{ine_code, name}], results: [] }
200 { status: "outside_area", results: [] }
400 { error: "invalid_input" | "unknown_concejo" }
503 { error: "service_unavailable" }      # nunca una lista vacía falsa
```

- `lat, lng` = punto de búsqueda: la casa por defecto; el GPS (una vez) o un concejo/chincheta solo si la persona lo elige.
- `ine_code` en la petición = concejo confirmado por el usuario o elegido a mano (pin manual).
- `featured` solo es `true` si además `in_zone` es `true`.
- Los perfiles se piden después con `catalogue(null, profile_id)`, que ya existe.

## Reglas

1. **Todos por distancia** (25-11-2026): perfiles aprobados del oficio con ubicación registrada, trabajen o no en
   el concejo del punto de búsqueda. La zona de trabajo solo informa: `in_zone: false` → la tarjeta dice
   «No suele trabajar en {concejo}».
2. **Distancia geográfica** (haversine) a la ubicación registrada, no por carretera ni seguimiento en directo.
   Empates por `profile_id`.
3. **Orden completo, sin H3**: con pocos perfiles se ordena la lista entera, exacta y barata. El algoritmo por
   anillos (`searchByRings`) sigue en `search.ts` y en sus pruebas, sin uso.
4. **Privacidad**: la distancia mostrada se redondea hacia arriba a tramos de 500 m (`distance_band_m`), para que no se pueda triangular el domicilio buscando desde varios puntos. Nunca se devuelven coordenadas ni celdas.
5. **Fronteras**: si el punto está a menos de 250 m de otro concejo, la función no adivina: devuelve `confirm_concejo`.
6. **Destacado** (#33; 25-11-2026): primero los perfiles con suscripción simulada activa cuya zona incluye el
   concejo (`featured: true`, entre ellos por distancia); después todos los demás por distancia. Un destacado
   fuera de su zona va mezclado con el resto y sin resalte (`featured: false`). Ver `suscripcion-contrato.md`.
   El catálogo general por oficio (`catalogue`) también pone primero a los destacados y devuelve `featured` (24-11-2026, ver `suscripcion-contrato.md`).

### Android (25-11-2026)

- Esqueleto mientras carga. Si la búsqueda falla, la lista queda en el orden del catálogo con «No hemos podido
  ordenar por cercanía · Reintentar».
- Visitante sin sesión: orden del catálogo con «Ordenar por cercanía» (GPS o concejo a elección).
- Los perfiles del catálogo sin ubicación registrada se añaden al final, sin distancia.
- El filtro de texto se aplica en el dispositivo sobre la lista ordenada; el mapa opcional muestra la misma lista.
- Lejos de casa (> 5 km): comprobación en segundo plano y banner no bloqueante una vez por sesión.

### Sesión de búsqueda de la app (25-11-2026, #57)

- El estado de la búsqueda (punto de búsqueda, oficio, resultados o fallo) vive en una sesión con alcance de
  aplicación (`AppSearchSession`), junto a la sesión de la casa, mientras vive el proceso; la pantalla lo observa
  en lugar de crear el suyo. Sin reintentos automáticos ni cachés persistentes.
- **Precarga**: en cuanto la identidad está conectada y la casa está presente, la sesión lanza la búsqueda por
  defecto (todos los oficios, punto de búsqueda = casa, sin GPS), normalmente durante la animación de inicio.
  Mientras se comprueba la sesión (cada vuelta del segundo plano) no arranca ni olvida nada. No hay precarga para
  visitantes, cuentas sin casa ni administradores.
- **Entre pestañas**: salir de «Buscar» y volver conserva la búsqueda y el oficio elegido; la pantalla no repite
  la búsqueda ni muestra esqueletos si ya hay resultados, y un fallo sigue con su «Reintentar» sin reintento
  automático. El GPS y la petición de permiso pertenecen a la pantalla: si se sale durante la localización, se
  cancela y la búsqueda vuelve al estado que tenía.
- **Cierre de sesión o cambio de cuenta**: la búsqueda y el oficio se olvidan; la cuenta nueva empieza de cero.

### Filtros en el dispositivo (25-11-2026, #52)

La Edge Function, el SQL y el límite de resultados no cambian. La app filtra la lista que ya tiene, en el orden
recibido (destacados de la zona primero, el resto por distancia), con una función pura; filtrar solo quita
perfiles, así que no hace falta pedir más:

1. **Oficio**: es el `trade` de la petición (chips y hoja comparten el estado).
2. **Distancia máxima** (2 · 5 · 10 · 25 · 50 km o sin límite): pasa si `distance_band_m ≤ límite × 1000`, es
   decir, se compara el tramo redondeado que muestra la tarjeta (4,7 km → 5000, pasa 5 km; 5,2 km → 5500, no).
   Se aplica también a los destacados. Un perfil sin tramo (sin ubicación registrada) no pasa con un límite
   activo. Sin punto de búsqueda no hay tramos y el límite no se puede elegir.
3. **Valoración mínima** (3★ o 4★): pasa si `rating ≥ mínima` (del catálogo); sin reseñas no pasa con un mínimo
   activo. No depende del punto de búsqueda.
4. **Texto**: el de siempre, después de los anteriores.

La privacidad no cambia: la app solo conoce el tramo, nunca coordenadas ni distancias exactas. La zona de trabajo
sigue sin excluir (ADR 0001); quien excluye es la persona al elegir un filtro.

## Historia

Hasta el 25-11-2026 la regla 1 era «**Cobertura primero**: solo perfiles aprobados del oficio cuyo `coverage`
incluye el concejo del trabajo» y la regla 3 «**H3 organiza, no decide el orden**: anillos desde la celda del
trabajo, por lotes (0, 1, 2–3, 4–7…), parando solo cuando ninguna celda sin explorar puede contener a alguien
más cerca que el último de la página; tope de 1000 celdas y, si se agota, orden de la lista de cobertura completa
(`method: "coverage"`)». La respuesta era `method: "h3" | "coverage"` y `results: [{ profile_id,
distance_band_m, featured }]`, con candidatos de `search_candidates(p_ine_code, p_trade, p_cells)`. Las
comprobaciones del 21/11 de más abajo corresponden a esa versión.

## Datos de concejos

`scripts/fetch_concejos.py` descarga los 78 concejos del WFS INSPIRE «Unidades administrativas» del IGN
(`au:nationalCode` `34033333*`, nivel municipal) en WGS84 y escribe
`supabase/geo/concejos_asturias.geojson` con el código INE como texto y coordenadas a 6 decimales (≈ 10 cm).
Licencia **CC BY 4.0, © Instituto Geográfico Nacional de España**; fuente y fecha en `metadata` del archivo.
Los 18 concejos de la cobertura de los perfiles ficticios existen y coinciden en nombre.

## Preparación de datos (sin reinicios)

`scripts/search-data/prepare_search_data.mjs` **solo imprime SQL**; no conecta ni ejecuta nada.

```bash
cd scripts/search-data && npm ci
node prepare_search_data.mjs --part concejos  > /RUTA_LOCAL/concejos.sql    # ~1,9 MB, demasiado para el editor
node prepare_search_data.mjs --part locations > /RUTA_LOCAL/ubicaciones.sql
# Para el editor SQL de Supabase: partes de ≤ 200 KB, en orden, y la comprobación al final.
node prepare_search_data.mjs --out-dir ../../supabase/.temp/carga --max-kb 200
```

- **Concejos**: los 78 del GeoJSON del IGN; comprueba al final que hay exactamente 78.
- **Ubicaciones** de los perfiles preparados (18, más 3 de limpieza con `--only-cleaning`, #36), con los **mismos UUID** que `scripts/prepare_catalogue.py`
  (uuid5, comprobado que coinciden). Punto **ficticio** determinista a menos de 800 m del centro de la ciudad
  de su dirección comercial ficticia (Oviedo, Gijón o Avilés), dentro de ese concejo. No son domicilios.
  Celda H3 resolución 6 calculada con h3-js, igual que la Edge Function.
- Cada parte es una transacción: aborta sin escribir nada si los ID/nombres no coinciden con el catálogo
  publicado, si un punto queda fuera de su concejo o si ya existía (conflicto de clave). Nunca borra ni
  sobrescribe. Orden: primero concejos, después ubicaciones; ambos tras aplicar la migración.
- Revisar el SQL antes de ejecutarlo en la única base compartida, como el resto de cargas de David.

## Comprobación hecha (21/11)

- Algoritmo de `search.ts` contra fuerza bruta (filtrar y ordenar todo): **1080 búsquedas idénticas**
  (18/300/3000 profesionales, páginas de 1/10/50, con tope de celdas y sin él), 895 resueltas por anillos.
  Base: la prueba técnica de `spikes/h3-busqueda` (bordes de celda, empates, cobertura).
- `supabase/tests/search_location.test.sql`: 18 comprobaciones (concejo interior, frontera, fuera de Asturias,
  entrada inválida, permisos de visitantes/cuentas/servidor, filtros). **Se ejecutarán en la CI de la PR**:
  en este portátil no hay Docker.

## Pendiente

- [x] Generador del SQL de carga (ver «Preparación de datos»).
- [x] Migración `20261121000100_search_location.sql` **aplicada el 21/11 en la base compartida** desde el
      editor SQL (en una transacción), por Marcos. Comprobado con la clave publicable: `concejos` legible solo
      en código y nombre (geometría 42501), `resolve_concejo` responde (vacío hasta cargar concejos; entrada
      inválida 22023), `search_candidates`/`count_candidates` denegadas a visitantes (42501) y el catálogo
      sigue con sus 18 perfiles. **No está registrada en el historial de migraciones**, igual que las de David:
      reconciliar antes de usar `supabase db push`.
- [x] Datos cargados el 21/11 desde el editor SQL. El editor rechaza consultas grandes («Query is too
      large»), así que los concejos se generaron con `--out-dir … --max-kb 200` en 11 partes + comprobación.
      Resultado: **78 concejos**, iguales al GeoJSON; Catedral de Oviedo→Oviedo, Avilés→Avilés,
      Covadonga→Cangas de Onís, playa de San Lorenzo→Gijón (a 24 m, dentro de la tolerancia), Madrid→ninguno.
      **18 ubicaciones** en 5 celdas H3, ninguna sin perfil público; `search_candidates('33044','plumbing')`
      devuelve Fontanería Nalón, el único fontanero que cubre Oviedo. El editor avisó de la tabla temporal
      sin RLS: se ejecutó tal cual («Run without RLS») porque existe solo dentro de la transacción.
- [x] Edge Function desplegada el 21/11 (CLI 3.2.0, `functions deploy search-professionals
      --remote=ipqydkulietvxdbdlkcv --use-api --yes`, sesión de CLI de Marcos). `npm:h3-js@4.1.0` funciona en
      Edge Functions. El proyecto usa las claves nuevas: la función toma `SUPABASE_SECRET_KEYS` y, si no,
      `SUPABASE_SERVICE_ROLE_KEY`, y registra en el servidor el código de error (nunca claves ni coordenadas).
- [x] Corrección `20261121000200_search_resolve_service_role.sql` (aplicada el 21/11 desde el editor):
      `service_role` necesitaba ejecutar `resolve_concejo` y leer código y nombre de `concejos`
      (fallaba con 42501). Tests añadidos para ambos casos.
- [x] Prueba real con la clave publicable (21/11):

      | Caso | Resultado |
      | --- | --- |
      | Fontanero, catedral de Oviedo | ok, Oviedo, Fontanería Nalón (1500 m) |
      | Electricista, Oviedo / Pintor, Gijón | Luz de Oviedo (1000 m) / Brocha del Mar (1000 m) |
      | Cualquier oficio, Langreo | 3 resultados ordenados (14,5–15 km) |
      | Frontera Oviedo/Llanera | `confirm_concejo` con Oviedo y Llanera |
      | Confirmado Llanera | ok, Llanera, 2 resultados |
      | Concejo manual 33044, pin lejos | ok, Oviedo, Madera Naranco |
      | Concejo inexistente / Madrid | 400 `unknown_concejo` / `outside_area` |
      | Entrada inválida / GET | 400 / 405 |

      Respuesta sin coordenadas ni celdas; distancias en múltiplos de 500 m.
- [ ] Pantalla Android: permiso GPS puntual, lista con carga/vacío/error y ES/EN/FR/DE.
- [ ] Ejecutar las pruebas del algoritmo en la CI (hoy solo en local con Node).
