# Revisión de #15

Base confirmada por David: `ea454d6c6bc175c241e0d2288ce0691253d779e2`.
Primera revisión de `git diff ea454d6...HEAD`, commit `93cd42c`.
Dos revisores independientes, sin modificar código.

## Standards

Violaciones documentadas encontradas:

1. Fecha cruda del servidor en `ChatScreen.kt`: contradice la visualización
   según zona horaria/idioma de `docs/arquitectura-demo.md:75`.
   **Corregido** con formato localizado del instante UTC en zona del dispositivo.
2. Todo error de envío se trataba como conexión incierta, manteniendo bloqueado
   el editor sin distinguir rechazos definitivos. Contradice
   `docs/arquitectura-demo.md:98` y `docs/especificacion.md:159`.
   **Corregido**: permisos/sesión, texto inválido y conflicto se distinguen.
   Texto inválido permite corregir; permisos conservan la clave pendiente para
   reintentar tras comprobar sesión. Los errores inciertos conservan la clave.

Heurísticas, no infracciones:

- Posible Primitive Obsession en navegación (`MainActivity.kt`): varios
  booleanos/identificadores y JSON representan destinos dependientes.
  Se conserva el patrón existente para no rediseñar navegación fuera del ticket.
- Posibles Data Clumps/Duplicated Code en clave local de salida
  `(accountId, conversationId)`. Se mantiene explícita la separación por cuenta;
  una abstracción adicional no es necesaria para esta entrega.

**Resumen:** dos infracciones corregidas; dos observaciones de diseño no bloqueantes.

## Spec

1. Recorrido alojado entre dos emuladores y captura de integración incompletos.
   **Pendiente**, expresamente registrado en `comprobacion-chat.md`; no se
   sustituye esta evidencia por pruebas con HTTP controlado.
2. Recuperar del servidor un envío confirmado no retiraba la salida pendiente,
   produciendo estados contradictorios. **Corregido** reconciliando autor,
   clave de petición y texto; regresión Compose observada fallar antes del arreglo
   y pasar después.
3. Atrás del sistema desde el acceso exigido al visitante cerraba la actividad,
   en vez de regresar al perfil. **Corregido** el handler de la puerta de acceso.
4. Fecha sin localización: **corregido**, también señalado en Standards.

No se identificó ampliación de alcance.

**Resumen:** tres problemas funcionales corregidos; queda una brecha de aceptación:
despliegue y evidencia real entre dos emuladores. La issue no se cierra.
