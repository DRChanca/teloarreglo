# Arquitectura y contratos de la demo

Estado: decisión aprobada, no implementada ni desplegada.
Fecha: 19 de noviembre de 2026.

Fuente: [Elegir arquitectura y contratos para desarrollar la demo entre dos personas](https://github.com/DRChanca/teloarreglo/issues/6).
Actualización de aceptación y entrega: [Preparación del vídeo y paso a especificación](https://github.com/DRChanca/teloarreglo/issues/8).
Actualización posterior de David, 19/11/2026: **un único proyecto Supabase y una
única base alojada para desarrollo, pruebas funcionales y presentación**.
Prevalece sobre las referencias anteriores a dos proyectos; véase la
[decisión vigente](especificacion.md#actualización-una-única-base-de-datos-compartida).
Reglas funcionales: [especificación](especificacion.md), especialmente apartados 1 y 14.
La resolución de la issue es la referencia de aprobación; este documento desarrolla sus contratos para compartirlos entre las dos líneas de trabajo. No constituye un desglose de tickets.

## 1. Elecciones y costes

| Parte | Elección |
| --- | --- |
| Android | Kotlin, Jetpack Compose, Android 8/API 26 o posterior; una app para cliente y profesional |
| Datos y operaciones | Supabase Free en Frankfurt: PostgreSQL, Auth, Storage, Realtime, funciones SQL/RPC y Edge Functions |
| Autenticación | Google y correo/contraseña con sesión persistente; verificación y recuperación por SMTP Brevo Free |
| Automatización | Supabase Cron, comprobación cada minuto y fechas persistidas en servidor |
| Avisos | Registro en base de datos y transporte Android mediante FCM |
| Panel web | React y TypeScript, web estática en Cloudflare Pages Free |
| Mapa | Google Maps SDK Android sin map ID; navegación externa mediante Google Maps |
| Geografía | PostGIS para concejos/cobertura y distancia; H3 mediante h3-js en servidor, sujeto a prueba en Edge Functions |
| Multimedia | Compresión de imágenes Android y vídeo mediante Media3 Transformer |
| Traducción | Recursos ES/EN/FR/DE para Android y panel; no traducción automática de contenidos |
| Distribución | APK instalable directamente, sin depender de Google Play para la presentación |
| Colaboración | GitHub, ramas por ticket, revisión, comprobaciones automáticas y migraciones versionadas |

- Objetivo orientativo: unos 10 €/mes, no coste garantizado. No se autoriza con esta decisión contratar planes ni introducir tarjetas.
- Supabase Free: 500 MB de base por proyecto, 1 GB de archivos, 5 GB de salida y 5 GB desde caché contabilizados por separado; dos proyectos activos gratuitos. Puede pausarse por una semana de inactividad y restringir servicios por cuotas. No tiene garantía de disponibilidad. Pro parte de 25 USD/mes antes de impuestos y extras.
- Vigilar almacenamiento y descargas de vídeos; no borrar automáticamente el historial de presentación para ahorrar espacio.
- Google Maps exige proyecto y cuenta de facturación vinculada, aunque el SKU Maps SDK sin map ID tenga uso sin coste según la tarifa consultada. No se habilitan inicialmente Places, rutas ni geocodificación. Restringir la clave a la aplicación y configuración de firma.
- FCM se utiliza solo como transporte gratuito: no implica contratar Blaze ni usar Firebase para datos, archivos o autenticación.
- Brevo Free admite hasta 300 correos/día, sujeto a aprobación de cuenta y límites adicionales de Supabase Auth. Preferir dominio propio autenticado. Sin dominio, comprobar remitente verificado, posible sustitución de dirección y entrega real. Cualquier compra de dominio requiere aprobación aparte.
- Si límites o incompatibilidades impiden cumplir una función aprobada, volver al usuario. No cambiar silenciosamente alcance, proveedor o plan.

## 2. Módulos e interfaces

Organización por recorridos completos, no una persona solo con pantallas y otra solo con servidor. No se exige convertir cada módulo en un artefacto Gradle independiente.

| Módulo | Interfaz que ofrece a sus consumidores | Reglas que concentra |
| --- | --- | --- |
| Identidad | Acceder, restaurar sesión y consultar capacidades de la cuenta | Identidad Supabase; perfil profesional opcional; rol administrativo restringido |
| Catálogo | Buscar por ubicación/cobertura y consultar versión pública de perfil | Concejos, proximidad, perfiles aprobados y revisión de cambios públicos |
| Conversación | Abrir conversación, enviar/leer mensajes y adjuntos | Participantes, compresión, subida privada, persistencia y sincronización |
| Citas | Solicitar, proponer, aceptar/rechazar, comenzar, marcar realizado y confirmar | Reservas, versiones, importes simulados, incidencias, cierre y habilitación de reseña |
| Administración | Revisar perfiles e incidencias con motivo y resultado | Acceso limitado, pruebas aportadas, decisiones y auditoría |
| Avisos | Consultar eventos y registrar dispositivos; entregar avisos | Recordatorios, reintentos y relación con eventos ya confirmados |

Android y panel consumen las mismas operaciones de dominio. La presentación puede validar entradas para ayudar al usuario, pero no decide por su cuenta permisos, reservas, saldos ni transiciones.

## 3. Contratos comunes

Son contratos lógicos compartidos. Los nombres de tablas, RPC y DTO definitivos y las versiones exactas de bibliotecas se fijarán al concretar la especificación implementable, sin cambiar estas reglas.

### Datos compartidos

| Dato | Contenido mínimo e invariantes |
| --- | --- |
| Cuenta | Identificador estable de Auth; capacidades verificadas en servidor. No aceptar un rol enviado por el cliente como prueba de autorización |
| Perfil profesional | Propietario, servicios, cobertura por códigos de concejo, ubicación registrada con privacidad y versión pública aprobada; cambios en revisión separados de la versión visible |
| Conversación | Identificador y participantes; cada mensaje tiene autor, fecha de servidor y texto o referencia a adjunto privado |
| Adjunto | Propietario, conversación/incidencia asociada, referencia de Storage, formato, bytes y dimensiones/duración aplicables. No exponer URL pública permanente de contenido privado |
| Cita | Participantes, dirección privada y coordenadas, intervalo, propuesta aceptada, versión y marcas de inicio/realizado/finalización/cancelación |
| Propuesta | Cita, autor, versión, alcance, intervalo, dirección, importe en céntimos EUR, modalidad de pago y resultado de aceptación/rechazo |
| Movimiento simulado | Cita, tipo, importe en céntimos, operación de origen y fecha del servidor; historial que distingue pagado, pendiente, devuelto y entregado |
| Incidencia | Cita, autor, motivo, pruebas aportadas, estado de revisión y decisiones separadas sobre realización y dinero |
| Reseña | Cita y autor únicos por cita, estrellas 1–5 y contenido; habilitación comprobada por servidor |
| Evento/decisión | Operación, actor, fecha y resultado; motivo administrativo obligatorio; avisos asociados sin duplicar el efecto de dominio |

- Importes enteros en céntimos, nunca números decimales de coma flotante. Todos los movimientos son simulados: no integrar cobros ni pedir datos bancarios reales. En la presentación universitaria se explica por voz, sin exigir banners globales de demo.
- Fechas y vencimientos en UTC de servidor; mostrar según zona horaria/idioma sin alterar el instante.
- Intervalos de agenda con principio incluido y final excluido: dos citas contiguas pueden tocarse sin solaparse.
- Propuestas y cambios versionados: aceptar una versión no autoriza otra posterior.
- Una incidencia no borra la etapa previa de la cita. Realización, decisión del pago y habilitación de reseña son dimensiones distintas: una devolución no prueba que el servicio no ocurrió.

### Operaciones y resultados

Las operaciones sensibles reciben identificador del recurso, versión esperada cuando corresponda, datos del cambio y clave de idempotencia. La identidad procede de la sesión, no de un campo de autor confiado al navegador o teléfono.

| Operación lógica | Actor y comprobaciones esenciales |
| --- | --- |
| Solicitar cita | Cliente; crea solicitud sin reserva definitiva y comparte dirección únicamente con el profesional implicado |
| Proponer/revisar condiciones | Profesional, o parte autorizada para proponer cambios; conserva condiciones y reserva aceptadas mientras no se acepte el cambio |
| Aceptar/rechazar propuesta | Otra parte correspondiente; verifica versión, cobertura e intervalo. Confirmación de pago integrado simula total o diferencia dentro de la misma operación coherente |
| Comenzar / marcar realizado | Profesional de la cita; valida etapa y cambios pendientes. Realizado fija origen del plazo de 48 horas |
| Cancelar antes de comenzar | Cualquiera de las partes; devolución total simulada, liberación de reserva y registro sin duplicar movimientos |
| Confirmar realización | Cliente; no libera si hay incidencia bloqueante o importe ya entregado |
| Abrir/aportar a incidencia | Parte de la cita; pruebas expresamente compartidas con la otra parte y administración, no acceso al resto de la conversación |
| Resolver incidencia | Administrador; motivo, saldo pendiente y decisiones separadas sobre realización y reparto del dinero; no revierte fondos ya entregados |
| Aprobar/rechazar perfil | Administrador; decisión sobre versión revisada, motivo y aviso; no reescribe datos profesionales |
| Publicar reseña | Cliente de una cita habilitada; una por cita y sin generación automática |
| Procesar vencimientos | Ejecutor de servidor; mismos controles de saldo, incidencias e idempotencia que las operaciones manuales |

El resultado incluye recurso/versión resultante o un error estable que Android y panel pueden traducir. Distinguir al menos: no autenticado, sin permiso, versión desactualizada, horario ocupado, fuera de cobertura, estado incompatible, incidencia bloqueante, archivo no admitido o demasiado grande, cuota/servicio no disponible y fallo de conexión.

Repetir una operación con la misma clave y datos devuelve su resultado sin repetir efectos. No reutilizar la misma clave para otra intención o contenido. Si la respuesta se pierde, recuperar el resultado antes de mostrar una confirmación o intentar un nuevo movimiento.

### Concurrencia y permisos

- Reservar o cambiar una cita, ajustar el saldo simulado y registrar el resultado se coordinan en una transacción de PostgreSQL. Una comprobación previa en Android no protege frente a dos confirmaciones simultáneas.
- Impedir intervalos incompatibles de una misma agenda mediante restricciones de base de datos; una solicitud pendiente no consume reserva. Si un cambio falla, conservar íntegramente la cita anterior.
- Coordinar confirmación, incidencia, vencimiento y resolución administrativa sobre el mismo estado protegido. Ninguna carrera puede entregar dos veces, repartir más del saldo pendiente o liberar un pago bloqueado.
- RLS y políticas de Storage para lecturas y escrituras privadas; RPC con privilegios mínimos y comprobaciones de identidad/propiedad. Si una función necesita privilegios elevados, restringir su ejecución, fijar su entorno de resolución y verificar expresamente al actor.
- Ninguna clave administrativa, contraseña de base, secreto SMTP o credencial FCM se incorpora a APK, bundle web o Git. Los clientes usan clave publicable y sesión, sujetas a autorización.
- El administrador solo puede revisar datos necesarios de la cita, condiciones, movimientos y pruebas aportadas. El rol no concede acceso general a chats y adjuntos.
- Un evento Realtime o push notifica que algo cambió; no es prueba suficiente para ejecutar una transición ni fuente de verdad del saldo.

## 4. Automatización y notificaciones

- Persistir en servidor el instante de realizado y sus vencimientos de 24 y 48 horas.
- Para aceptación se permite un intervalo de cierre de 5 minutos en una prueba aislada, con recordatorio previo. Con la decisión de base única, no se exige otro proyecto alojado ni se aplica ese intervalo globalmente a la base compartida o sus citas preparadas. Acordar el aislamiento antes de desplegarlo; el funcionamiento normal conserva 24/48 horas. No se exige observar una espera real de 48 horas ni se afirma haberla observado al probar el intervalo reducido.
- Supabase Cron comprueba cada minuto los registros elegibles. No mantener una Edge Function esperando 48 horas ni depender del teléfono.
- Nunca cerrar antes del vencimiento. En operación normal se procesa en la siguiente ejecución disponible; no se promete exactitud al segundo ni disponibilidad que Free no garantiza.
- Al reanudar tras un fallo, recuperar vencimientos atrasados sin duplicar recordatorios, cierres o entregas y respetando incidencias.
- Registrar avisos como consecuencia de operaciones confirmadas y reintentar su envío separado del movimiento de dinero. Un fallo push no revierte una cita ni pierde el aviso dentro de la app.
- Validar habilitación efectiva de Cron en Free. No asumir que sus propias tareas impidan la pausa por inactividad.
- FCM requiere permisos de notificación y gestión de tokens. No se garantiza entrega puntual en un dispositivo sin conexión o restringido.

## 5. Ubicación, mapas y multimedia

- GPS puntual como opción principal; alternativa manual de concejo y pin si se deniega permiso, falta precisión o el trabajo es en otro domicilio.
- Dirección exacta de cita como texto privado más coordenadas; no hay autocompletado ni conversión automática de dirección escrita a coordenadas.
- Concejos con polígonos oficiales IGN/CNIG y PostGIS. Conservar códigos INE como texto, atribución y edición; transformar correctamente el sistema de referencia.
- Cerca de una frontera o con GPS incierto, pedir confirmación de concejo. Ni el mapa ni H3 resuelven por sí solos la división administrativa.
- Primero filtrar cobertura de servicio; organizar candidatos mediante H3 y celdas vecinas y ordenar por distancia geográfica entre coordenadas originales. Comprobar bordes y expansión para no perder candidatos ni presentar un orden incorrecto.
- H3 en servidor mediante h3-js, sujeto a prueba de importación y ejecución en Supabase Edge Functions. No asumir una extensión H3 nativa disponible en Postgres.
- Google Maps representa resultados; un enlace de navegación abre Google Maps fuera de Teloarreglo. La aplicación no calcula rutas ni tiempos por carretera. Respetar privacidad de domicilio profesional y cliente.
- Fotos JPEG/PNG, hasta 1600 píxeles en el lado mayor y 2 MB tras compresión; vídeo MP4 compatible, hasta 40 segundos (decisión de 2026-11-23, con 499 ms de margen), 720p y 20 MB. No ampliar archivos de menor resolución.
- Progreso, cancelación y original intacto. Si compresión o formato fallan, informar y no subir silenciosamente el original.
- No declarar envío confirmado hasta recibir respuesta del servidor. Conexión necesaria para operaciones compartidas; no se promete modo completo sin internet.

## 6. Dos personas, integración y entrega

- Responsables de futuros tickets: `DRChanca` y `marcosarguellesrivera`, quien ya tiene acceso de escritura al repositorio.
- Un responsable por ticket, con dependencias nativas en GitHub. No se crea todavía el desglose final.
- Acordar y compartir estos contratos antes de trabajar en paralelo. Cada recorrido incluye presentación, persistencia y permisos.
- Primera integración: acceso, perfil y conversación persistente entre dos cuentas en dos emuladores contra Supabase. Ampliar después con archivos, citas y administración.
- Ramas por ticket, revisión y comprobaciones en GitHub. Coordinar cambios de interfaces, migraciones y archivos compartidos antes de modificarlos.
- Un único proyecto Supabase Free en Frankfurt y una única base alojada para desarrollo, pruebas funcionales y presentación. David prepara los datos de la presentación en esa misma base.
- Revisar migraciones antes de aplicarlas a la base compartida. No hay promoción a otra base ni reinicio, repoblación o borrado automáticos de los datos preparados. Las pruebas que necesiten aislamiento se ejecutan localmente, sin exigir otro proyecto alojado.
- Secretos de despliegue fuera de Git y de los clientes; builds del panel Cloudflare Pages desde el mismo repositorio.
- Conservar APK identificada para grabación y distribución directa. El diseño visual no se aprueba con esta decisión.
- Calendario actualizado: recorrido integrado el 27/11, pruebas el 28/11, correcciones/grabación el 29–30/11 y entrega del vídeo el 1/12/2026; prueba posterior de la aplicación.
- Revisar primeras pantallas reales con el usuario antes de extender el diseño. Apariencia cuidada, navegación fluida, progreso y respuesta a acciones, sin bloqueos ni confirmaciones falsas.
- Conservar versiones de APK, panel y servidor utilizadas al grabar y comprobar disponibilidad. No realizar despliegues improvisados durante la grabación.
- Para la presentación universitaria las simulaciones y perfiles ficticios se explican por voz; no se exigen avisos globales de demo. No cambia la prohibición de cobros y documentos personales reales.

## 7. Validaciones pendientes, no decisiones omitidas

La arquitectura está elegida, pero todavía no está demostrada ni provisionada. Las siguientes comprobaciones condicionan su ejecución y alimentarán la especificación implementable, no autorizan un cambio automático:

- Compilación Kotlin/Compose y dependencias compatibles con API 26; sesión, Auth/Storage/Realtime y reconexión con el SDK comunitario supabase-kt.
- Dos emuladores simultáneos; actualmente solo se ha observado uno configurado. Comprobar multimedia, Google OAuth, firma y retorno de sesión.
- H3 en Edge Functions, PostGIS e importación de concejos, orden/cobertura y bordes de celda.
- Cron real y avisos con apps cerradas, recuperación tras interrupción y coordinación con incidencias. Evidencia de ejecución mediante intervalo reducido de 5 minutos en pruebas y comprobación de configuración 24/48 horas en presentación; no se exige una espera real de 48 horas.
- Permisos de cuenta/administración, RLS y Storage contra terceros; conflictos concurrentes y reintentos; revisión de perfil e incidencia de extremo a extremo.
- Pruebas automáticas de contratos, permisos y transacciones; tiempos controlados solo en pruebas aisladas. No introducir un botón de adelanto de reloj en la demo.
- Correo Brevo aprobado y remitente/dominio verificable; confirmación, recuperación y límites. No desactivar verificación como atajo.
- Proyecto Google Cloud, billing y restricciones de Maps; configuración FCM y recepción de avisos con app cerrada.
- Cuotas y consumo de cada entorno y servicio. Activaciones, credenciales y cualquier compra requieren pasos posteriores explícitos.
- Dependencia de red aceptada sin conexión alternativa ni grabación de respaldo obligatorias por ahora; no hay modo completo sin conexión. Aceptación por lista verificable de comportamientos según la [especificación](especificacion.md), no por apariencia de pantallas.
- El mapa de decisiones deja paso a una especificación implementable y después tickets aprobados con dependencias; cerrar el mapa no acredita implementación ni comprobaciones ya realizadas.

## Fuentes técnicas consultadas

- [Investigación previa de alternativas](https://github.com/DRChanca/teloarreglo/blob/research/base-android-demo/docs/research/base-android-demo.md), anterior a las ampliaciones de reloj, compresión y panel.
- Supabase: [tarifas](https://supabase.com/pricing), [pausa Free](https://supabase.com/docs/guides/platform/free-project-pausing), [Kotlin comunitario](https://supabase.com/docs/reference/kotlin/introduction), [Cron](https://supabase.com/docs/guides/cron), [regiones](https://supabase.com/docs/guides/platform/regions), [SMTP](https://supabase.com/docs/guides/auth/auth-smtp).
- Servidor: [PostGIS](https://supabase.com/docs/guides/database/extensions/postgis), [funciones y permisos](https://supabase.com/docs/guides/database/functions), [restricciones de intervalos](https://www.postgresql.org/docs/current/rangetypes.html#RANGETYPES-CONSTRAINT), [entornos y despliegue](https://supabase.com/docs/guides/deployment/managing-environments).
- Geografía: [h3-js](https://github.com/uber/h3-js), [límites IGN/CNIG](https://centrodedescargas.cnig.es/CentroDescargas/limites-municipales-provinciales-autonomicos), [licencia IGN](https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf).
- Google: [Maps Android y billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing), [SKU Maps](https://developers.google.com/maps/billing-and-pricing/sku-details/maps), [FCM y tarifas](https://firebase.google.com/pricing), [Supabase y push](https://supabase.com/docs/guides/functions/examples/push-notifications).
- Multimedia: [Media3 Transformer](https://developer.android.com/media/media3/transformer).
- Panel: [Cloudflare Pages y monorepos](https://developers.cloudflare.com/pages/configuration/monorepos/), [límites](https://developers.cloudflare.com/pages/platform/limits/).
- Correo: [Brevo Free](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan), [SMTP](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP), [remitentes no autenticados](https://help.brevo.com/hc/en-us/articles/14925263522578-Comply-with-Gmail-Yahoo-and-Microsoft-s-requirements-for-email-senders).
