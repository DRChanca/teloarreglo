# Comprobación: catálogo y perfiles públicos

Fecha: 20/11/2026. Rama: `catalogoYPerfilesPublicos`.
Base de implementación: `ac098d273edd2c7823a18b09c6dcb983b93c0b56`.
Ticket: [Consultar catálogo y perfiles públicos](https://github.com/DRChanca/teloarreglo/issues/14).

## Observado

- TDD en las fronteras confirmadas: PostgreSQL/pgTAP, interfaz `Catalogue` contra
  HTTP controlado y pantallas Compose. Se observaron fallos iniciales por ausencia
  del esquema y de las interfaces antes de implementarlos.
- Inicialmente, migración aplicada **solo** a la pila local existente mediante
  `migration up`, sin reiniciar ni repoblar su base. Despliegue alojado posterior
  comprobado en el apartado siguiente.
- `supabase test db`: 19 aserciones correctas (13 catálogo y 6 identidad).
  Lectura pública por oficio/ID; pendiente oculto; acceso directo privado denegado;
  escrituras de visitante denegadas; capacidades y propiedad desde servidor;
  borrador permitido al propietario, rechazado a otro y sin efecto público.
- `contract_catalogue.py --local`: cuatro casos correctos sobre Auth/PostgREST
  reales. Carga transaccional de 18 perfiles, tres por oficio; lectura sin sesión;
  detalle, privacidad, perfil pendiente, escritura pública denegada y borrador
  protegido entre dos cuentas verificadas reales de la pila local.
  Solo crea/retira UUIDs y cuentas desechables de esa ejecución en loopback.
- `android.py check`: compilación Kotlin, tests JVM y lint correctos.
- Suite completa Gradle `:app:test :app:lint :app:assemblePruebasDebug
  :app:assemblePresentacionDebug`: correcta en las cuatro variantes de tests JVM;
  ambas APK debug generadas. Contrato HTTP local de identidad también correcto.
- `android.py ui-test --serial emulator-5560`: ocho pruebas correctas en el AVD
  **nuevo y desechable** `Teloarreglo_Catalogue_Disposable`, Android 15/API 35.
  Lista→perfil→volver, precios, carga, fallo→reintento→vacío, identidad y selector
  alemán conservado tras recrear la actividad y navegación durante un acceso
  pendiente. No se tocaron los AVD preparados.
  Compose sustituye el servicio externo: estas pruebas no demuestran la conexión
  de Android con la base alojada.

## Despliegue alojado comprobado — 20/11/2026

- David confirmó la ejecución mediante SQL Editor de la migración
  `20261120000100` y de la carga explícita. La comprobación inicial HTTP 404
  queda sustituida por los resultados siguientes.
- `python3 scripts/contract_catalogue.py`: tres casos de lectura correctos,
  uno omitido deliberadamente porque las escrituras se prueban solo en loopback.
  Se verificaron los 18 identificadores preparados, tres por oficio, detalle de
  Fontanería Nalón, referencias de precios, ausencia de campos privados, rechazo
  del acceso directo a datos privados y detalle vacío para un ID inexistente.
  No acredita un perfil pendiente real en la base alojada; ese caso se mantiene
  comprobado con fixtures locales.
- Acceso Auth real con la cuenta configurada y lectura de `current_account()`:
  `professional_profile_id` coincide con Fontanería Nalón y `can_hire=true`.
  Solo se cerró la sesión creada para esta comprobación (`scope=local`),
  sin modificar perfiles ni cerrar las sesiones del emulador.
- No se reiniciaron, borraron ni sobrescribieron datos para comprobar el despliegue.

## No comprobado / pendiente de aprobación

- Elegir o preparar otra cuenta verificada distinta como cliente.
- Aceptación visual del contenido de los 18 perfiles e ilustraciones. Los otros
  17 perfiles están sin reclamar hasta asociarlos expresamente a cuentas.
- **Revisión visual con David de las primeras pantallas de lista y perfil reales**,
  antes de ampliar el diseño. Tener tests Compose correctos no equivale a esa
  aprobación. No se han ampliado búsqueda por proximidad, mapas, chat ni panel.
- Recorrido Android conectado al catálogo alojado, persistencia al reabrir y
  comprobación manual ES/EN/FR/DE. Los recursos están entregados en los cuatro
  idiomas; los contenidos originales de los perfiles no se traducen.
- API 26 se mantiene en la matriz de CI, no se ejecutó localmente en esta sesión.
  No se ha ejecutado el workflow remoto ni publicado ninguna APK.
- Reconciliar el historial de migraciones de CLI antes de un futuro `db push`;
  SQL Editor no lo actualiza. La versión comunicada por David se registra aquí,
  no se ha marcado automáticamente como aplicada en el historial remoto.

La issue permanece abierta: implementación local y comprobación alojada/revisión
humana se distinguen expresamente. Preparación y contratos:
[catálogo](catalogo-contrato.md).

## Revisión de código

### Normas

La revisión independiente detectó un bloqueo posible al abandonar Cuenta durante
una petición: su coroutine estaba ligada a la pantalla. Se corrigió inyectando
el scope de aplicación en las operaciones de identidad y en la restauración.
Se añadió una regresión Compose con respuesta retrasada y salida/vuelta a Cuenta.
También se limitó el dibujo de las ilustraciones a sus límites visuales.

### Especificación

Sin defectos adicionales de código confirmados. Permanecen los límites de
aceptación alojada, idiomas y revisión humana indicados arriba. La especificación
general menciona fotos de trabajos anteriores; la issue admite imágenes
autorizadas y esta entrega incluye ilustraciones, no fotos. David debe validar
esa elección de contenido antes de aceptar el catálogo.

## Ajustes solicitados durante la revisión de lista/perfil

David confirmó que ve la lista alojada y pidió dirección comercial ficticia,
texto de precios breve, idiomas solo cuando haya alguno adicional al español
y más referencias de servicios, incluida visita de valoración.

- Se conserva la distinción comercial/pública frente al domicilio privado.
  La migración nueva `20261120000200` añade una columna opcional, sin copiar datos
  privados. La nueva APK también admite respuestas antiguas sin esa columna.
- TDD: fallo observado de DTO sin dirección, de SQL sin columna y de pantalla sin
  dirección; después pasaron las pruebas específicas de cada frontera.
- El contrato HTTP local de seis casos comprueba los perfiles ampliados, visitas
  con coste/gratuitas y que la actualización respeta datos preparados. Ante una
  edición concurrente se observó el aborto de toda la transacción, incluido el
  primer perfil ya actualizado dentro de ella.
- Suite SQL completa: 22 aserciones correctas. Suite Compose completa: nueve
  pruebas correctas en el emulador desechable API 35. Se añadió desplazamiento
  explícito en dos aserciones de cuenta para evitar depender de la posición
  visible tras cerrar el teclado; no se cambió el acceso de producción.
- Suite JVM completa, lint de ambas variantes y ambas APK debug: correctos con
  las versiones de Gradle/AGP presentes en el entorno de David.
- Los datos nuevos están en `supabase/catalogue/details.json`. No se ha vuelto a
  cargar ni modificado automáticamente el catálogo alojado. Falta ejecutar el
  SQL revisado y repetir allí `contract_catalogue.py --details`.
- Se mantienen intactos los cambios de Gradle/wrapper hechos por David en su
  entorno; no forman parte de esta modificación.

### Standards

Revisión independiente del cambio `50a7137...a809e19`: sin incumplimientos
accionables de normas ni problemas sustanciales de mantenibilidad. Se revisaron
en particular privacidad, conservación de datos preparados y despliegue manual.

### Spec

Sin defectos de implementación confirmados ni ampliaciones de alcance. Cumple
los ajustes de la revisión visual; sigue pendiente ejecutar la actualización
alojada y revisar su resultado con David. Total: cero hallazgos por eje.
