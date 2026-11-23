# Revisión de perfiles desde el panel privado — #29

Contrato del recorrido [Revisar perfiles desde el panel privado](https://github.com/DRChanca/teloarreglo/issues/29).
Lo amplían #30 (presentar y corregir perfiles) y #31 (incidencias en el panel).

## Interfaz compartida

Migración nueva: `supabase/migrations/20261122002900_profile_review.sql`. No modifica migraciones anteriores.

### Datos (todos en `private`, sin permisos para clientes)

- `private.profile_submissions`: versión presentada e **inmutable** del contenido público
  (`id` = clave de idempotencia, `version` creciente, `profile_id`, `content`, `status`
  `pending|approved|rejected`, `submitted_at`). Solo se permite el paso único
  `pending → approved|rejected` sin cambiar el contenido.
- `private.profile_decisions`: auditoría de solo inserción (envío, administrador, aprobado,
  motivo, fecha). Disparadores rechazan `update`, `delete` y `truncate` incluso al
  propietario de la tabla (`42501 audit_immutable`).
- `private.notices`: avisos persistidos por cuenta (`kind`, `source_id`, `payload`), únicos por
  `(kind, source_id, recipient_id)`. #32 entregará push a partir de aquí; push no es la única copia.
- `private.administrators` (ya existente): solo SQL de confianza. Ni registro, ni metadatos de
  Auth, ni ninguna RPC conceden administración.

### Contenido de una versión

Mismos campos públicos que `approved_profiles`: `name`, `description`, `trades[]`, `services[]`,
`coverage[{code,name}]`, `languages[]`, `phone`, `images[]`, `prices[]`, `business_address`
(opcional). Cualquier otra clave (p. ej. `private_address`) se rechaza con `22023 invalid_profile_content`.

### Operaciones

| Operación | Quién | Resultado / errores |
| --- | --- | --- |
| `private.submit_profile_version(p_profile uuid, p_submission uuid, p_content jsonb)` | Solo SQL de confianza (sin `grant`). **#30 la envolverá en una RPC de propietario.** | Fila pendiente; repetir con mismos datos devuelve la misma; otra intención con la misma clave `22023 request_conflict`; perfil inexistente `22023 profile_not_found`. Marca `review_status='pending'`. |
| `public.admin_profile_submissions(p_status text = 'pending')` | `authenticated` administrador | Array: `submission_id, profile_id, version, status, submitted_at, reason, decided_at, published, latest, content, decision{approved,reason,decided_at,administrator_email}`. `p_status`: `pending/approved/rejected/all`. Nunca propietario ni domicilio. Omite perfiles del propio administrador. No administrador `42501 permission_denied`. |
| `public.review_professional_profile(p_submission uuid, p_approve boolean, p_reason text)` | `authenticated` administrador, nunca sobre su propio perfil | Motivo obligatorio (1–2000) `22023 reason_required`; inexistente `22023 submission_not_found`; versión desactualizada (hay otra posterior) `40001 profile_changed`; decisión ya registrada distinta `22023 review_already_recorded`; reintento idéntico devuelve el resultado sin duplicar. Aprobar copia **exactamente** el contenido presentado a `approved_profiles` (conserva `rating`, `review_count`, `cover_path`); rechazar no toca el catálogo. Registra decisión y aviso `profile_review` al propietario. |
| `public.my_profile_review()` | `authenticated` propietario | `null` sin perfil; si no hay versiones `{profile_id, status:null, published}`; si no, `submission_id, profile_id, version, status, submitted_at, reason, decided_at, published`. |
| `public.my_notices()` | `authenticated` | Últimos 100 avisos propios: `id, kind, payload, created_at`. |

`published` indica si hay una versión aprobada visible, independiente de un cambio pendiente o
rechazado. Pendientes y rechazados nunca llegan a `approved_profiles`, y por tanto tampoco a
`catalogue()`, `search_candidates()` ni `count_candidates()`.

### Fronteras de cliente

- Android: `catalogue/ProfileReviews.kt` (`ProfileReviews.mine()`, `ReviewSummary`) y
  `ProfileReviewCard.kt` en la pantalla de cuenta. Strings `profile_review_*`.
- Panel: `panel/src/api.ts` (`AdminApi`, errores estables `Failure`). La interfaz no decide permisos:
  una cuenta no administradora no carga datos y, aunque llamase a la RPC, el servidor la rechaza.

## Panel web

React 18 + TypeScript + Vite, estático. Solo usa la clave publicable y la sesión del propio
administrador (correo y contraseña de Supabase Auth). No contiene clave de servicio ni secretos.

```bash
cd panel && npm ci && npm run check      # tsc + vitest
npm run build                            # dist/ (requiere variables públicas)
```

Variables de compilación (públicas): `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`
(`sb_publishable_…`). Sin ellas el panel muestra «falta la configuración».
`public/_headers` añade `noindex`, CSP con `connect-src` a `*.supabase.co` y prohíbe iframes.

## Preparación alojada (pasos humanos, revisados)

**No se ha desplegado ni escrito en la base alojada.** Cuando David lo decida:

1. Aplicar la migración `20261122002900_profile_review.sql` con el procedimiento revisado del README.
2. Crear (o elegir) dos cuentas **verificadas y distintas**: una administradora, sin perfil
   profesional, y otra propietaria del perfil ficticio, sin perfil previo.
3. Generar y revisar el SQL; no conecta ni ejecuta nada:

   ```bash
   python3 scripts/prepare_profile_review.py --admin-email ADMIN --owner-email PROPIETARIO
   ```

   Concede administración por SQL de confianza y crea el perfil de prueba pendiente
   (`c1772900-0000-4000-8000-000000000029`). Repetirlo aborta la transacción.
   El perfil de prueba no tiene ubicación privada: tras aprobarse aparece en el catálogo por oficio,
   no en la búsqueda cercana hasta que se registre su ubicación con el procedimiento de #16.
4. Cloudflare Pages Free, mismo repositorio: proyecto nuevo con directorio raíz `panel`,
   comando `npm ci && npm run build`, salida `dist`, y las dos variables públicas anteriores.
   No se ha creado ninguna cuenta ni proyecto de Cloudflare desde este ticket.

## Ampliación #30: presentar, corregir y cambiar perfiles

Migración nueva: `supabase/migrations/20261123003000_profile_editing.sql`. No modifica las anteriores;
solo amplía `admin_profile_submissions` con `create or replace`.

| Operación | Quién | Resultado / errores |
| --- | --- | --- |
| `public.start_professional_profile()` | `authenticated` (cuenta verificada) | Crea el perfil propio si no existe (uno por cuenta, idempotente) y devuelve `my_professional_profile()`. La cuenta sigue pudiendo contratar. |
| `public.my_professional_profile()` | `authenticated` propietario | `null` sin perfil; si no, `profile_id, private_address, public` (versión aprobada o `null`), `editable` (última versión presentada; si no hay, la aprobada; si no, vacío) y `review` (= `my_profile_review()`). Otra cuenta nunca ve el perfil ajeno. |
| `public.submit_professional_profile(p_submission uuid, p_content jsonb)` | `authenticated` propietario; el perfil se deriva de la sesión | Envuelve `private.submit_profile_version`. Mismos campos públicos de #29 y además: nombre 1–120, oficio conocido (≥1), ≥1 concejo `{code 33NNN, name}`, ≥1 idioma `xx`, teléfono `+34 600 000 000`, dirección comercial opcional 1–240. Errores: `22023 invalid_profile_content`, `22023 profile_not_found` (sin perfil propio), `22023 request_conflict` (misma clave, otro contenido). Reintentar con la misma clave devuelve la misma versión. |
| `public.save_professional_private_address(p_address text)` | `authenticated` propietario | Guarda o borra (`''`/`null`) la dirección privada (≤240, `22023 invalid_private_address`). No crea versión ni revisión y nunca llega al catálogo ni al panel. |
| `public.admin_profile_submissions(...)` | administrador | Igual que #29 y además `public_content`: la versión aprobada actual (o `null`), para comparar un cambio con lo publicado. |

Reglas comprobadas: un cambio de un perfil aprobado queda pendiente y `approved_profiles` sigue con la
última versión aprobada; si se rechaza, permanece. Administración decide sobre la versión concreta y no la
reescribe. La agenda (`save_agenda`, bloqueos) no crea versiones. Revisar no borra conversaciones ni citas.

Fronteras de cliente:

- Android: `catalogue/ProfessionalProfiles.kt` (`ProfessionalProfiles`, `ProfileContent`, `OwnProfile`,
  `ProfileEditFailure` INVALID/CONFLICT/NO_PROFILE/ACCESS; el resto se relanza como incertidumbre de red) y
  `ProfileEditorScreen.kt`, abierto desde «Cuenta» («Ofrecer mis servicios» / «Editar mi perfil profesional»).
  La clave de envío se conserva mientras se reintenta el mismo contenido. Strings `profile_edit_*`.
- Panel: `Submission.public_content`; cada campo que cambia se marca «Cambia» junto al valor publicado.

## Ampliación del 23 de noviembre de 2026: servicios, precios y portada sin revisión

Decisión de David: servicios, precios orientativos y foto de portada los publica el propietario
directamente. El resto de campos sigue la revisión de #30. Migraciones nuevas
`20261123004100_profile_offer_direct.sql` y `20261123004200_direct_covers.sql` (portadas: ver
[portadas-y-diseno.md](portadas-y-diseno.md#publicación-directa-desde-el-23-de-noviembre-de-2026)).

- `private.professional_profiles` gana `services text[]` y `prices jsonb`: lo que publicó el propietario.
  `null` significa que aún no los gestiona (catálogo preparado, perfiles anteriores); entonces manda la
  instantánea aprobada. `start_professional_profile()` crea los perfiles nuevos con `{}`/`[]`.
- Disparador `approved_profiles_offer` (`before insert or update` en `approved_profiles`): si el perfil tiene
  `services`/`prices` propios, la instantánea pública siempre los toma. Aprobar una versión (también una antigua)
  nunca devuelve precios ni servicios anteriores. SQL de confianza que escriba precios en
  `approved_profiles` de un perfil gestionado por su propietario queda sustituido por lo del propietario.

| Operación | Quién | Resultado / errores |
| --- | --- | --- |
| `public.save_professional_offer(p_services jsonb, p_prices jsonb)` | `authenticated` propietario; el perfil sale de la sesión | Publica a la vez servicios y precios, sin revisión ni versión nueva: actualiza el perfil y, si está aprobado, `approved_profiles`. Devuelve `my_professional_profile()`. Servicios: ≤ 30 cadenas de 1–120. Precios: ≤ 20 objetos con exactamente `service` (1–120), `scope`, `includes`, `excludes` (≤ 300, pueden ir vacíos), `unit` (`hour`/`sqm`/`unit`/`job`) y `amount_cents` (entero 0–100 000 000). Errores: `22023 invalid_profile_offer`, `22023 profile_not_found` (sin perfil propio; también otra cuenta). Repetirla deja el mismo estado. |
| `public.submit_professional_profile(...)` | igual que #30 | Sustituye `services` y `prices` del contenido presentado por los publicados (o, si el propietario aún no los gestiona, por los aprobados): una versión presentada nunca cambia precios ni servicios. |
| `public.my_professional_profile()` | igual que #30 | `editable.services` y `editable.prices` son siempre los publicados por el propietario, si los gestiona. |

Las citas, solicitudes y propuestas ya existentes conservan su importe: ver
[citas-contrato.md](citas-contrato.md#precio-guardado-en-la-cita--decisión-del-23-de-noviembre-de-2026).

Fronteras de cliente:

- Android: `ProfessionalProfiles.saveOffer(services, prices)`; `ProfileContent.prices` es `List<ReferencePrice>`.
  `catalogue/ProfileOffer.kt` (`PriceDraft`, `parseServices`, `toPrices`: importe en euros con `parseEuroCents`).
  En `ProfileEditorScreen` la sección «Servicios y precios orientativos» tiene su propio botón «Guardar y
  publicar» y explica que no se revisan; el formulario revisado ya no incluye servicios. Botón «Cambiar foto de
  portada» abre el editor de portada también para perfiles sin aprobar. Strings `profile_offer_*`, `profile_edit_cover`.
- Panel: la tarjeta de revisión ya no muestra servicios ni precios; nunca mostró portadas.

## Fuera de alcance

Sin acceso general a chats, documentación real, sanciones, categorías ni estadísticas. El panel
solo lista versiones de perfil y decide sobre ellas.
