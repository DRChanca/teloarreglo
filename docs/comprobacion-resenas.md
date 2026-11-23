# Comprobación de reseñas — #28

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#reseñas--28).

No se ha desplegado ni escrito en la base alojada. La migración `20261123012800_reviews.sql` solo se ha aplicado a la pila
Supabase local (rama `ticket/28` sobre `spec/11-demo`, con #32 y el arreglo de agenda/«Destacado» ya integrados). La Edge Function `search-professionals` no se
ha modificado. El dinero es simulado: publicar una reseña no confirma la cita ni mueve saldo.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/reviews.test.sql` | 45/45 (suite completa: 836/836 en 31 archivos) | Cierre sin respuesta por la invariante del reloj (`complete_appointment(…, 'no_answer')`): ninguna reseña automática. Terceros, cita inexistente y el propio profesional → `permission_denied`. Sin aviso de datos privados → `privacy_not_acknowledged`; 0 o 6 estrellas, comentario vacío o > 2000 → `invalid_review`. Solo `started` → `not_done`; `done` sin confirmar → `awaiting_confirmation`. Confirmada con pago integrado: publica (comentario recortado, perfil correcto); misma clave → misma reseña; misma clave con otro contenido → `retry_conflict`; otra clave → `review_exists`; una sola reseña y un solo aviso `review_published` al profesional. Pago directo y gratuita cerrada por silencio: reseñables. Queja de calidad abierta en `done`: reseñable y la cita sigue `done` con el pago retenido. Disputa `not_performed`: `performance_disputed`; resuelta con `missing_evidence` sigue pendiente; con `performed` se habilita; otra resuelta `not_performed` → `not_performed` (aunque el dinero se hubiera devuelto). `review_states`: estado por cita para el cliente, la reseña visible para el profesional, nada para terceros. Sin INSERT/UPDATE/DELETE directos; ni el SQL de confianza reescribe (`review_locked`). `public.reviews` no tiene cita ni autor; `anon` lee exactamente 5 columnas; `private.review_sources` sin permisos; agregado del perfil `3.00/5` a partir de las 5 reseñas reales. Anónimo no publica ni consulta estados |
| `python3 scripts/contract_reviews.py --local` | 3 pruebas OK | Auth y PostgREST reales con tres cuentas desechables. Antes de confirmar → 409; tras confirmar, sin aviso → 400; anónimo, tercero y profesional rechazados. Dos publicaciones simultáneas con claves distintas → una 200 y otra 409; reintento con la clave ganadora devuelve la misma reseña; otra clave → 409; la misma clave con otro contenido → 400. Lectura anónima del perfil: solo `id, profile_id, stars, comment, created_at`; `review_count` coincide; `review_sources` inaccesible; PATCH/DELETE rechazados a cliente y profesional. Queja de calidad → publica; disputa del profesional (pago directo) → 409 hasta la decisión `performed` (simulada con SQL, #31 pendiente) → publica. Gratuita cerrada por el reloj → `enabled`, sin reseña previa, publica |
| Resto de contratos `--local` | OK los 22 `contract_*.py` (exit 0 cada uno) | `profile_editing` y `profile_review` requieren `npm ci` en `panel/` (adaptador del panel con vitest) |
| `ReviewsTest` (MockEngine) | 4/4 | Cuerpo de `publish_review` (siempre con el aviso aceptado), errores estables (`ACCESS`, `ALREADY_REVIEWED`, `PERFORMANCE_DISPUTED`, `NOT_PERFORMED`, `NOT_ENABLED`, `RETRY_CONFLICT`, `INVALID`) separados de la incertidumbre de red, `review_states` por cita, lectura pública del perfil ordenada por fecha |
| `NoticesTest` | +1 aserción | `review_published` → `NoticeTopic.REVIEW` |
| Unitarias Android | 199/199 en `pruebas` | |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 20 claves `review_*` en ES/EN/FR/DE (`values*/reviews.xml`) |
| `:app:compilePruebasDebugAndroidTestKotlin` | OK (solo compilación) | `ReviewsUiTest`: el cliente no ve «Escribir una reseña» como profesional; publica estrellas y comentario solo tras aceptar el aviso; una respuesta perdida bloquea el contenido y el reintento no duplica; el profesional ve la reseña; una disputa muestra el motivo en vez del botón; el perfil lista la reseña sin la dirección; el aviso de privacidad existe en los cuatro idiomas |

MockEngine y los servicios simulados comprueban la app, no el recorrido de extremo a extremo; la evidencia contra servidor
real es pgTAP y el contrato HTTP local.

## No ejecutado

- `ReviewsUiTest` en emulador: requiere un emulador desechable; no se ha usado emulator-5554.
- Decisión administrativa real (#31): aquí existe la columna `incidents.performance_decision` y la regla que la respeta; las
  pruebas la fijan con SQL de confianza. El panel no se ha modificado.
- Reloj (#27): la integración se ha comprobado llamando a la invariante compartida con fechas controladas, no con Cron.
- Respuesta pública del profesional, denuncia de reseñas y fotos en reseñas (especificación §11): fuera del alcance de #28.

## Pendiente antes de dar por aceptada la issue

1. Desplegar la migración en la base alojada como paso revisado por David.
2. Con dos emuladores: completar una cita (confirmación del cliente). En la tarjeta, el cliente pulsa «Escribir una reseña»,
   elige estrellas, escribe el comentario, lee el aviso de contenido público, marca «Entiendo que la reseña será pública» y
   publica. Ambas cuentas ven la reseña; el profesional recibe el aviso «El cliente publicó una reseña».
3. Abrir el perfil público sin sesión: aparece el agregado actualizado y la reseña (estrellas, comentario, fecha), sin nombre
   del cliente, dirección ni chat.
4. En otra cita marcada como realizada, el cliente comunica una queja de calidad y puede reseñar sin confirmar; en otra, una
   disputa sobre si ocurrió muestra «La reseña espera a que administración revise si el servicio se realizó».
5. Repetir los pasos 2–4 en ES, EN, FR y DE.
