# Comprobación de conversación persistente — #15

Fecha: 20 de noviembre de 2026. Separar implementación de aceptación alojada.

## Ejecutado localmente

- pgTAP `chat.test.sql`: 16 comprobaciones correctas sobre PostgreSQL local.
  Apertura idempotente, contacto antes de cita/respuesta, reintento sin duplicar,
  rechazo de edición por reintento, lectura y respuesta profesional con autor
  real, prohibición de edición/borrado y aislamiento de administrador ajeno.
- `contract_chat.py --local`: contrato real Auth + PostgREST correcto.
  Dos cuentas intercambian texto, se renueva sesión y se recuperan ambos
  mensajes conservados; una tercera cuenta administradora no lee ni escribe.
  También se rechazan visitante, escritura directa, suplantación, edición y borrado.
- `ChatTest`: cuatro pruebas HTTP controladas correctas: apertura, recuperación
  ordenada, fallo/reintento con la misma clave y rechazo de permisos diferenciado.
  No acreditan la base alojada.
- `ChatUiTest`: cuatro pruebas Compose correctas en emulador **desechable**
  `Teloarreglo_Catalogue_Disposable`, Android API 35, puerto 5560:
  fallo conserva texto y UUID, bandeja profesional abre historial y salida
  pendiente sobrevive a salir/reabrir pantalla y el historial recuperado confirma
  automáticamente un envío cuya respuesta se perdió. HTTP sustituido en estas pruebas.
- Batería completa final: `:app:test :app:lint :app:assemblePruebasDebug
  :app:assemblePresentacionDebug :app:connectedPruebasDebugAndroidTest`, correcta
  (22 pruebas instrumentadas, incluidas cuatro de chat).
  `supabase test db`: 53 comprobaciones correctas en cinco archivos.
  Contratos locales completos de identidad, catálogo, portadas y chat: correctos.
- [Revisión en dos ejes y correcciones](revision-chat.md).

## Pendiente antes de dar por aceptada la issue

- Desplegar de forma revisada las dos migraciones nuevas en la única base
  alojada, comprobando antes el historial existente. No ejecutar `db reset`,
  seed, borrado de usuarios ni repoblación.
- Comprobar el propietario del perfil elegido y disponer de dos cuentas
  verificadas distintas. No cambiar la propiedad de perfiles preparados sin
  revisarlo con David.
- En dos emuladores con APK del mismo commit:
  1. Como visitante, abrir perfil y pulsar «Enviar mensaje»: pide acceso.
  2. Acceder como cliente y enviar texto sin cita ni respuesta previa.
  3. Como profesional, abrir «Conversaciones», leer y responder.
  4. Cortar/reponer la conexión: comprobar estado sin confirmar, reintento sin
     duplicado y recuperación de mensajes enviados por la otra cuenta.
  5. Cerrar y reabrir ambas apps: conservar identidad e historial.
  6. Revisar ES/EN/FR/DE y capturar primera integración acceso/perfil/chat,
     sin credenciales ni tokens. Registrar APK, SHA y evidencia.
- Revisión visual con David y captura real entre dos dispositivos **no realizadas**.
  No se presenta un test con servicio sustituido como evidencia de ese recorrido.

No se ha desplegado ni escrito en la base alojada durante esta implementación.
No se cierra la issue hasta completar la comprobación compartida.

## Corrección de CI — 21 de noviembre

Ejecución analizada:
https://github.com/DRChanca/teloarreglo/actions/runs/44795274786

- `LanguageTest` fallaba en API 26 y 35: la cabecera añadida para chat dejaba
  el selector de idioma fuera de pantalla a 320×640 dp. Reproducido localmente
  con el mismo error `language_de`, aislando únicamente `LanguageTest`.
  La aserción adicional de visibilidad confirmó que el botón no se mostraba.
  Se separan navegación y selector de idioma en dos filas, sin retirar funciones.
- `AccessTest.signedInAccountCanSignOutWithoutLeavingAnAuthenticatedScreen`
  fallaba en API 35 buscando `account` antes de completarse el acceso.
  Una respuesta de identidad controlada con 250 ms reproduce el mismo fallo.
  La prueba espera ahora al estado visible con límite de cinco segundos,
  manteniendo las aserciones de acceso y cierre de sesión. No añade esperas
  fijas ni reintentos de pruebas fallidas; el retraso solo pertenece al servicio
  simulado para comprobar una respuesta asíncrona.
- Verificación final local, emulador desechable API 35 configurado a
  320×640 píxeles y densidad 160 (320×640 dp):
  `:app:test :app:lint :app:connectedPruebasDebugAndroidTest` correcto,
  22 pruebas instrumentadas sin fallos. La prueba de idioma falló antes del
  arreglo y pasó después en esa misma configuración.
- Pendiente volver a ejecutar Actions con el arreglo en las dos API;
  no se afirma haber ejecutado API 26 localmente.
