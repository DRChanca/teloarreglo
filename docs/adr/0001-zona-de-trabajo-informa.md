# La zona de trabajo informa, no restringe

Status: accepted (25-11-2026). Sustituye en parte la decisión de #9 (especificación §14) y las reglas de
cobertura de §95, §127 y §165.

Hasta ahora la zona de trabajo (`coverage`) filtraba la búsqueda y las citas la exigían (`22023 out_of_coverage`
al solicitar, proponer un cambio de dirección y aceptarlo). Decidimos que solo informa, porque es un mercado:
si un cliente y un profesional quieren acordar un trabajo fuera de la zona, se les deja.

- La búsqueda muestra a todos los profesionales por distancia. Solo los destacados cuya zona incluye el
  concejo del punto de búsqueda van primero; las tarjetas de fuera de su zona llevan «No suele trabajar en …».
- Las citas se permiten en cualquier concejo de Asturias. Fuera de Asturias se siguen rechazando, porque la
  zona aproximada necesita un concejo.
- Ambas partes ven un aviso informativo. El profesional puede «Añadir [concejo] a mi zona» desde la solicitud.
- En un perfil ya aprobado, añadir o quitar concejos de la zona **no pasa por revisión** del administrador.
  Cada cambio queda en el registro de solo inserción del perfil (quién, concejo, alta o baja, fecha), así que
  el administrador conserva el rastro sin tener que aprobarlo. La primera versión de un perfil y el resto
  de su contenido siguen revisándose.
- Por eso, en un perfil aprobado la zona deja de formar parte de las versiones enviadas a revisión: se edita
  aparte y aprobar una versión conserva la zona vigente, sin deshacer ampliaciones hechas mientras esperaba.
- La zona tiene siempre al menos un concejo.

## Considered Options

- Mantener la cobertura como filtro y restricción: más simple, pero impide trabajos que ambas partes quieren.
- Mostrar dos secciones («en tu concejo» y «fuera»): descartado porque un profesional vecino a 2 km quedaría
  por debajo de uno lejano del mismo concejo.
- Ampliar la zona creando una versión pendiente de revisión: descartado; ampliar o reducir dónde trabaja uno
  no publica contenido nuevo que haya que moderar.
