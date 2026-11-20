# Catálogo y perfiles públicos

Contrato del recorrido [Consultar catálogo y perfiles públicos](https://github.com/DRChanca/teloarreglo/issues/14).
Migración nueva: `20261120000100_catalogue.sql`. No modifica la migración de identidad.

## Interfaces compartidas

- `catalogue(p_trade text = null, p_id uuid = null)`: lectura sin registro,
  devuelve una lista de versiones aprobadas. Un ID no publicado devuelve `[]`.
  Oficios: `plumbing`, `electrical`, `carpentry`, `painting`, `masonry`, `general`.
  Ambos filtros son opcionales y se combinan.
- `public.approved_profiles`: solo la última versión aprobada, lectura con RLS
  para visitantes y cuentas. Ningún cliente tiene permisos de escritura.
  No contiene propietario, domicilio, coordenadas ni datos de clientes.
- `private.professional_profiles`: propiedad, domicilio privado, borrador y estado
  de revisión. No accesible directamente a clientes.
- `save_professional_draft(p_profile_id uuid, p_draft jsonb)`: cuenta verificada
  propietaria exclusivamente; marca pendiente y nunca publica. No es todavía
  el editor de perfiles ni el flujo administrativo de revisión.
- `current_account()`: conserva los campos anteriores y añade `can_hire` y
  `professional_profile_id` (UUID o `null`). La relación con Auth es privada y
  única por cuenta, no procede de metadatos editables.

Android comparte un único cliente Supabase para identidad y catálogo.
`Catalogue.list(trade)` y `Catalogue.profile(id)` devuelven datos públicos o
propagan el error; un fallo no se convierte en lista vacía.

### Forma pública

`profile_id`, `name`, `description`, `trades[]`, `services[]`,
`coverage[{code,name}]`, `languages[]`, `phone`, `images[]`,
`prices[{service,scope,unit,amount_cents,includes,excludes}]`,
`rating`, `review_count`, `approved_at`.

Los códigos de concejo son INE como texto. Los importes son céntimos EUR;
unidades `hour`, `sqm`, `unit`, `job`. Todos estos precios son referencias
orientativas, nunca propuestas aceptadas. Los contenidos de profesionales
permanecen en su idioma original; etiquetas ES/EN/FR/DE.

`images` contiene claves de ilustraciones originales incluidas en la app,
una por oficio. No son fotos de trabajos reales y su descripción accesible lo
explica. No requiere almacenamiento externo, licencias de terceros ni URLs
privadas. `rating=null, review_count=0` significa sin valoraciones; no se
inventan reseñas para rellenar el catálogo.

## Preparación explícita, sin reinicios

**Primero revisar la migración y coordinarla con los tickets consumidores**
(búsqueda, conversación, administración). El futuro panel publicará una
instantánea tras revisar una versión concreta; esta migración no le concede
todavía permisos ni implementa su operación de aprobación.

1. Aplicar la migración nueva con el procedimiento revisado del README.
   Si la identidad se aplicó por SQL Editor, reconciliar antes su historial;
   no repetir la migración inicial.
2. Revisar los 18 perfiles de `supabase/catalogue/profiles.json`. Son datos
   ficticios con tres perfiles por oficio. No usan domicilios reales ni teléfonos
   de terceros: los números `+34 000…` son deliberadamente inválidos.
3. Elegir una cuenta **existente y verificada** para Fontanería Nalón:

   ```bash
   python3 scripts/prepare_catalogue.py --owner-email CUENTA_VERIFICADA
   ```

4. Revisar el SQL impreso y sus datos antes de ejecutarlo en la única base.
   El programa **no conecta ni ejecuta nada**. Es una carga inicial transaccional,
   no un seed de arranque ni una migración automática. Repetirla causa conflicto
   y aborta toda la transacción: nunca borra ni sobrescribe datos ya preparados.
5. Los otros 17 perfiles son explícitamente **sin reclamar** (`owner_id=null`),
   no cuentas Auth simuladas. Si deben participar en otros recorridos, asociar
   cada uno a una cuenta verificada distinta mediante SQL de confianza revisado.
   No se crean cuentas ni contraseñas automáticamente en el proyecto alojado.
   La cuenta principal puede tanto contratar como actuar como profesional; usar
   otra cuenta verificada distinta como cliente del recorrido entre personas.
6. Ejecutar la comprobación alojada de solo lectura:

   ```bash
   python3 scripts/contract_catalogue.py
   ```

No hay carga desde la app, despliegue automático, `db reset` ni borrado de
presentación. Las pruebas de escritura utilizan exclusivamente loopback y UUIDs
aleatorios propios que retiran al terminar; no tocan fixtures preparados.

## Comprobación

```bash
supabase migration up --local
supabase test db
python3 scripts/contract_catalogue.py --local
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial EMULADOR_DESECHABLE
```

No ejecutar las pruebas instrumentadas en emuladores con sesiones que conservar:
Gradle instala/desinstala la app de prueba. Resultados y límites de evidencia:
[comprobación del catálogo](comprobacion-catalogo.md).
