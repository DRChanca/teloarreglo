# Comprobación: catálogo y perfiles públicos

Fecha: 20/11/2026. Rama: `catalogoYPerfilesPublicos`.
Base de implementación: `ac098d273edd2c7823a18b09c6dcb983b93c0b56`.
Ticket: [Consultar catálogo y perfiles públicos](https://github.com/DRChanca/teloarreglo/issues/14).

## Observado

- TDD en las fronteras confirmadas: PostgreSQL/pgTAP, interfaz `Catalogue` contra
  HTTP controlado y pantallas Compose. Se observaron fallos iniciales por ausencia
  del esquema y de las interfaces antes de implementarlos.
- Migración aplicada **solo** a la pila local existente mediante `migration up`,
  sin reiniciar ni repoblar su base.
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

## No comprobado / pendiente de aprobación

- `contract_catalogue.py` contra la única base alojada, **solo lectura**:
  las llamadas a `catalogue` devolvieron HTTP 404. La migración y la carga nueva
  no están desplegadas; no se afirma un catálogo alojado operativo.
- Revisar y aplicar la migración compartida y la carga explícita con David.
  Elegir la cuenta verificada propietaria de Fontanería Nalón y otra cuenta
  cliente distinta. No se han creado usuarios ni modificado datos alojados.
- Aprobar contenido de los 18 perfiles e ilustraciones originales. Los otros
  17 perfiles están sin reclamar hasta asociarlos expresamente a cuentas.
- **Revisión visual con David de las primeras pantallas de lista y perfil reales**,
  antes de ampliar el diseño. Tener tests Compose correctos no equivale a esa
  aprobación. No se han ampliado búsqueda por proximidad, mapas, chat ni panel.
- Recorrido Android conectado al catálogo alojado, persistencia al reabrir y
  comprobación manual ES/EN/FR/DE. Los recursos están entregados en los cuatro
  idiomas; los contenidos originales de los perfiles no se traducen.
- API 26 se mantiene en la matriz de CI, no se ejecutó localmente en esta sesión.
  No se ha ejecutado el workflow remoto ni publicado ninguna APK.

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
