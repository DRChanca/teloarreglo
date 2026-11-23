# Suscripción simulada — #33

Escena de la suscripción opcional de 5 €/mes (historias 73–75). Es una propuesta comercial
simulada: no hay compra, renovación, facturación ni datos bancarios, y el perfil gratuito conserva
todo lo que tenía.

## Interfaz compartida

Migración: `supabase/migrations/20261122003300_subscription_simulation.sql`.

- `private.subscription_simulations(profile_id, active, updated_at)`: sin permisos para `anon` ni
  `authenticated`. No tiene columnas de tarjeta, banco, factura, importe cobrado ni renovación; una
  prueba pgTAP (`columns_are`) lo fija.
- `public.my_subscription() → jsonb | null` (solo `authenticated`). Devuelve `null` si la cuenta no
  tiene perfil profesional. Campos: `profile_id`, `price_cents` (500, entero en céntimos),
  `currency` (`EUR`), `period` (`month`), `simulated` (bool), `charged` (siempre `false`),
  `featured_zones` (cobertura `[{code,name}]` de la última versión **aprobada**, o `[]`).
- `public.simulate_subscription(p_active boolean) → jsonb` (solo `authenticated`). Mismo resultado.
  Es idempotente y nunca cobra. Errores: `42501 permission_denied` sin sesión,
  `42501 professional_profile_required` sin perfil profesional, `22023 invalid_choice` si
  `p_active` es nulo.

Qué NO se concede ni se cambia:

- La simulación no modifica `private.professional_profiles` (estado de revisión), `public.approved_profiles`
  (valoración, reseñas, cobertura) ni los datos de búsqueda. La suscripción no compra verificación.
- «Destacado» no se inserta en los resultados de la búsqueda cercana ni del catálogo: la búsqueda por
  proximidad no cambia. La escena muestra una vista previa etiquetada y las zonas donde podría
  aparecer, derivadas de la cobertura aprobada en el servidor. Un perfil pendiente o rechazado
  devuelve `featured_zones = []`, de modo que pagar no publica un perfil sin revisar.
- Si un ticket futuro muestra «Destacado» en resultados reales, debe leer `featured_zones` (o la misma
  regla) y mantener la etiqueta visible; no se promete posición fija ni clientes.

Frontera Kotlin: `es.teloarreglo.subscription.Subscriptions` (`mine()`, `simulate(active)`),
implementada por `SupabaseSubscriptions`. Fallos: `SubscriptionFailure.ACCESS` (401/403) frente a
`UNCONFIRMED` (cualquier otro error del servidor; la interfaz mantiene el último estado confirmado).
Interfaz: `SubscriptionCard` en la pantalla «Cuenta» de una cuenta con perfil profesional.
Textos: claves `subscription_*` en ES/EN/FR/DE.

## «Destacado» visible para clientes (23 de noviembre de 2026)

Sustituye la segunda viñeta de «Qué NO se concede»: con la suscripción simulada activa, el «Destacado»
ya aparece en resultados reales. Migración `supabase/migrations/20261123012620_featured_profiles.sql`.

- Regla única (`private.is_featured`): suscripción simulada activa **y** versión pública aprobada en
  `approved_profiles` con cobertura no vacía; es la misma fuente que `featured_zones`. Un perfil sin
  versión aprobada nunca es destacado aunque se suscriba.
- `public.is_featured(p_profile_id uuid) → boolean` (`anon`, `authenticated`): solo la insignia del perfil.
- `public.featured_candidates(p_ine_code, p_trade)` (solo `service_role`): subconjunto destacado de
  `search_candidates`, por tanto solo en concejos de su cobertura aprobada y de su oficio.
- Búsqueda cercana: la Edge Function coloca primero los destacados del concejo (entre ellos por distancia),
  después el resto por proximidad, sin repetir perfiles y con el mismo límite de página; cada resultado
  lleva `featured`. La distancia mostrada no cambia. No se promete posición fija ni clientes.
- Android: insignia «Destacado» (`FeaturedBadge`) en la lista de cercanos, título y color del marcador del
  mapa, y cabecera del perfil público (`Catalogue.featured(id)`; si falla, no se muestra). El catálogo
  general por oficio (sin zona) no muestra insignia, porque no hay concejo con el que aplicar la regla.
