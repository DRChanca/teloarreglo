# Nombre de la persona — #40

Es el nombre de la persona que usa la cuenta, no el nombre comercial de un perfil profesional
(`approved_profiles.name`, que se edita en #30). Es la única fuente de `client_name` en la bandeja de
chats (#39). El correo nunca se usa como nombre.

## Interfaz compartida

Migración nueva: `20261123004000_account_names.sql`. No modifica migraciones anteriores ni borra
datos preparados. No se ha desplegado ni escrito en la base alojada.

### Tabla `public.account_names` (una fila por cuenta)

| Columna | Tipo | Notas |
| --- | --- | --- |
| `user_id` | `uuid` PK → `auth.users` | `on delete cascade` |
| `name` | `text` | 1–80 caracteres, sin espacios a los lados |
| `updated_at` | `timestamptz` | hora del servidor |

RLS: `authenticated` solo lee su propia fila. Sin `INSERT`, `UPDATE` ni `DELETE` directos. `anon` no
tiene ningún permiso. Otra cuenta solo ve el nombre a través de `conversation_inbox()`, y solo si
comparte una conversación con esa persona.

### Relleno automático (trigger `seed_account_name` en `auth.users`)

- Al crear o actualizar metadatos de una cuenta se toma `full_name` o, si no hay, `name` de
  `raw_user_meta_data`. Se recortan los espacios y se corta a 80 caracteres.
- Registro con correo: la app envía `data = {"full_name": nombre}` en `signUp`.
- Google: GoTrue escribe `full_name`/`name` desde el token, así que no se pregunta nada.
- Si la cuenta ya tiene nombre, **no se sobrescribe**. Un nuevo acceso con Google no deshace el
  cambio que hizo la persona.
- La migración rellena una sola vez las cuentas existentes cuyos metadatos ya tenían nombre.

### RPC (`security definer`, actor `auth.uid()`, solo `authenticated`)

| Operación | Resultado / errores |
| --- | --- |
| `my_account_name()` | `text` o `null` si la cuenta no tiene nombre |
| `set_account_name(p_name text)` | Crea o sustituye el nombre propio y lo devuelve recortado. `22023 invalid_account_name` (vacío o > 80). `28000 authentication_required` sin sesión verificada |
| `conversation_inbox()` | Igual que en #39, pero `client_name` sale de `account_names`. Vale `null` si el cliente no tiene nombre, y la app muestra entonces «Cliente · id» como último recurso |

## Android

- `identity/AccountNames.kt`:
  - `AccountNames` y `SupabaseAccountNames`.
  - `cleanAccountName` aplica la regla de 1 a 80 caracteres y recorta los espacios.
  - `AccountNameFailure`: `ACCESS` o `INVALID`. Los fallos de red se relanzan tal cual.
  - `AccountNameSession` tiene los estados `Unknown`, `Missing`, `Present` y `Failed`, como
    `HomeSession`. Solo un `Missing` confirmado por el servidor obliga a dar el nombre. Un fallo
    de red no bloquea y se reintenta en `onStart`.
- `Identity.signUp(email, password, name)`: sin un nombre válido devuelve `AccessError.NAME` y no
  llama a Auth.
- `AccountNameScreen.kt`:
  - `AccountNameField` es el campo «Nombre» del registro.
  - `AccountNameScreen` es el paso obligatorio tras entrar, antes de la dirección de casa (#37).
    Se puede cerrar sesión desde él.
  - `AccountNameCard` aparece en «Cuenta» (`AccessScreen(nameSlot=…)`) para cambiar el nombre.
- Textos: `account_name_*` en ES, EN, FR y DE.

Comprobación: [comprobacion-nombre.md](comprobacion-nombre.md).
