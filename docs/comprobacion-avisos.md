# Comprobación de avisos persistentes y notificaciones push — #32

Fecha: 23 de noviembre de 2026.
Contrato: [avisos-contrato.md](avisos-contrato.md).

No se ha desplegado ni escrito en la base alojada. Las migraciones `20261123013200_notices.sql` y
`20261123013210_push_delivery.sql` solo se han aplicado a la pila Supabase local, en la rama `ticket/32` unida
con `spec/11-demo` (tip `54c1dc2`, con #26). La Edge Function `deliver-push` no se ha desplegado. No se ha enviado
ningún mensaje a FCM: no hay proyecto Firebase ni credenciales configuradas.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/notices.test.sql` | 19/19 | Sobre el contenido de los avisos: <br>• Un mensaje avisa solo al otro participante y nunca lleva el cuerpo. <br>• Un tercero no recibe nada. <br>• La solicitud de cita avisa al profesional sin dirección ni coordenadas. <br>• Un tipo de evento nuevo (`incident_opened`, de #26) llega al destinatario sin cambiar esta capa, y no al actor. <br><br>Sobre la lectura: <br>• Los mensajes de una conversación se agrupan con su número de mensajes sin leer. <br>• Abrir la conversación marca el grupo leído, pero no el aviso de cita. <br>• Otra cuenta no puede marcar avisos ajenos. <br>• La marca de leído persiste. <br>• No hay lectura directa de la tabla ni acceso anónimo. |
| pgTAP `supabase/tests/push_delivery.test.sql` | 32/32 | Sobre el registro y la seguridad: <br>• Un token vacío se rechaza. <br>• Ni las cuentas ni los anónimos pueden leer dispositivos, reservar entregas o informar resultados. <br><br>Sobre la entrega: <br>• Hay una entrega por dispositivo activo. <br>• La reserva solo incluye token, destinatario e identificadores, sin cuerpo. <br>• Una entrega en curso no se entrega dos veces. <br>• `sent` es definitivo y un informe tardío se ignora. <br>• `retry` espera su plazo, reutiliza la misma fila y pasa a `failed` tras 5 intentos sin perder el aviso de la app. <br>• Un token inválido desactiva el dispositivo, y un token renovado lo reactiva. <br><br>Sobre los cambios de sesión: <br>• La instalación pasa a la nueva cuenta. <br>• Las entregas pendientes de la anterior se descartan y esa cuenta deja de recibir push. <br>• Una cuenta no puede borrar un dispositivo que ya no es suyo. <br><br>Sobre los dispositivos: <br>• Un token pertenece a una sola instalación. <br>• Si se desactiva en la app, no recibe nada. <br>• Al cerrar sesión no se envía nada. <br>• Una entrega de más de un día caduca. |
| Suite pgTAP completa | 769/769 en 29 archivos | Sin regresiones. `profile_review` sigue funcionando con la nueva `my_notices()`. |
| `python3 scripts/contract_notices.py --local` | 1 prueba OK | Con Auth y PostgREST reales y tres cuentas desechables: <br>• El aviso agrupado (`count` 2) no contiene el cuerpo. <br>• Cliente, tercero y anónimo no ven avisos ajenos. <br>• Las cuentas no pueden reservar entregas ni leer tablas privadas. <br>• Dos despachadores simultáneos reciben entregas distintas. <br>• `sent` e `invalid_token` funcionan: tras `invalid_token` no hay push, pero el aviso sigue (`count` 3) y un token nuevo reanuda los envíos. <br>• Leer la conversación marca el aviso. <br>• Tras desregistrar el dispositivo no se envía nada. <br><br>El despachador lo simula la clave `service_role` local. No se llama a FCM. |
| Resto de contratos `--local` | OK los 21 `contract_*.py` | Incluidos `incidents` (#26) y `completion` (#25), que producen eventos de cita. |
| Deno `supabase/functions/deliver-push/dispatch_test.ts` | 6/6 | Cada entrega reservada se envía y se informa con su resultado. Un fallo del emisor cuenta como `retry` y no detiene el resto. Clasificación de respuestas de FCM. El mensaje es solo de datos y de prioridad alta, sin `notification` ni contenido privado. `FcmSender` firma el JWT de la cuenta de servicio con WebCrypto, obtiene el token OAuth una sola vez y envía al endpoint del proyecto; se probó con `fetch` falso y una clave RSA generada en la prueba. Sin cuenta de servicio no hay emisor. |
| `NoticesTest` (MockEngine y dobles) | 9/9 | `my_notices` y `mark_notices_read` (solo los identificadores elegidos). Tema de cada evento conocido, `incident*` como incidencia y los eventos desconocidos con un tema genérico. Registro al conectar. Sin permiso Android se registra `enabled=false`. Desactivar se recuerda. Al cerrar sesión deja de aceptar push de esa cuenta aunque falle la red y desregistra cuando hay conexión. Sin Firebase el estado es `NOT_CONFIGURED`. Un fallo de registro se muestra como `FAILED` sin lanzar error. |
| Unitarias Android | 192/192 en `pruebas` | — |
| `python3 scripts/android.py check` | OK | Compilación, unitarias y lint con 36 claves `notices_*` y el plural `notices_messages` en ES/EN/FR/DE. |
| `:app:compilePruebasDebugAndroidTestKotlin` y `:app:assemblePresentacionDebug` | OK | Solo compilación. |

MockEngine, los dobles y el despachador simulado comprueban la app y el servidor, pero no la entrega real. No son
evidencia de que llegue un push con la app cerrada.

## No ejecutado

- **Recepción real con la app cerrada.** No existe un proyecto Firebase y faltan las credenciales:
  - En Supabase: `FCM_SERVICE_ACCOUNT` y `PUSH_DISPATCH_SECRET`.
  - En `.env`: `FCM_PROJECT_ID`, `FCM_API_KEY`, `FCM_SENDER_ID`, `TEST_FCM_APP_ID` y `PRESENTATION_FCM_APP_ID`.

  Sin ellos, la app muestra «Esta versión no recibe notificaciones push. Los avisos se guardan aquí.» y todo
  funciona dentro de la app.
- **Recordatorio a 24 h con el reloj corto.** El productor pertenece a #27, que todavía no existe. Cuando #27
  inserte un evento `reminder` en `appointment_events`, llegará como aviso y como push sin cambios aquí, y Android
  ya tiene el texto «Recordatorio: confirma el trabajo realizado o comunica una incidencia».
- **Pruebas instrumentadas y emulador.** No se ha usado emulator-5554.
- **Cron de `deliver-push`.** No se ha programado (ver el SQL en el contrato).
- **`panel/`.** No se ha modificado. El panel de incidencias de #31 tampoco existe aún. Sus decisiones aparecerán
  como avisos si registran un evento en `appointment_events`.

## Pendiente antes de dar por aceptada la issue

1. David crea un proyecto Firebase en el plan Spark gratuito, sin activar Blaze. Registra dos apps Android:
   `es.teloarreglo.pruebas` y `es.teloarreglo.presentacion`. Copia sus identificadores públicos a `.env` y crea una
   cuenta de servicio con el rol de envío de FCM. La cuenta de servicio solo se guarda como secreto de Supabase.
2. Desplegar las migraciones y la función como paso revisado. Definir `PUSH_DISPATCH_SECRET` y programar Cron cada
   minuto.
3. Con dos emuladores desechables (API 33 o superior):
   - Conceder el permiso desde «Avisos».
   - Cerrar la app del profesional.
   - El cliente envía un mensaje y solicita una cita.
   - Llega la notificación «Nuevo mensaje» o «Nueva solicitud de cita», sin texto del mensaje.
   - Al tocarla se abre «Avisos». Al tocar el aviso se abre la conversación.
4. Repetir:
   - Con el permiso denegado: no llega ningún push, el aviso está en la lista y la tarjeta pide permiso.
   - Con «Desactivar».
   - Tras cerrar sesión y entrar con otra cuenta en el mismo dispositivo: no llegan push de la cuenta anterior.
5. Con #27 desplegado y el reloj corto aislado, comprobar el recordatorio con la app cerrada.
6. Revisar los textos `notices_*` en ES/EN/FR/DE a 320×640 dp.

No se promete entrega puntual. Las entregas se reintentan con espera exponencial y caducan al día. Un dispositivo
sin conexión o restringido puede recibirlas tarde o no recibirlas, y el aviso siempre queda en la app.
