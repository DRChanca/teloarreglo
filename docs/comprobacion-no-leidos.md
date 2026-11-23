# Comprobación de la bandeja con nombre del cliente y no leídos — #39

Fecha: 23 de noviembre de 2026. Separar lo probado en local de la aceptación
alojada. No se ha desplegado ni escrito en la base alojada.

## Decisiones aplicadas

- Cada fila muestra en grande a la otra persona: el **nombre del cliente** para
  el profesional y el **nombre del negocio** para el cliente. A la derecha, un
  indicador con el número de mensajes sin leer de la otra persona (99+ a partir
  de 100), que no aparece si no hay ninguno. Con mensajes sin leer el nombre va
  en negrita.
- El lector de pantalla anuncia la frase completa, p. ej. «3 mensajes sin leer»
  (plurales en ES, EN, FR y DE).
- El nombre del cliente sale de sus metadatos de Auth (`full_name`/`name`). Si
  no hay, se muestra «Cliente · últimos 8 del identificador». Nunca el correo.
- Abrir la conversación la marca como leída, y también cada mensaje nuevo visto
  mientras está abierta. La marca es por participante: leer no cambia el
  indicador de la otra persona.
- La cabecera del chat abierto muestra también a la otra persona.

## Ejecutado localmente

- pgTAP `chat_unread.test.sql`: 20 comprobaciones correctas (suite completa
  234 en 13 archivos). Cubre: los mensajes propios no cuentan, recuento por
  conversación, nombre recortado, `null` sin nombre declarado (sin correo),
  marcar como leído pone el contador a 0 y deja las demás conversaciones
  igual, la marca del profesional no afecta al cliente, un mensaje posterior
  vuelve a contar, RLS de `conversation_reads` solo propia, sin escritura
  directa (INSERT/UPDATE), bandeja vacía y `42501` para una tercera cuenta que
  conoce el identificador, y bandeja denegada al rol anónimo.
- `contract_chat_unread.py --local`: contrato real Auth + PostgREST correcto
  (nombre desde `user_metadata`, correo ausente de la respuesta, recuento 2→0,
  indicador propio del cliente, tercera cuenta y visitante rechazados al marcar,
  lectura directa vacía y escritura directa rechazada). Solo crea y elimina sus
  propias cuentas y filas aleatorias en loopback.
- Resto de contratos `--local` (identidad, catálogo, chat, fotos, portadas,
  citas, mapa, revisión de perfiles, suscripción): correctos con esta migración.
- Unitarias JVM (`./gradlew :app:testPruebasDebugUnitTest`): 87 correctas, de
  ellas 2 nuevas en `ChatTest` (bandeja por `rpc/conversation_inbox` con nombre
  y recuento; `rpc/mark_conversation_read` con el identificador). No acreditan
  la base alojada.
- `python3 scripts/android.py check` (compilación, unitarias y lint con los
  plurales ES/EN/FR/DE) y `:app:compilePruebasDebugAndroidTestKotlin`:
  correctos.

## Escrito pero no ejecutado

- `ConversationRowUiTest` (6 pruebas Compose): nombre del cliente e indicador
  con descripción accesible, sin indicador a 0, el cliente ve el negocio y su
  indicador, alternativa sin nombre, 99+ con anuncio exacto, y abrir el chat
  llama a `markRead`. Necesita un emulador **desechable** asignado; no se ha
  usado emulator-5554.

## Pendiente antes de dar por aceptada la issue

1. Ejecutar las pruebas instrumentadas en un emulador desechable.
2. Desplegar de forma revisada `20261123003900_chat_unread.sql` en la base
   alojada. Sin `db reset`, seed ni borrados.
3. Dar nombre a la cuenta cliente de la presentación: con Google ya lo trae;
   con correo y contraseña la app no lo pide todavía (ver decisión abierta).
4. En dos emuladores con el APK del mismo commit y dos cuentas verificadas:
   1. Como cliente, escribir tres mensajes al profesional.
   2. Como profesional, abrir la bandeja de conversaciones: la fila muestra el nombre del cliente
      en grande y un «3» a la derecha (en menos de 3 s si ya estaba abierta).
   3. Abrir la conversación, volver: el indicador ha desaparecido.
   4. Responder; como cliente, ver el negocio con «1» y que desaparece al abrir.
   5. Con TalkBack, comprobar que se anuncia «3 mensajes sin leer».
   6. Revisar textos y disposición en ES, EN, FR y DE (también a 320×640 dp y
      con un nombre largo).
