# Comprobación de la suscripción simulada — #33

Fecha: 23 de noviembre de 2026. Contrato: [suscripcion-contrato.md](suscripcion-contrato.md).

## Ejecutado localmente

- **pgTAP** `supabase/tests/subscription.test.sql`: 22 comprobaciones, todas correctas (suite completa
  200/200 tras `supabase db reset --local`). Cubre: tabla sin columnas bancarias, de factura o de
  renovación; visitante sin acceso; cliente sin escena y sin poder simular; tabla privada ilegible;
  profesional empieza gratis; precio 500 céntimos, EUR, mensual; `charged` siempre falso; zonas
  «Destacado» iguales a la cobertura aprobada; elección nula rechazada; activar es idempotente;
  catálogo, valoración y reseñas sin cambios; cancelación en cualquier momento; el profesional
  gratuito sigue en el catálogo; el estado de revisión no cambia; un perfil sin aprobar no obtiene
  zonas ni al simular.
- **Contrato HTTP** `python3 scripts/contract_subscription.py --local`: OK (1 recorrido) con Auth y
  PostgREST locales reales y cuentas desechables en loopback. Visitante 401/403; cliente recibe
  `null` y 401/403 al simular; lectura directa de la tabla rechazada; el profesional activa y cancela
  sin cobro; la fila pública del perfil es idéntica antes, durante y después. Limpia sus datos.
- **Android** `python3 scripts/android.py check` (pruebas y presentación): compilación, lint y 75
  pruebas unitarias. 5 son nuevas en `SubscriptionTest`: precio como propuesta sin cobro, cuenta sin
  perfil, el cuerpo enviado es solo `{"p_active":true}`, un permiso denegado no se confunde con un
  fallo de red, y un error del servidor se informa como no confirmado.
- `SubscriptionUiTest` (instrumentada, 4 pruebas: precio, ventajas y lo que conserva el perfil
  gratuito; activar y cancelar sin datos de pago; un cambio no confirmado no aparece como activo;
  perfil sin aprobar sin zonas) compila. **No se ha ejecutado** porque no se asignó un emulador desechable.

## Decisiones de alcance

- «Destacado» se muestra como vista previa etiquetada dentro de la escena y no en los resultados
  de búsqueda. Así la proximidad no cambia y no se promete posición. Las zonas vienen del servidor
  y solo incluyen la cobertura aprobada.
- No se añaden banners globales de demo. Los textos presentan el precio como «provisional
  propuesto» y la interacción como «simulación», y nunca afirman una rentabilidad demostrada.
- No se construyen herramientas de estadísticas, exportación ni plantillas: solo se presentan como
  ventajas de la propuesta.

## Pendiente antes de dar por aceptada la issue

1. Aplicar la migración en la base alojada mediante el paso revisado por David.
2. En un emulador desechable, con la cuenta profesional preparada, abrir «Cuenta» y después «Ver
   ventajas y probar la simulación». Comprobar el precio, las cuatro ventajas, la vista «Destacado»
   con sus concejos, la lista de lo que conserva el perfil gratuito y la frase sobre la
   verificación. Pulsar «Probar la suscripción (simulación)» y después «Volver al perfil gratuito»,
   y confirmar que no se pide ningún dato de pago.
3. Con una cuenta cliente, comprobar que la escena no aparece y que el perfil profesional se ve igual
   en el catálogo y en la búsqueda cercana, tanto con la simulación activa como sin ella.
4. Revisar los textos en ES/EN/FR/DE a 320×640 dp y ejecutar `SubscriptionUiTest` y `LanguageTest` en
   el emulador desechable asignado.

No se ha desplegado ni escrito en la base alojada. Los resultados anteriores son locales y no
constituyen evidencia de aceptación alojada.
