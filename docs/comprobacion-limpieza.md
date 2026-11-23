# Comprobación de la categoría Limpieza — #36

Fecha: 23 de noviembre de 2026. Rama: `ticket/36`, sobre `spec/11-demo`.
Ticket: [Incluir limpiadoras](https://github.com/DRChanca/teloarreglo/issues/36),
sub-issue de la especificación [#11](https://github.com/DRChanca/teloarreglo/issues/11).

## Alcance

- Nuevo oficio `cleaning`, etiqueta inclusiva «Limpieza» (EN Cleaning, FR Nettoyage,
  DE Reinigung), en el catálogo, la búsqueda cercana, la ilustración de portada y la
  validación de la Edge Function `search-professionals`.
- Migración aditiva `20261122003600_cleaning_trade.sql`: amplía el `check` de
  `approved_profiles.trades`; las filas existentes siguen siendo válidas.
- Tres perfiles ficticios aprobados y sin reclamar: Limpiezas del Naranco (Oviedo),
  Hogar Limpio Gijón y Limpieza Ría de Avilés. Mismo formato que los anteriores:
  teléfonos `+34 000…` inválidos, locales ficticios, precios orientativos y visita.
- **Cambio sobre #11:** la especificación pedía 6 oficios × 3 = 18 perfiles; ahora
  son 7 × 3 = **21**. Actualizados `especificacion.md`, `catalogo-contrato.md` y
  `busqueda-contrato.md`. Los 18 UUID anteriores no cambian (índices 0–17).
- La app no tiene todavía editor de oficios del perfil profesional; el panel
  muestra la clave `cleaning` como los demás oficios.

## Ejecutado localmente

- pgTAP (`db-check.sh`, reinicio local + `supabase test db`): todas las pruebas
  correctas, incluidas las 3 nuevas de `cleaning_trade.test.sql` (oficio aceptado,
  filtro `catalogue('cleaning')`, oficio desconocido rechazado). Fallaban antes de
  la migración.
- `contract_catalogue.py --local`: 7 casos correctos; espera 21 perfiles y 3 por
  oficio. El caso nuevo simula la base con solo 18, carga `--only-cleaning` y
  comprueba que una segunda carga aborta.
- Búsqueda en local: concejos + perfiles de limpieza + ubicaciones
  `--only-cleaning`; `count_candidates('33024','cleaning')` = 1.
- `python3 scripts/android.py check`: compilación, 72 tests JVM (incluye
  `TradesTest`: Limpieza se ofrece con etiqueta propia y todas las etiquetas son
  distintas) y lint de traducciones correctos.
- No se han ejecutado pruebas instrumentadas ni en emulador.

## Pendiente antes de dar por aceptada la issue

1. David revisa y aplica la migración `20261122003600` en la base alojada y
   despliega de nuevo `search-professionals`.
2. Generar, revisar y ejecutar a mano los SQL de `--only-cleaning` (ver
   [catálogo](catalogo-contrato.md#añadir-la-limpieza-a-un-catálogo-ya-cargado-36)),
   y después `python3 scripts/contract_catalogue.py` (solo lectura).
3. En un emulador: filtrar por Limpieza en catálogo y búsqueda cercana, abrir un
   perfil y revisar la etiqueta e ilustración en ES/EN/FR/DE.

No se ha desplegado ni escrito en la base alojada.
