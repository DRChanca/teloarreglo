# Comprobación de la ubicación del profesional en la ventana de llegada — #85

Fecha: 25 de noviembre de 2026. Decisión: ADR 0002 y `GLOSSARY.md` («Ventana de llegada»). Contrato del servidor:
[citas-contrato.md](citas-contrato.md#ventana-de-llegada-en-el-servidor--82). Issue padre: #72.

No se ha desplegado ni escrito en la base alojada. No se ha cambiado el servidor.

## Qué hace la app

- En la cita confirmada, el profesional ve «Compartir mi ubicación». Antes de pedir nada se explica para qué es
  («Tu cliente verá por dónde vienes solo durante la hora previa a la cita…»). Después se pide la ubicación. **En cuanto
  se concede, la elección queda guardada**; la ubicación «Permitir todo el tiempo» y las notificaciones se piden
  después y solo la completan, así que denegarlas (o que los ajustes de Android manden la app a segundo plano) no la
  pierde. Denegar la ubicación no comparte nada y no bloquea nada de la cita.
- Volver a la app (desde los ajustes de Android, otra app o la pantalla de inicio) ya no cierra la hoja de citas: la
  comprobación de la sesión de `MainActivity.onStart()` mantiene conectada la cuenta mientras pregunta y solo cambia
  de estado con una respuesta definitiva (sesión caducada o revocada).
- La elección se guarda solo en el móvil. Con cada elección nueva, la app llama a `start_sharing_arrival` antes de
  enviar ninguna posición. Hasta una hora antes del inicio no se envía nada: una alarma exacta despierta la app en ese
  momento. Antes de arrancar el servicio se pregunta la ventana (sin posición), así que con la ventana «aún no
  abierta» no aparece la notificación fija.
- El inicio guardado sigue al servidor (`start_at` de «estado de la ventana»): al volver la app a primer plano, con
  cada push de esa cita y al reiniciar el móvil o actualizar la app (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) se
  pregunta y se reprograma la alarma. Un cambio aceptado que adelanta la cita ya no hace perder la ventana.
- La posición se toma con el proveedor `fused` de alta precisión (Android 12+), que combina GPS, Wi-Fi y antenas; sin
  él, GPS durante la mitad del tiempo y después la red. La búsqueda sigue usando `currentLocation` como antes.
- Al despertar arranca un servicio en primer plano de tipo ubicación con la notificación fija «Compartiendo tu
  ubicación con {cliente}» y la acción «Dejar de compartir». Cada ~30 s pregunta primero el estado de la ventana a
  `arrival_sharing_window`, que no recibe ninguna posición, y solo con la ventana abierta toma la posición y la
  envía a `report_arrival_position`. Fuera de la ventana nunca sale del móvil una posición.
- Cada respuesta del servidor decide (`nextArrivalStep`):
  - `open`: sigue enviando;
  - `not_yet_open`: para y vuelve a intentarlo en `opens_at` (ventana de media hora con otra cita comenzada);
  - `closed` o `42501`: para y olvida la elección;
  - sin red o sin posición: lo intenta en la siguiente vuelta.
- «Dejar de compartir», en la notificación o en la cita, detiene el envío y llama a `stop_sharing_arrival`. El
  cliente pasa al momento a «El profesional no comparte su ubicación». El servidor deja una marca de parada y
  rechaza (`stopped: true`) cualquier posición que llegue después, también una que ya iba de camino, hasta que el
  profesional vuelva a elegir compartir. Parar y volver a compartir en el mismo móvil llegan al servidor en ese orden.
- Cerrar sesión detiene el envío de todas las citas, avisa al servidor (`stop_sharing_arrival`), olvida las
  elecciones y cancela sus alarmas antes de cerrar la sesión.
- El mapa del cliente calcula la antigüedad con `age_s` del servidor más el tiempo transcurrido desde la lectura
  (reloj monotónico): un móvil con la hora adelantada ya no ve siempre «no comparte».

## Ejecutado localmente

- Pruebas unitarias en JVM:
  - `ArrivalSharingTest` (9): enviar, esperar o detenerse a partir de la respuesta del servidor y de la elección
    del profesional, y el primer paso tras elegir (esperar a −60 min, enviar ya dentro de la hora previa o hasta
    +60 min, nada después).
  - `ArrivalRoundTest` (13): una vuelta completa con un servidor falso. Comprueba que sin elección o sin posición no
    se envía nada, que con la ventana «aún no abierta», cerrada o desconocida (sin red) no se envía ninguna
    posición, que «cerrada» y el rechazo olvidan la elección, que «aún no abierta» y la falta de red la
    conservan, y que «Dejar de compartir» olvida la elección aunque no haya conexión.
  - `ArrivalPositionsTest` (7): el formato de `arrival_sharing_window` (sin posición), `report_arrival_position`
    (también sin precisión) y `stop_sharing_arrival`, `42501` como rechazo definitivo e `invalid_position` como
    fallo de esa posición.
  - Total del módulo, tras unir #83 y #84 de `spec-72` y las correcciones de revisión: 374 pruebas, sin fallos.
- `:app:compilePresentacionDebugKotlin`, `:app:testPresentacionDebugUnitTest` y `:app:lintPresentacionDebug`:
  superados (lint sin errores).

No se han ejecutado pruebas instrumentadas ni se ha probado en un emulador.

## Pendiente antes de dar por aceptada la issue

Para David, con la APK de presentación en emulator-5556 (profesional) y emulator-5558 (cliente), nunca
emulator-5554, contra la base con la migración de #82 aplicada.

1. Preparación:
   1. El cliente pide una cita con la dirección de la cita en un punto conocido, por ejemplo 43.3614, −5.8494
      (Oviedo).
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
4. Ruta GPS que se acerca a la dirección de la cita, en emulator-5556: en *Extended controls → Location → Routes*,
   cargar una ruta de unos 5 km que termine en la dirección de la cita y reproducirla. Otra opción es repetir
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

## Correcciones de la verificación (26-11-2026)

Defectos encontrados al verificar #72 en emulador y corregidos en la rama `spec-72-fixes`: la primera elección se
perdía al volver de los ajustes de Android; una posición en vuelo al parar podía volver a mostrarse; el reloj
adelantado del cliente ocultaba la posición; el centro del círculo se volvía a sortear (independiente) en cada cita o
tras parar; la alarma no seguía un cambio que adelantaba la cita ni sobrevivía a un reinicio; la posición dependía
solo del GPS; la notificación aparecía un instante con la ventana «aún no abierta»; cerrar sesión no detenía nada.
Servidor: `20261126080000_arrival_review_fixes.sql` (ver citas-contrato.md). Pruebas nuevas:
`supabase/tests/arrival_review_fixes.test.sql` (30), `ArrivalDeviceTest` (5), y casos nuevos en `ArrivalRoundTest`,
`ArrivalPositionsTest`, `ArrivalWindowTest` e `IdentityTest`.

## Límites conocidos

- Sin «Permitir todo el tiempo», Android no deja empezar el servicio con la app cerrada. Se reanuda igual, al abrir
  la cita o al volver la app a primer plano.
- Android exige mostrar la notificación pocos segundos después de pedir el servicio, antes de poder preguntar al
  servidor. Por eso la ventana se pregunta antes de arrancarlo (como mucho 5 s, dentro del margen que da una alarma
  exacta). Solo si esa respuesta no llega a tiempo, o la ventana cambia entre medias, la notificación aparece un
  instante y se retira.
- El aviso de una cita adelantada llega por push; sin push (sin Firebase o sin red) el inicio se actualiza al volver
  la app a primer plano o al abrir la cita.
- `USE_EXACT_ALARM` sirve mientras la app no se publique en Google Play. Si se publica, habrá que declararla en la
  ficha, junto con la ubicación en segundo plano.
