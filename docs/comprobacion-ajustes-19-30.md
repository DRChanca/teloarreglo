# Comprobación de los ajustes a #19 y #30

Fecha: 23 de noviembre de 2026. Rama `fix/ajustes-19-30`, con `spec/11-demo` (hasta #22) integrada.
Decisiones de David del mismo día, recogidas en la [especificación](especificacion.md#actualización-decisiones-de-david-del-23-de-noviembre-de-2026).
**No se ha desplegado ni escrito en la base alojada.**

## Qué cambia

- **Vídeos (#19):** máximo de 40 s (antes 60 s), con el mismo margen de 499 ms. Solo la app: el servidor
  nunca validó la duración. Ver [comprobación de vídeos](comprobacion-videos.md#corrección-del-23-de-noviembre-de-2026-máximo-de-40-segundos).
- **Servicios y precios orientativos:** el propietario los publica desde el editor sin revisión
  (`save_professional_offer`, migración `20261123004100`). El resto del perfil sigue la revisión de #30.
  Contrato: [revision-perfiles-contrato.md](revision-perfiles-contrato.md#ampliación-del-23-de-noviembre-de-2026-servicios-precios-y-portada-sin-revisión).
- **Portada:** se publica sin revisión al confirmar la subida (migración `20261123004200`); un perfil sin
  aprobar la muestra cuando se publica; `review_professional_cover` deja de estar disponible. Contrato:
  [portadas-y-diseno.md](portadas-y-diseno.md#publicación-directa-desde-el-23-de-noviembre-de-2026).
- **Precio guardado:** la solicitud guarda los precios vigentes (`appointments.reference_prices`) y la
  propuesta su importe; nada posterior del perfil los cambia (migración `20261123004300`). Contrato:
  [citas-contrato.md](citas-contrato.md#precio-guardado-en-la-cita--decisión-del-23-de-noviembre-de-2026).

## Ejecutado localmente

Todo con `db-check.sh` (reinicio de la pila local y pgTAP) tras integrar `spec/11-demo`:

- **pgTAP:** 506/506 en 22 archivos. Nuevos o cambiados:
  - `profile_offer.test.sql` (25): visitante y otra cuenta no publican; validación de unidad, importe
    negativo, servicio vacío, campos que faltan o sobran; antes de aprobar se guardan sin salir en el
    catálogo; presentar el perfil no cambia los servicios; al aprobar aparecen los del propietario; tras
    aprobar, un cambio de precio es público al momento y no crea versión; aprobar después una versión
    antigua no devuelve el precio anterior.
  - `appointment_prices.test.sql` (10): el cliente pide, el negocio sube el precio y la solicitud conserva
    el original; la propuesta conserva su importe aunque el perfil baje; ni SQL de confianza cambia el
    precio guardado de una solicitud ni el importe de una propuesta decidida (`price_locked`).
  - `professional_covers.test.sql` (21, reescrito): imagen de oficio y foto se publican sin revisión;
    reintento idempotente; `request_conflict`; sin objeto o con MIME distinto no se publica; otra cuenta no
    publica; el público solo lee lo publicado; el administrador ya no puede revisar; perfil sin aprobar:
    foto guardada, visible para el propietario, pública al aprobarse el perfil.
- **Contratos HTTP** `contract_*.py --local`: los 16 correctos. `contract_covers.py` reescrito (publicación
  directa con Storage real, sin revisión); `contract_profile_editing.py` añade la publicación directa de
  precios; `contract_agenda.py` ordena por fecha los avisos que comprueba (fallaba de forma intermitente
  por un orden no fijado, sin relación con este cambio).
- **Android** `python3 scripts/android.py check`: compilación, lint y 155 pruebas JVM en 22 clases.
  Nuevas o cambiadas: `ChatVideoTest` (límite de 40 s), `ProfileOfferTest` (3: servicios por línea,
  euros ↔ céntimos, fila incompleta no se envía), `ProfessionalProfilesTest` (+2: RPC
  `save_professional_offer` y rechazo `INVALID`), `ProfessionalCoversTest` (estado `published`).
- **Panel** `npm run check`: `tsc` y 12 pruebas (2 omitidas fuera del contrato local); 1 nueva: la tarjeta
  de revisión no muestra servicios, precios ni portadas.
- Instrumentadas que compilan y **no se han ejecutado** (sin emulador desechable asignado):
  `ProfileEditorUiTest.pricesArePublishedDirectlyAndAnIncompletePriceIsNotSent`, `CoverEditorTest`
  (estados `PUBLISHED`), `ChatVideoPrepareTest`.

## Pendiente antes de dar por aceptado

1. Ejecutar en un emulador desechable `ProfileEditorUiTest`, `CoverEditorTest` y `ChatVideoPrepareTest`.
2. David revisa y aplica en la base alojada, en orden, `20261123004100_profile_offer_direct.sql`,
   `20261123004200_direct_covers.sql` y `20261123004300_appointment_kept_prices.sql`. La segunda publica la
   última portada pendiente de cada perfil: confirmarlo antes.
3. Con dos emuladores y el APK del mismo commit:
   1. Propietario de un perfil aprobado: «Editar mi perfil profesional», añadir un precio («Añadir
      precio», servicio, importe 35,50, unidad «hora») y «Guardar y publicar». Desde otra cuenta, el
      catálogo muestra el precio sin pasar por el panel; el panel no muestra ninguna versión nueva.
   2. Guardar un precio sin importe: aviso y nada publicado.
   3. Como cliente, solicitar una cita; como propietario, cambiar el precio. La solicitud conserva el precio
      anterior (hoy solo comprobable en la base: Android aún no lo muestra).
   4. «Cambiar foto de portada» desde el editor: «Publicar portada» y «Portada publicada.»; el catálogo la
      muestra al volver. Con un perfil todavía sin aprobar, la foto no aparece en el catálogo hasta que el
      panel aprueba el perfil.
   5. Enviar en el chat un vídeo de 45 s (aviso de duración, nada enviado) y otro de 40 s (se envía).
   6. Revisar en ES, EN, FR y DE los textos nuevos del editor, de la portada y del límite de vídeo, también
      a 320×640 dp.
