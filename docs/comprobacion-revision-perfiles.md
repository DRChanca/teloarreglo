# Comprobación de la revisión de perfiles — #29

Fecha: 23 de noviembre de 2026. Contrato: [revision-perfiles-contrato.md](revision-perfiles-contrato.md).

## Ejecutado localmente

- **pgTAP** `supabase/tests/profile_review.test.sql`: 39 comprobaciones, todas correctas (suite completa
  112/112 tras `supabase db reset --local`). Cubre: visitante y cuenta normal sin acceso a lista ni
  decisión aunque sus metadatos digan «admin»; nadie se inserta en `private.administrators`; tablas
  privadas ilegibles; propietario no se autoaprueba; motivo obligatorio; rechazo y reintento
  idempotente; decisión ejecutada no modificable; pendiente/rechazado fuera de `catalogue()`;
  aviso persistido sin duplicar; versión corregida aprobada publica exactamente lo presentado sin
  tocar el domicilio privado; versión desactualizada `profile_changed` con la aprobada aún visible;
  auditoría no editable ni borrable ni siquiera como `postgres`.
- **Contrato HTTP** `python3 scripts/contract_profile_review.py --local`: OK (1 recorrido). Ejecuta el
  SQL impreso por `prepare_profile_review.py` con cuentas desechables en loopback; Auth y PostgREST
  reales. Cuenta normal y propietario reciben 401/403; editar metadatos no concede administración;
  **el adaptador real del panel** (`panel/src/review.local.test.ts`, 2 pruebas) rechaza y luego aprueba;
  el propietario lee motivo, fecha y aviso por las mismas RPC que usa Android; el catálogo público
  solo muestra la versión aprobada; `PATCH` directo rechazado. Limpia sus fixtures al terminar.
- **Panel** `cd panel && npm run check`: `tsc` sin errores y 10 pruebas Vitest (2 omitidas fuera del
  contrato local): sin datos para cuenta no administradora, entrada, lista con contenido, motivo
  obligatorio, rechazo de la versión concreta, error del servidor sin confirmación falsa, cambio a
  alemán sin traducir el contenido, historial con administrador y motivo, mismas claves en ES/EN/FR/DE,
  ruta y cuerpo de la RPC. `npm run build` genera `dist/` (156 kB JS) sin secretos.
- **Android** `python3 scripts/android.py check` (pruebas y presentación): compilación, lint y 38 pruebas
  unitarias, 5 nuevas en `ProfileReviewTest` (motivo/fecha de rechazo, perfil preparado publicado,
  cuenta sin perfil, fallo de servicio no confundido con «sin revisión», resumen visible/no visible).
- `ProfileReviewUiTest` (instrumentada) compila; **no ejecutada**: no se asignó emulador desechable.

## Pendiente antes de dar por aceptada la issue

1. Aplicar la migración y el SQL de `prepare_profile_review.py` en la base alojada (pasos del contrato).
2. Crear el proyecto Cloudflare Pages Free con las variables públicas y desplegar el panel.
3. Recorrido web→Android con cuentas reales: en el panel, como administrador, rechazar el perfil de
   prueba con motivo; en un emulador desechable, con la cuenta propietaria, abrir «Cuenta» y ver
   «Rechazado», fecha y motivo. Después presentar una versión corregida (SQL de confianza hasta #30),
   aprobarla y comprobar en Android «Aprobado y publicado» y el perfil en el catálogo.
4. Con una tercera cuenta normal, abrir el panel desplegado: debe mostrar «sin acceso administrativo».
5. Revisar textos del panel y de la tarjeta Android en ES/EN/FR/DE y ejecutar `ProfileReviewUiTest` y
   `LanguageTest` en un emulador desechable asignado.

No se ha desplegado ni escrito en la base alojada, ni se ha creado ninguna cuenta o proyecto de
Cloudflare. Los resultados anteriores son locales y no constituyen evidencia de aceptación alojada.
