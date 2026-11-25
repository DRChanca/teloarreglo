# Seguimiento en directo solo en la ventana de llegada

Status: accepted (25-11-2026). Sustituye en parte la regla de la especificación (§1, «no por seguimiento en
directo») para la ventana de llegada; la búsqueda sigue usando solo la ubicación registrada. Origen: #62.

Hasta ahora la app nunca conocía la posición en directo del profesional. Decidimos que, durante la ventana de
llegada de una cita confirmada, el cliente vea en un mapa dónde está el profesional y reciba un aviso cuando
esté a 100 m de la casa, porque saber que llega y prepararse es lo que el cliente pide.

- La ventana es la hora previa al inicio; media hora si el profesional tiene otra cita comenzada. Termina con
  «Comenzar trabajo», la cancelación o, como tope, una hora después del inicio.
- Compartir es voluntario: el móvil del profesional envía su posición en segundo plano solo dentro de la
  ventana, con una notificación fija que lo indica. Si no la comparte, el cliente ve «El profesional no
  comparte su ubicación» y no hay aviso de cercanía.
- A más de 2 km de la casa se muestra un círculo aproximado de 500 m, para no revelar el domicilio del
  profesional; más cerca, la posición exacta.
- Solo se guarda la última posición de cada cita y se borra al cerrar la ventana: sin historial de recorrido ni
  acceso del administrador.
- El aviso de cercanía se envía una vez por cita y lo calcula el servidor con esa misma posición.
- Riesgo aceptado: en la media hora previa a otra cita, si el profesional sigue en casa de un cliente anterior a
  menos de 2 km, el siguiente cliente puede ver esa posición exacta.

## Considered Options

- Geovalla en el móvil del profesional (solo avisa al entrar en 100 m, sin enviar la posición): más privada,
  pero no permite que el cliente vea por dónde viene.
- Botón manual «Voy de camino»: sin permisos de segundo plano, pero depende de que el profesional se acuerde.
- Exigir compartir la ubicación para confirmar citas: descartado; Android no permite forzar el permiso y dejaría
  sin trabajar a quien no lo conceda.
