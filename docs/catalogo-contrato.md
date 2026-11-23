# Catálogo y perfiles públicos

Contrato del recorrido [Consultar catálogo y perfiles públicos](https://github.com/DRChanca/teloarreglo/issues/14).
Migración nueva: `20261120000100_catalogue.sql`. No modifica la migración de identidad.

## Interfaces compartidas

- `catalogue(p_trade text = null, p_id uuid = null)`: lectura sin registro,
  devuelve una lista de versiones aprobadas. Un ID no publicado devuelve `[]`.
  Oficios: `plumbing`, `electrical`, `carpentry`, `painting`, `masonry`, `general`, `cleaning`
  (Limpieza, migración aditiva `20261122003600_cleaning_trade.sql`, #36).
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
`rating`, `review_count`, `approved_at`, `business_address` (opcional).

`business_address` es una dirección **comercial pública aprobada**, separada
del domicilio privado. Si falta, no se muestra dirección ni se toma el domicilio
privado como alternativa. La migración aditiva `20261120000200` añade únicamente
esa columna; no modifica los datos preparados.

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
2. Revisar los 21 perfiles de `supabase/catalogue/profiles.json`. Son datos
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
5. Los otros 20 perfiles son explícitamente **sin reclamar** (`owner_id=null`),
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

## Ajustes de la revisión visual

David solicitó direcciones comerciales ficticias, una explicación de precios
más breve, ocultar idiomas si solo consta español y más referencias de servicios.

- El texto es «El precio final se acuerda al agendar la cita», conservando el
  encabezado «Precios orientativos». Traducciones ES/EN/FR/DE.
- Idiomas se muestra si hay alguno distinto de español; si aparece, enumera
  también español cuando corresponda.
- `supabase/catalogue/details.json` amplía los datos ficticios con dirección,
  visita de valoración y otro trabajo por perfil. No añade tipos rígidos de cita:
  una visita es otra referencia de servicio dentro de `prices`.
- Se incluyen visitas gratuitas y con coste; las de pago explican que se cobran
  aunque no haya reparación. Reparación, materiales y desplazamiento se indican
  aparte y requieren acuerdo previo.
- Las direcciones usan calles reales y **locales expresamente ficticios**, sin
  números de portal ni domicilios/personas/negocios reales asociados. La sede
  comercial no cambia la lista de concejos cubiertos.

Fuentes municipales consultadas el 20/11/2026 para nombres de calles (no para
inventar titularidades ni copiar direcciones de negocios):

- Oviedo: [Gascona](https://www.oviedo.es/fr/web/turismo/w/tierra-astur-gascona),
  [Uría](https://www.oviedo.es/en/web/turismo/-/fruela).
- Gijón: [itinerario modernista, Corrida y Cabrales](https://www.gijon.es/en/turismo/art-nouveau-gijonxixon),
  [compras, Los Moros](https://www.gijon.es/en/turismo/shopping).
- Avilés: [Galiana y Rivero](https://aviles.es/calle-galiana).

### Actualizar los perfiles ya cargados

**No repetir `prepare_catalogue.py` ni la migración inicial.**

```bash
python3 scripts/update_catalogue_details.py > /RUTA_LOCAL_REVISADA/actualizacion.sql
```

Este programa solo lee el catálogo público y genera SQL. Si falta la columna
comercial, incluye la migración `20261120000200` al principio, dentro de la misma
transacción que los cambios de contenido.

Se revisa y ejecuta manualmente en el mismo proyecto alojado. Solo toca los
21 identificadores preparados (cargar antes la limpieza, ver abajo): añade dirección, referencias de visita y otro
trabajo, y actualiza su fecha de aprobación. Conserva propietarios, domicilios
privados, nombres, descripciones, cobertura, imágenes, idiomas, teléfonos y
reseñas; conserva los servicios y precios existentes y no duplica los nuevos.
Si ya hay otra dirección comercial, falta un perfil o cambió su nombre, exige
revisión. Si cambian los campos afectados entre generar y ejecutar el SQL,
aborta toda la transacción, sin dejar actualizaciones parciales.

Después de ejecutarlo:

```bash
python3 scripts/contract_catalogue.py --details
```

La app nueva es compatible con el servidor anterior (dirección ausente). El texto
y los idiomas cambian al instalar la nueva APK; dirección y precios ampliados
necesitan además ejecutar esta actualización alojada.

### Añadir la limpieza a un catálogo ya cargado (#36)

Los tres perfiles de limpieza son los índices 18–20: los 18 UUID anteriores no
cambian. **No repetir la carga inicial.** Tras aplicar la migración
`20261122003600_cleaning_trade.sql` (y desplegar la Edge Function, que valida el
oficio), generar, revisar y ejecutar a mano en la base alojada:

```bash
python3 scripts/prepare_catalogue.py --only-cleaning > /RUTA_LOCAL/limpieza.sql
(cd scripts/search-data && node prepare_search_data.mjs --part locations --only-cleaning) > /RUTA_LOCAL/limpieza-ubicaciones.sql
```

Los tres quedan sin reclamar (`owner_id=null`), con dirección comercial, visita
y otro trabajo ya incluidos. Repetir cualquiera de los dos archivos causa
conflicto y aborta. Después, `python3 scripts/contract_catalogue.py` espera 21.

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
