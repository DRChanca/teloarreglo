# Comprobación de agenda sin horario, «Destacado» visible y vídeos del chat — #11 (#21–#23, #33, #19)

Fecha: 23 de noviembre de 2026.
Contratos: [citas-contrato.md](citas-contrato.md#agenda-sin-horario-y-comprobación-al-proponer-corrección-23-de-noviembre-de-2026),
[suscripcion-contrato.md](suscripcion-contrato.md#destacado-visible-para-clientes-23-de-noviembre-de-2026),
[busqueda-contrato.md](busqueda-contrato.md).

> Nota del 25-11-2026: registro histórico. La búsqueda ya no usa `featured_candidates` ni filtra por zona: solo los
> destacados cuya zona incluye el concejo van primero ([ADR 0001](adr/0001-zona-de-trabajo-informa.md), [búsqueda](busqueda-contrato.md)).

No se ha desplegado ni escrito en la base alojada. Las migraciones `20261123012610_agenda_open_hours.sql` y
`20261123012620_featured_profiles.sql` y la Edge Function `search-professionals` modificada solo se han probado en la
pila Supabase local (rama `fix/agenda-destacado` con `spec/11-demo` fusionada, incluidas #26 y #32).

## Errores reportados

1. **«Pagar 100,00 € y confirmar» → «Esa hora ya no está libre en la agenda del profesional».** El diagnóstico de
   solo lectura en la base alojada mostró que el perfil no tenía ninguna franja en `availability_windows` ni reservas:
   la comprobación de franja fallaba siempre y el cliente lo descubría al pagar. Corrección: sin franjas no se limita
   la hora; la hora se comprueba ya al proponer (y al proponer un cambio de hora); al confirmar se distingue
   `outside_hours` de `slot_unavailable`.
2. **Suscripción simulada activa sin «Destacado» visible.** Antes solo había una vista previa en la tarjeta de la
   suscripción. Ahora la búsqueda cercana coloca primero a los destacados en los concejos de su cobertura aprobada,
   con insignia en lista y mapa, y el perfil público muestra la insignia.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/agenda_destacado.test.sql` | 21/21 | Sin franjas: el profesional propone a cualquier hora futura y el cliente paga y confirma (el caso del error); propuesta sobre una cita confirmada → `slot_unavailable` al enviarla, sin dejar fila; con franjas, propuesta fuera → `outside_hours` al enviarla; bloqueo posterior a la propuesta → `slot_unavailable` al confirmar; horario cambiado tras la propuesta → `outside_hours` al confirmar; cambio (#23) a hora fuera de horario rechazado al proponerlo y dentro aceptado; `is_featured` para anónimos solo con suscripción activa y versión aprobada; `featured_candidates` solo `service_role`, solo en concejos de su cobertura y de su oficio; cancelar la simulación quita el «Destacado» |
| pgTAP suite completa | 791/791 en 30 archivos | `agenda.test.sql` (41) y `appointment_changes.test.sql` (46) actualizados: lo que antes se rechazaba al confirmar ahora se rechaza al proponer, y las carreras se prueban con un bloqueo tomado entre propuesta y confirmación |
| `contract_*.py --local` (21 scripts) | todos exit 0 | `contract_agenda.py` actualizado: propuesta fuera de horario → 409 al proponer; bloqueo tomado después de proponer → 409 al aceptar y 200 tras quitarlo |
| `contract_chat_videos.py --local` | 3 pruebas OK | Nueva: el profesional envía un MP4 real, el cliente lo ve en la conversación, obtiene URL firmada y descarga los mismos bytes; un tercero no obtiene enlace. Las existentes: cliente → profesional, límites de 20 MiB y MP4 en Storage, sin enlace público ni para terceros/administración |
| `deno test supabase/functions/search-professionals/` | 3/3 | `featuredFirst`: destacados primero por distancia, sin duplicados, límite común, orden normal sin suscriptores. `deno check index.ts` OK |
| Unitarias Android (`pruebas`) | 195/195 | `AppointmentsTest`: `outside_hours` → `OUTSIDE_HOURS` en confirmación y cambios; `ProfessionalSearchTest`: `featured` y orden del servidor; `MapLocationTest`: marcadores destacados; `CatalogueTest`: `rpc/is_featured` con `p_profile_id` |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 6 claves nuevas en ES/EN/FR/DE |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | Los dobles de prueba siguen compilando (`Catalogue.featured` tiene valor por defecto) |

MockEngine y los dobles comprueban la app, no el recorrido de extremo a extremo; la evidencia contra servidor real
es pgTAP y los contratos HTTP locales. La Edge Function no se ha servido localmente: su lógica de orden está probada
con `deno test` y la regla de datos con pgTAP.

## No ejecutado

- Pruebas instrumentadas y reproducción de vídeo en emulador: requieren un emulador desechable; no se ha usado
  emulator-5554. La reproducción Android usa la misma URL firmada que descarga el contrato (`ExoPlayer` sobre HTTPS).
- Nada contra la base alojada.

## Pendiente antes de dar por aceptado

1. Desplegar (paso revisado por David) las migraciones `20261123012610` y `20261123012620` y la Edge Function
   `search-professionals`. Sin la función redeplegada, la app sigue funcionando pero sin «Destacado» en la búsqueda.
2. Base alojada, solo lectura: comprobar que el perfil suscrito tiene versión aprobada con cobertura, p. ej.
   `select jsonb_array_length(coverage) from public.approved_profiles where profile_id = '<perfil>';`. Si no hay fila
   o la cobertura está vacía, el «Destacado» no aparecerá por regla (no se relaja).
3. Con dos emuladores desechables: el cliente paga la propuesta del 25-11-2026 09:00 de un profesional sin horario →
   se confirma; el profesional guarda un horario y propone fuera de él → ve «fuera de tu horario» sin enviar nada;
   el cliente busca en un concejo cubierto por el suscriptor → aparece primero con «Destacado» en lista, mapa y perfil.
4. Vídeo de 40 s en ambos sentidos en emulador: enviar, abrir y reproducir. Si falla en la base alojada, verificar que
   está desplegada `20261123001900_chat_videos.sql` (amplía el bucket a 20 MiB y `video/mp4`).
5. Revisión ES/EN/FR/DE de los textos nuevos (`appointment_error_hours`, `agenda_send_error_*`, `agenda_no_hours`).
