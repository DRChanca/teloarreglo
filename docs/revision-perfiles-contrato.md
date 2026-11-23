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

## Fuera de alcance

Sin acceso general a chats, documentación real, sanciones, categorías ni estadísticas. El panel
solo lista versiones de perfil y decide sobre ellas.
