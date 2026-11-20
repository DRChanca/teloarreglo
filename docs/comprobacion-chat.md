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
- `ChatTest`: tres pruebas HTTP controladas correctas: apertura, recuperación
  ordenada y fallo/reintento con la misma clave. No acreditan la base alojada.
- `ChatUiTest`: tres pruebas Compose correctas en emulador **desechable**
  `Teloarreglo_Catalogue_Disposable`, Android API 35, puerto 5560:
  fallo conserva texto y UUID, bandeja profesional abre historial y salida
  pendiente sobrevive a salir/reabrir pantalla. HTTP sustituido en estas pruebas.

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
