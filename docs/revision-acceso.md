# Revisión del acceso

Base confirmada por David: `main` (`530566d`).
Revisión inicial: `git diff main...34701f8`, dos revisores independientes.
Las observaciones se conservan por eje, sin mezclar sus prioridades.

**Nota posterior:** esta revisión evaluó el requisito original de dos proyectos.
David lo ha sustituido por un único proyecto/base para desarrollo, pruebas
funcionales y presentación, cuyos datos prepara él. Las referencias a dos
proyectos que siguen abajo son históricas, no requisitos vigentes. La
configuración inicial debe adaptarse; no se exige provisionar un segundo proyecto.

## Estándares

1. **Defecto P2:** el observador de Auth podía aplicar un evento antiguo después
   de esperar al mutex, sobrescribiendo una restauración correcta. Contradice
   la respuesta coherente de `docs/especificacion.md:136`.
2. **Juicio de diseño — Duplicated Code:** construcción/configuración/cierre de
   clientes repetidos en `IdentityTest.kt`; posible fixture pequeña.
3. **Juicio de diseño — responsabilidad dispersa:** restauración inicial en
   `AccessScreen` y regreso en `MainActivity`, coordinados por `firstStart`.

Resolución:
- P2 reproducido con una prueba sobre operaciones públicas de identidad/Auth,
  HTTP controlado y orden de concurrencia explícito. Antes del cambio:
  `CONNECTED` esperado, `SIGNED_OUT` observado. Ahora se descarta un evento que
  ya no coincide con el estado actual del SDK.
- Restauración concentrada en `MainActivity.onStart`, sin coordinación implícita
  con la composición.
- La fixture es una mejora menor pendiente, no una infracción documentada; se
  conservan explícitos los escenarios y su almacenamiento/HTTP por claridad.

**Resumen: 3 hallazgos iniciales; el peor era la carrera P2, corregida.**

## Especificación

1. **Defecto P2:** la misma carrera podía impedir restaurar una sesión válida.
   Ticket: «Una sesión válida se restaura […] resultados comprensibles».
2. **Defecto P2:** cualquier fallo de renovación automática se mostraba como
   problema de conexión, incluso un HTTP 500/503 del servicio.
   Ticket: «[…] fallo de conexión tienen resultados comprensibles».
3. **Aceptación parcial reconocida:** faltan dos proyectos alojados comprobados,
   acceso/reapertura desde APK contra ellos, ejecución de Checks en GitHub,
   observación API 26 y revisión visual con David. No son pruebas fallidas ni
   justifican afirmar acceso remoto verificado.

No se detectó ampliación injustificada de alcance. La lista de administradores
forma parte de las capacidades de identidad acordadas, no concede acceso a chats.

Resolución:
- Carrera corregida como se describe en Estándares.
- Causa de renovación traducida según SDK: `NetworkError` → conexión;
  `InternalServerError` → servicio. Prueba añadida con HTTP 503 real para el
  cliente SDK sobre transporte controlado: falló antes de la corrección y pasó después.
- Los pendientes de aceptación permanecen en
  [comprobación de acceso](comprobacion-acceso.md); el ticket no se declara cerrado.

**Resumen: 3 hallazgos iniciales; los dos defectos P2 corregidos, aceptación
alojada/visual todavía pendiente.**
