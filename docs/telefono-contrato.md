# Teléfono de contacto — #74

Número privado y obligatorio de la cuenta (spec [#71](https://github.com/DRChanca/teloarreglo/issues/71);
vocabulario «Teléfono de contacto» en `GLOSSARY.md`). Tiene la misma protección que la casa (#37):
solo su propietario lo lee y lo escribe. Ninguna búsqueda, chat, solicitud, catálogo ni vista pública
lo expone. Solo el profesional de una cita confirmada lo recibirá, a través de la función de la
dirección exacta de la cita (#79).

## Interfaz compartida

Migración nueva: `20261126071101_contact_phones.sql`. No modifica migraciones anteriores.

### Tabla `public.contact_phones` (una fila por cuenta)

| Columna | Tipo | Notas |
| --- | --- | --- |
| `user_id` | `uuid` PK → `auth.users` | `on delete cascade` |
| `phone` | `text` | E.164: `^\+[1-9][0-9]{7,14}$` |
| `updated_at` | `timestamptz` | hora del servidor |

RLS: `authenticated` solo lee su propia fila. Sin `INSERT`, `UPDATE` ni `DELETE` directos. `anon` no
tiene ningún permiso.

### Formato aceptado

Se ignoran espacios, guiones, puntos y paréntesis. Después:

- 9 dígitos empezando por 6, 7, 8 o 9 → número español, se guarda con `+34`
  (`612 34 56 78` → `+34612345678`).
- `+` seguido de 8 a 15 dígitos, sin 0 tras el `+` → internacional, se guarda tal cual
  (`+1 (415) 555-2671` → `+14155552671`).
- Cualquier otra cosa se rechaza: letras, longitud incorrecta, número sin `+` que no tiene 9 dígitos,
  `+` en medio, vacío.

La misma regla está en el servidor (`private.normalize_contact_phone`) y en la app
(`normalizeContactPhone`). Sin verificación por SMS.

### RPC (`security definer`, actor `auth.uid()`, solo `authenticated`)

| Operación | Resultado / errores |
| --- | --- |
| `my_contact_phone()` | `text` en E.164, o `null` si la cuenta no tiene teléfono (la app lo pide). `28000 authentication_required` sin sesión |
| `set_contact_phone(p_phone text)` | Crea o sustituye el teléfono propio y lo devuelve en E.164. `22023 invalid_contact_phone` si el formato no vale. `28000 authentication_required` sin sesión |

`anon` recibe `42501` en las dos RPC y en la tabla.

## Android

- `identity/ContactPhones.kt` (reutilizable por #78 y #79):
  - `normalizeContactPhone(raw): String?` aplica la regla anterior.
  - `ContactPhones` / `SupabaseContactPhones`: `current()` y `save(phone)`. Un número mal escrito se
    rechaza antes de llamar al servidor.
  - `ContactPhoneFailure`: `ACCESS` o `INVALID`. Los fallos de red se relanzan tal cual.
  - `ContactPhoneSession`: estados `Unknown`, `Missing`, `Present(phone)` y `Failed`, como
    `HomeSession`. `phone` da el número vigente. Solo un `Missing` confirmado por el servidor obliga a
    darlo; un fallo de red no bloquea y se reintenta en `onStart`.
- `ContactPhoneScreen.kt`:
  - `ContactPhoneField`: campo «Teléfono de contacto» con el error debajo, para el paso y para Ajustes
    (#78).
  - `saveContactPhone(session, typed)`: guarda y devuelve el texto de error que mostrar.
  - `ContactPhoneScreen`: tercer paso obligatorio tras el nombre y la casa
    (`Navigation.screen` → `MainScreen.PHONE_STEP`), con el texto «Los profesionales con los que
    confirmes una cita usarán este número para contactarte». No se puede saltar; se puede cerrar
    sesión. Las cuentas anteriores lo ven al entrar.
  - `ContactPhoneEditor` / `ContactPhoneCard` (#78): el teléfono en Ajustes › Cuenta, bajo el nombre.
    Usa `saveContactPhone`, así que guardar actualiza la `ContactPhoneSession` y el paso obligatorio
    no vuelve a pedirse.
- Textos: `contact_phone_*` en ES, EN, FR y DE.

## Pruebas

- pgTAP: `supabase/tests/contact_phones.test.sql`.
- Kotlin: `ContactPhoneEditorTest` (edición en Ajustes, #78), `ContactPhoneTest` (formato, RPC, sesión y orden de los pasos) y `ContactPhoneUiTest`
  (pantalla del paso y tarjeta de Ajustes).
