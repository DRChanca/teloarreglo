# Comprobación de la ubicación del profesional en la ventana de llegada — #85

Fecha: 25 de noviembre de 2026. Decisión: ADR 0002 y `GLOSSARY.md` («Ventana de llegada»). Contrato del servidor:
[citas-contrato.md](citas-contrato.md#ventana-de-llegada-en-el-servidor--82). Issue padre: #72.

No se ha desplegado ni escrito en la base alojada. No se ha cambiado el servidor.

## Qué hace la app

- En la cita confirmada, el profesional ve «Compartir mi ubicación». Antes de pedir nada se explica para qué es
  («Tu cliente verá por dónde vienes solo durante la hora previa a la cita…»). Después se piden la ubicación, la
  ubicación «Permitir todo el tiempo» y las notificaciones. Denegar cualquiera no bloquea nada de la cita.
- La elección se guarda solo en el móvil. Hasta una hora antes del inicio no se envía nada: una alarma exacta
  despierta la app en ese momento.
- Al despertar arranca un servicio en primer plano de tipo ubicación con la notificación fija «Compartiendo tu
  ubicación con {cliente}» y la acción «Dejar de compartir». Envía la posición a `report_arrival_position` cada
  ~30 s.
- Cada respuesta del servidor decide (`nextArrivalStep`):
  - `open`: sigue enviando;
  - `not_yet_open`: para y vuelve a intentarlo en `opens_at` (ventana de media hora con otra cita comenzada);
  - `closed` o `42501`: para y olvida la elección;
  - sin red o sin posición: lo intenta en la siguiente vuelta.
- «Dejar de compartir», en la notificación o en la cita, detiene el envío y llama a `stop_sharing_arrival`. El
  cliente pasa al momento a «El profesional no comparte su ubicación».

## Ejecutado localmente

- Pruebas unitarias en JVM:
  - `ArrivalSharingTest` (9): enviar, esperar o detenerse a partir de la respuesta del servidor y de la elección
    del profesional, y el primer paso tras elegir (esperar a −60 min, enviar ya dentro de la hora previa o hasta
    +60 min, nada después).
  - `ArrivalRoundTest` (9): una vuelta completa con un servidor falso. Comprueba que sin elección o sin posición no
    se envía nada, que «cerrada» y el rechazo olvidan la elección, que «aún no abierta» y la falta de red la
    conservan, y que «Dejar de compartir» olvida la elección aunque no haya conexión.
  - `ArrivalPositionsTest` (5): el formato de `report_arrival_position` y `stop_sharing_arrival`, `42501` como
    rechazo definitivo e `invalid_position` como fallo de esa posición.
  - Total del módulo: 358 pruebas en 49 clases, sin fallos.
- `:app:compilePresentacionDebugKotlin`, `:app:testPresentacionDebugUnitTest` y `:app:lintPresentacionDebug`:
  superados (lint sin errores).

No se han ejecutado pruebas instrumentadas ni se ha probado en un emulador.

## Pendiente antes de dar por aceptada la issue

Para David, con la APK de presentación en emulator-5556 (profesional) y emulator-5558 (cliente), nunca
emulator-5554, contra la base con la migración de #82 aplicada.

1. Preparación:
   1. El cliente pide una cita con su casa en un punto conocido, por ejemplo 43.3614, −5.8494 (Oviedo).
   2. El profesional la propone para dentro de unos 70 min, y el cliente la acepta: queda confirmada.
2. Elección y permisos, en emulator-5556:
   1. En el detalle de la cita aparece «Compartir mi ubicación». Al pulsarlo sale la explicación; «Ahora no» no
      cambia nada.
   2. «Continuar» pide la ubicación. Si se deniega, aparece «Sin permiso de ubicación no se comparte nada. La cita
      sigue igual.» y la cita se puede seguir usando con normalidad.
   3. Repetir y conceder: se piden la ubicación «Permitir todo el tiempo» (Android abre los ajustes) y las
      notificaciones. La cita muestra «Compartirás tu ubicación con {cliente} durante la ventana de llegada».
   4. Repetir denegando solo «Permitir todo el tiempo»: aparece el aviso de que solo se comparte si se abre la cita
      durante la ventana.
3. Arranque al abrirse la ventana, con la app cerrada:
   1. Cerrar la app en emulator-5556 y esperar a que falten 60 min para el inicio (o proponer la cita para dentro de
      61 min).
   2. Aparece la notificación fija «Compartiendo tu ubicación con {cliente}», que no se puede descartar, con la
      acción «Dejar de compartir».
4. Ruta GPS que se acerca a la casa, en emulator-5556: en *Extended controls → Location → Routes*, cargar una ruta
   de unos 5 km que termine en la casa y reproducirla. Otra opción es repetir
   `adb -s emulator-5556 emu geo fix <lng> <lat>` con puntos cada vez más cercanos.
   1. En la base, `private.arrival_positions` tiene una sola fila para la cita y `reported_at` avanza cada ~30 s.
   2. En emulator-5558, cuando exista la pantalla del cliente (#84), se ve el círculo a más de 2 km y el punto a
      2 km o menos. A 100 m llega una sola vez «Tu profesional está cerca» (#83).
5. Fin:
   1. «Dejar de compartir» en la notificación: la notificación desaparece y la fila se borra. El cliente ve «El
      profesional no comparte su ubicación».
   2. Volver a elegir compartir en la cita: la notificación vuelve en segundos.
   3. «Dejar de compartir» desde la cita: mismo efecto.
   4. Volver a compartir y pulsar «Comenzar trabajo»: en ≤ 30 s la notificación desaparece sola y no se envía nada
      más.
   5. Con otra cita confirmada y compartiendo, cancelarla desde el cliente: la notificación desaparece sola.
6. Ventana de media hora: con otra cita del profesional comenzada, elegir compartir en una cita que empieza dentro
   de 50 min. No aparece la notificación hasta que faltan 30 min.
7. Revisar los textos nuevos en ES, EN, FR y DE (permiso, notificación y cita).

## Límites conocidos

- Si el móvil se reinicia, la alarma se pierde. La elección sigue guardada y el envío se reanuda en cuanto el
  profesional abre la cita.
- Sin «Permitir todo el tiempo», Android no deja empezar el servicio con la app cerrada. Se reanuda igual, al abrir
  la cita.
- Al despertar a −60 min se envía una posición para conocer la ventana. Si el servidor responde «aún no abierta»,
  no la guarda.
- `USE_EXACT_ALARM` sirve mientras la app no se publique en Google Play. Si se publica, habrá que declararla en la
  ficha, junto con la ubicación en segundo plano.
