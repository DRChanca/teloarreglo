# Comprobación de «Llamar» en el aviso para llamar — #81

Fecha: 25 de noviembre de 2026.
Contratos: [avisos-contrato.md](avisos-contrato.md) (aviso `call_client`, #77) y
[citas-contrato.md](citas-contrato.md) (teléfono de contacto en `appointment_places`, #79).

No se ha desplegado ni escrito en la base alojada. Este ticket no añade migraciones.

## Qué hace

- La notificación `call_client` lleva la acción «Llamar» (`call_button`, ya traducida en ES/EN/FR/DE) cuando el push
  trae `conversation_id` y `appointment_id` (`pushCall`).
- La acción abre `MainActivity` con los mismos extras que el cuerpo del aviso más `EXTRA_CALL_APPOINTMENT`. Solo
  viajan identificadores: el número no está en el aviso, en el push ni en el Intent.
- Con la cuenta ya conectada, la app abre la conversación de la cita, marca el aviso como leído, quita la
  notificación y decide con `callNoticeAction`. Esta función pide el número en ese momento a
  `Appointments.contactPhone`:
  - Si hay número marcable, se abre el marcador con `ACTION_DIAL` encima de la cita. No llama directamente y no
    pide permiso de llamada.
  - Si no hay número, se queda abierta la cita. Esto ocurre si la cita está terminada o cancelada, si no se puede
    leer, si falla la red o si el texto no es marcable.
- El extra se borra del Intent nada más leerlo, así que recrear la actividad no vuelve a abrir el marcador.
- Tocar el cuerpo del aviso sigue abriendo la cita, como en #77.

## Ejecutado

| Prueba | Resultado | Qué cubre |
| --- | --- | --- |
| `CallNoticeTest` | 4/4 | Con número se abre el marcador con `tel:` normalizado y el número se pide para esa conversación y esa cita. Sin número o sin nada marcable se abre la cita. Si la lectura falla, se abre la cita. Solo el push `call_client` con ambos identificadores lleva la acción. |
| `:app:testPresentacionDebugUnitTest` | 365/365 | Sin regresiones. |
| `:app:assemblePresentacionDebug` | OK | Compilación. |
| Emulador `emulator-5554` (`sdk_gphone64_arm64`, solo había uno) | Parcial | Se instaló la variante `presentacion`, se forzó su cierre (`am force-stop`) y se lanzó `MainActivity` con los extras de la acción (`OPEN_NOTICES`, `OPEN_CONVERSATION` y `CALL_APPOINTMENT` con identificadores inventados). La app arrancó en frío sin cerrarse y quedó en Avisos, en la pantalla de acceso, porque no había sesión iniciada. Así se comprueba que la acción con la app cerrada no rompe el arranque. No se comprueban ni el número ni el marcador. |

## Pendiente

Esta comprobación manual del ticket no se ha hecho: **con la app cerrada, el aviso llega y la acción abre el
marcador, en los dos emuladores**. Motivos:

- Solo había un emulador disponible y no tenía ninguna sesión iniciada. No había credenciales de una cuenta
  profesional con una cita confirmada.
- Un aviso real necesita la base alojada con la migración de #77, el cron `call-notices` programado y
  `deliver-push` desplegada con la cuenta de servicio de FCM. No se ha preparado nada de eso en este ticket.

Pasos para completarla en cada emulador:

1. Aplicar las migraciones de spec-71, programar `call-notices` (ver `avisos-contrato.md`) y desplegar
   `deliver-push`.
2. Profesional con sesión iniciada en el emulador y notificaciones permitidas. Cliente con teléfono de contacto y
   una cita confirmada que empiece en menos de una hora.
3. Cerrar la app (deslizarla en Recientes). Cuando llegue la notificación «Tu cita empieza pronto: llama al
   cliente», pulsar «Llamar». Debe abrirse el marcador con el número del cliente, sin llamar, y al volver debe
   verse la cita. La notificación desaparece.
4. Repetir con la cita cancelada antes de pulsar «Llamar». Debe abrirse la cita y no el marcador.
5. Tocar el cuerpo de otro aviso para llamar. Debe abrirse la cita.
