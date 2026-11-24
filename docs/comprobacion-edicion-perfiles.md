# Comprobación de presentar y corregir perfiles — #30

Fecha: 23 de noviembre de 2026. Contrato: [revision-perfiles-contrato.md](revision-perfiles-contrato.md#ampliación-30-presentar-corregir-y-cambiar-perfiles).

## Ejecutado localmente

- **pgTAP** `supabase/tests/profile_editing.test.sql`: 42 comprobaciones, todas correctas (suite completa
  339/339 en 16 ficheros tras `supabase db reset --local`, con `spec/11-demo` integrada; los 13 contratos
  `contract_*.py --local` también pasan). Cubre: visitante sin acceso; la misma cuenta crea su
  perfil sin perder la contratación; perfil único e idempotente; validación del contenido (domicilio privado,
  nombre vacío, oficio desconocido, sin concejos, sin teléfono); dirección privada solo del propietario;
  presentación idempotente y `request_conflict`; pendiente fuera del catálogo; otra cuenta no ve ni presenta
  ni escribe el perfil ajeno; el panel nunca recibe la dirección privada; rechazo con motivo, corrección desde
  la versión rechazada y aprobación; un cambio de un perfil aprobado queda pendiente y el catálogo mantiene la
  versión aprobada, también tras rechazar el cambio; el panel recibe la versión publicada para comparar; la
  agenda cambia sin crear versión; la revisión no elimina conversaciones.
- **Contrato HTTP** `python3 scripts/contract_profile_editing.py --local`: OK (1 recorrido). Cuentas
  desechables en loopback, Auth y PostgREST reales: propietario crea y presenta por las RPC de Android;
  **el adaptador real del panel** (`panel/src/review.local.test.ts`) rechaza, aprueba la corrección y rechaza
  un cambio posterior; el catálogo público solo muestra la versión aprobada en todo momento; visitante y
  tercera cuenta no presentan, no leen el perfil ajeno ni las tablas privadas y no pueden `PATCH`; la
  dirección privada no aparece en el panel ni en el catálogo. Limpia sus fixtures.
- **Panel** `cd panel && npm run check`: `tsc` y 11 pruebas Vitest (2 omitidas fuera del contrato local),
  1 nueva: un cambio de un perfil publicado marca solo el campo que cambia junto al valor publicado. Claves
  `name/changed/publicValue` en ES/EN/FR/DE (lo comprueba la prueba de traducciones).
- **Android** `python3 scripts/android.py check`: compilación, lint (traducciones completas en ES/EN/FR/DE)
  y 118 pruebas unitarias; 6 nuevas en `ProfessionalProfilesTest` (rutas y cuerpos RPC, versión aprobada frente a
  editable, cuenta sin perfil, campos obligatorios, fotos y precios intactos, sin dirección privada en el
  contenido, rechazos distinguidos de la incertidumbre de red).
- `ProfileEditorUiTest` (instrumentada, 3 pruebas) compila; **no ejecutada**: no se asignó emulador desechable.

## Pendiente antes de dar por aceptada la issue

1. Aplicar `20261123003000_profile_editing.sql` (después de la de #29) en la base alojada con el
   procedimiento revisado del README.
2. Recorrido con dos emuladores desechables y el panel desplegado: con una cuenta normal, «Cuenta» →
   «Ofrecer mis servicios», completar y presentar; en el panel, rechazar con motivo; en Android ver el
   motivo, corregir y volver a presentar; aprobar; comprobar el perfil en el catálogo desde otra cuenta.
   Después cambiar la descripción, comprobar que el catálogo sigue con la aprobada, rechazar el cambio en el
   panel y comprobar que sigue publicada.
3. Revisar los textos del editor y del panel en ES/EN/FR/DE y ejecutar `ProfileEditorUiTest` y
   `LanguageTest` en un emulador desechable asignado.

No se ha desplegado ni escrito en la base alojada, ni se ha creado ninguna cuenta o proyecto de
Cloudflare. Los resultados anteriores son locales y no constituyen evidencia de aceptación alojada.
