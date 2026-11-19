# Base Android y servicios gestionados para una demo de bajo coste

Fecha de consulta: **19 de noviembre de 2026**. Investigación para [Investigar una base Android y servicios gestionados para una demo de bajo coste](https://github.com/DRChanca/teloarreglo/issues/2), dentro de [Teloarreglo: camino hacia la demo universitaria del 28 de noviembre](https://github.com/DRChanca/teloarreglo/issues/1).

**Estado: investigación completa; elección de arquitectura pendiente del usuario.** No se ha implementado, contratado ni desplegado nada. Las cifras son las publicadas al consultar, no una garantía futura ni una oferta en euros.

## 1. Contexto y conclusión

Se han leído el ticket, sus comentarios (sin comentarios al iniciar), el mapa y la versión local de `docs/especificacion.md`, cuya actualización universitaria aún no estaba en `main`. Este informe no incorpora ni modifica ese trabajo local.

El objetivo es una APK Android con datos compartidos reales: búsqueda → perfil → chat → solicitar → confirmar → finalizar cita → reseña, usando dos cuentas y profesionales ficticios identificados como demo. Pagos, suscripción y verificación comercial se simulan; **autenticación, persistencia y autorización no**. Interfaz ES/EN/FR/DE, idioma del dispositivo con alternativa castellana y selector manual; contenido de usuarios sin traducción automática.

Hay tres combinaciones técnicamente viables, con condiciones distintas:

| Alternativa | Composición completa | Ventaja para esta demo | Riesgo determinante |
| --- | --- | --- | --- |
| A. Nativa + Firebase | Kotlin, Jetpack Compose; Firebase Auth, Firestore Standard, Cloud Storage; reglas; alojamiento gestionado de esos servicios | Integración Android oficial y caché/sincronización Firestore | Los adjuntos exigen Blaze y una cuenta de facturación; no hay techo global automático de 10 € para Firestore/Storage |
| B. Multiplataforma + Supabase | Flutter/Dart; Supabase Auth, Postgres, Realtime y Storage; RLS; alojamiento Supabase Cloud | SDK Flutter oficial y modelo relacional útil para citas/reseñas; Free sin cargos del proveedor | Aprender Flutter y SQL/RLS; pausa por inactividad, correo restringido, cuota de archivos |
| C. Nativa + Supabase | Kotlin, Compose; mismos servicios Supabase que B mediante `supabase-kt` | Android nativo con las mismas restricciones de gasto de Free y relaciones SQL | El cliente Kotlin es comunitario, no oficial; revisar compatibilidad y ejemplos antes de comprometer el plazo |

Fundamento técnico: documentación de Compose, Firebase Android, Supabase Flutter y Supabase Kotlin [S1–S4]. La valoración de esfuerzo es **juicio de esta investigación**, no una comparación medida de productividad. Ninguna opción elimina construir las pantallas, el modelo de estados o las pruebas de permisos.

**Lectura condicional, no elección:** A encaja si se prefiere una cadena Android oficial y se acepta facturación variable vigilada; B si se prioriza un SDK multiplataforma oficial y Free con restricciones; C si se quiere Kotlin y Postgres aceptando el soporte comunitario. Flutter + Firebase también es compatible oficialmente [S5]: hereda el coste/riesgo de A y las herramientas de Flutter; no se cuenta como cuarta alternativa.

## 2. Plazo, herramientas locales y coste de aprendizaje

Del 19 al 28 hay nueve días de diferencia: aproximadamente 54 horas-persona si se trabaja nueve jornadas de seis horas combinadas; contar ambos extremos daría 60. No son horas netas: instalación, integración, traducción y ensayos consumen parte. **No se puede asegurar la entrega por documentación**; el riesgo principal es integración y permisos, no capacidad bruta del backend.

- **Kotlin/Compose (A/C):** Android Studio estable, SDK Android, herramientas de compilación/Gradle y JDK compatible con el proyecto, Git, emulador o teléfono con depuración USB. Compose dispone de plantilla Empty Activity en Kotlin [S1]. Firebase se registra con el `applicationId`, configura `google-services.json` y recomienda BoM; los antiguos módulos `*-ktx` dejaron de actualizarse en julio de 2025: no copiar tutoriales que los añadan [S2].
- **Flutter (B):** añade Flutter SDK/Dart y su PATH al conjunto Android; el plugin de Android Studio por sí solo no basta. Validar `flutter doctor`, licencias Android y dispositivo reconocido. La guía actual pide plataforma API 36 y herramientas SDK/NDK/CMake [S6]. No exige preparar iOS ni comprar cuenta Apple para una APK Android.
- **Dispositivos:** verificar las versiones efectivas de SDK/plugins antes de fijar `minSdk`. El setup Firebase actual pide Android 7/API 24 o superior; algunos productos requieren Play services [S2, S5]. La cifra mínima de la plantilla Compose no determina la compatibilidad de toda la app.
- **Máquina:** comprobar requisitos oficiales de Android Studio para el sistema concreto y espacio de SDK/emuladores [S7]. No se ha auditado RAM, disco, JDK ni disponibilidad de dos teléfonos. Si el emulador resulta pesado, probar con dos dispositivos físicos; eso es una mitigación, no una constatación sobre el equipo.
- **Backend:** las tres opciones alojan Auth, datos y objetos en el proveedor; no requieren un VPS propio ni un servidor siempre encendido del equipo. Un backend local/emulador ayuda a probar, pero no sustituye el servicio compartido remoto de la demostración. Docker/CLI Supabase sería trabajo adicional para pruebas SQL locales; no es requisito del cliente conectado al proyecto Cloud [S3, S8].

Separación sugerida para estimar viabilidad, no plan de implementación aprobado: una persona mantiene datos, autenticación y permisos, otra UI y traducciones; ambas integran una sección vertical diariamente con IDs/estados/errores acordados. Dividir “toda la app cliente” frente a “todo el servidor” y juntarlos el último día deja sin evidencia el recorrido real.

## 3. Cuotas, archivos y facturación

### A. Firebase

Valores gratuitos de **Firestore Standard**, por proyecto y para exactamente una base gratuita; operaciones diarias reiniciadas aproximadamente a medianoche del Pacífico [S9]:

| Recurso | Cuota sin coste publicada |
| --- | --- |
| Datos almacenados | 1 GiB |
| Lecturas | 50.000 documentos/día |
| Escrituras / borrados | 20.000 / 20.000 documentos/día |
| Transferencia saliente | 10 GiB/mes |
| Tamaño máximo de documento | 1 MiB: no guardar fotos/vídeos dentro del documento |

Spark no necesita método de pago, pero **Cloud Storage exige Blaze** [S10, S11]. Por tanto “Firebase gratuito sin tarjeta con chat multimedia alojado en Firebase Storage” no es una combinación válida hoy. Las cuotas antiguas de buckets `*.appspot.com` no deben aplicarse a un proyecto nuevo.

Para buckets nuevos `*.firebasestorage.app`, la tabla de precios publica 5 GB-mes almacenados, 100 GB/mes descargados, 5.000 operaciones de subida y 50.000 de descarga al mes sin coste **solo en `us-central1`, `us-west1`, `us-east1`** [S10]. Un bucket europeo sigue la tarifa regional de Google Cloud Storage; no presupuestar esos mismos gratuitos en Europa [S11]. Elegir región implica revisar ubicación de datos, latencia y requisitos de privacidad, no solo el coste.

Auth por correo/contraseña y otros servicios de autenticación no telefónica están disponibles; la tabla distingue la modalidad con Identity Platform (50.000 MAU gratuitos). SMS se factura aparte: no aporta valor a los teléfonos ficticios de esta demo [S10, S12]. FCM y App Distribution figuran sin coste; Cloud Functions requiere Blaze y puede añadir compilación, artefactos y ejecución aunque exista franquicia [S10].

**Coste:** sin tarifa mensual fija de entrada para Blaze, consumo potencialmente sin cargo dentro de todas las franquicias aplicables, pero **sin garantía de quedar por debajo de 10 €/mes**. Lecturas de listeners, reconexiones, comprobaciones de reglas, descargas repetidas y bucles de funciones pueden consumir cuotas. Los presupuestos de alertas **no paran servicios**. La documentación actual sí contempla spend caps para AI Logic, App Hosting, Functions y Extensions, **no un límite general para Firestore y Storage** [S13]. No confundir esa novedad con un techo total de factura. Revisar moneda, impuestos y configuración concreta antes de activar billing; no contar créditos promocionales como coste sostenible.

### B/C. Supabase Cloud Free

Cuotas publicadas en Free [S14]:

| Recurso | Límite/franquicia publicada |
| --- | --- |
| Proyectos activos gratuitos | 2 |
| Base Postgres | 500 MB por proyecto |
| Auth | 50.000 MAU |
| Archivos almacenados | 1 GB |
| Salida | 5 GB egress y 5 GB cached egress, categorías separadas |
| Subida máxima por archivo | 50 MB |
| Realtime | 200 conexiones pico; 2 millones de mensajes/mes; mensaje máximo 256 KB |
| Edge Functions | 500.000 invocaciones incluidas |
| Inactividad | Pausa tras una semana |

“API requests ilimitadas” no elimina los límites de base, transferencia ni Realtime. No sumar las dos clases de egress como una bolsa libremente intercambiable de 10 GB. Fotografías y vídeos van a Storage; mensajes Realtime llevan eventos/metadatos, no el vídeo.

**Coste:** Supabase declara que Free no genera cargos; el riesgo al agotar cuotas es restricción del servicio, no escalado automático gratuito infinito [S15]. Es compatible con gastar 0 € en ese proveedor si se permanece en Free, **no garantiza disponibilidad de una demo ni el coste de SMTP, dominio u otros servicios externos**.

El siguiente escalón Pro parte de **25 USD/mes**, antes de impuestos; incluye 10 USD de créditos de cómputo que cubren una instancia Micro. No es un plan de 10 €; más proyectos, cómputo y extras pueden sumar. Su Spend Cap cubre determinados excesos de uso, pero no cómputo, dominios y otros extras contratados [S14, S15]. No promover a Pro automáticamente como “arreglo barato” de la pausa.

Free no incluye copias automáticas según la tabla de planes [S14]. Para una demo: conservar semillas reproducibles y una exportación propia de los datos de ensayo, comprobar recuperación y reactivar el proyecto con antelación si se pausó. No basta con guardar la APK.

### Sobre fotos y vídeos, en las tres opciones

Ambos almacenamientos sirven archivos, pero no se ha contratado ni demostrado un pipeline de transcodificación, análisis de contenido o streaming adaptativo. “Subir un vídeo” no equivale a que todos los formatos se reproduzcan en todos los teléfonos.

**Propuesta técnica a aprobar, no nuevo requisito de producto:** foto hasta 2 MB, vídeo MP4 H.264/AAC corto hasta 20 MB, carga y reproducción manual sin autoplay, paginación de chat y limpieza del entorno tras la evaluación con consentimiento. Así cabe un vídeo de ensayo por debajo de 50 MB de Free. Validar MIME/tamaño en servidor/bucket/reglas, no solo en el selector Android; MIME declarado no prueba contenido seguro [S16, S17]. El límite de duración requiere inspección adicional, no se deduce de los bytes.

Ejemplo de dimensionado **hipotético**, no uso medido: 40 fotos × 1 MB + 10 vídeos × 20 MB = 240 MB de objetos. Veinte descargas completas de ese conjunto ≈ 4,8 GB: el egress puede agotarse mucho antes que el almacenamiento, especialmente en Free. Repetir cien vídeos de 50 MB ya ronda 5 GB descargados. Las unidades GB/GiB, caché, metadatos y otros recursos impiden tratar estas cuentas decimales como límite exacto del proveedor.

## 4. Autenticación y permisos: qué sigue siendo trabajo de la app

**A:** Firebase permite alta e inicio por email/password; el alta inicia sesión, no implica por sí sola demostrar correo verificado [S12]. Comprobar el estado de verificación para las operaciones que lo exijan. Google es otra ruta posible, pero añade configuración de proveedor y certificados: ensayar la firma de la APK final, no solo debug.

**B/C:** Supabase Auth admite usuarios de correo y proveedores sociales; el correo integrado restringe destinatarios a miembros del equipo de la organización, **actualmente dos mensajes/hora, sin SLA**, y no es SMTP de producción [S18]. Para las dos cuentas de ensayo se puede usar sus direcciones reales autorizadas y verificarlas antes del acto; para altas de público se necesita SMTP propio u otra ruta acordada y probada. No desactivar verificación silenciosamente ni llamar “verificado” a un usuario creado administrativamente sin demostrar ese flujo. Los enlaces de confirmación/OAuth requieren redirect/deep links que vuelvan a la app [S3]. SMTP propio puede añadir coste y configuración de dominio; no se ha presupuestado ni elegido proveedor.

En las tres:

| Información / acción | Frontera de permiso que debe probarse |
| --- | --- |
| Catálogo profesional demo, categorías, zonas e idiomas | Lectura pública; edición solo del titular/administración autorizada |
| Cuenta y dirección del trabajo | No exponer en catálogo; lectura solo de las partes autorizadas de la cita |
| Conversación y adjuntos | Solo participantes; remitente derivado de la identidad autenticada |
| Solicitud / confirmación / finalización | Validar actor, estado anterior y campos modificables en el backend |
| Reseña | Cliente autorizado de cita realizada, una por cita; no habilitar solo con un botón oculto |
| Verificación comercial, suscripción y pago demo | Señalados como simulación; no conceder privilegios administrativos por un campo editable del perfil |

Firebase requiere Security Rules tanto en Firestore como en Storage. `request.auth != null` solo restringe a “cualquier usuario registrado”: **no es aislamiento entre conversaciones**. Las consultas deben cumplir reglas: las reglas no filtran una consulta demasiado amplia. Lecturas auxiliares de reglas pueden facturarse; el Admin SDK de servidor las omite y debe protegerse por IAM [S19]. Storage puede comprobar pertenencia en Firestore y validar tamaño/tipo; esas consultas también cuentan [S16].

Supabase requiere grants mínimos y RLS por operación en tablas expuestas; las políticas se basan en `auth.uid()` y pertenencia, no en roles que el usuario pueda autoeditar. Revisar vistas y funciones que puedan saltarse RLS. El cliente usa una clave publishable; **jamás secret/service-role ni contraseña Postgres en APK, repositorio o chat** [S8]. Storage necesita políticas propias sobre `storage.objects`; un adjunto compartido no se resuelve con una política “solo el dueño”, pues también debe poder leerlo la otra parte [S17]. Separar imágenes públicas del perfil y adjuntos privados; no publicar permanentemente estos últimos para evitar diseñar permisos.

No almacenar contraseñas en tablas propias ni incrustar las credenciales de las cuentas demo en la APK. Tener dos identidades no significa crear dos aplicaciones: cada instalación inicia una cuenta distinta contra el mismo proyecto.

## 5. Persistencia entre dispositivos, chat y consistencia

- **Firebase:** mensajes/documentos persistidos en Firestore y archivos en Storage; listeners actualizan ambos clientes. Android tiene persistencia offline por defecto; los cambios locales se sincronizan al reconectar y modificaciones del mismo documento siguen “última escritura gana” [S20]. Un mensaje visible localmente puede estar pendiente: mostrar estado de envío/errores. Las transacciones reintentan ante concurrencia y **fallan offline** [S21]. No confirmar reservas sin respuesta del servidor ni resolver solapamientos con una consulta de disponibilidad seguida de una escritura independiente.
- **Supabase:** mensajes, citas y reseñas persisten en Postgres; Postgres Changes notifica modificaciones, requiere añadir las tablas a la publicación y respetar permisos [S22]. Después de reconectar o reabrir la app, consultar el historial persistente: no usar únicamente Broadcast como almacén del chat ni asumir recuperación de eventos perdidos. No se ha validado una cola offline equivalente a Firestore; para la demo tratar las mutaciones como online, con error y reintento explícitos. Una función/transacción SQL y restricciones pueden validar cambios atómicos; diseño y pruebas concretas siguen pendientes.
- **En ambos:** el proveedor no suministra las reglas de citas de Teloarreglo. Confirmar sin solapamientos exige un mecanismo atómico (por ejemplo bloqueo/restricción de intervalos en Postgres o esquema de ocupación con transacción y reglas en Firestore), no dos validaciones de pantalla. La reseña necesita unicidad por cita y comprobación de elegibilidad. No se fija aquí la máquina de estados ni se decide cómo tratar disputas o cambios de condiciones.
- Guardar mensajes como registros individuales paginables con identificador estable, autor y fecha de servidor; no un historial entero en un array creciente. Para archivos: primero confirmar subida, después persistir referencia/metadatos; gestionar subida fallida y objeto huérfano. Son propuestas de diseño para estimar el trabajo, no implementación existente.
- Realtime con la app abierta no equivale a notificaciones con la app cerrada. A dispone de FCM sin coste del servicio [S10]; B/C pueden integrar un emisor confiable/función y FCM, pero no deben llevar credenciales de envío en la APK. El alcance de push y recordatorios debe acordarse aparte, sin prometerlo porque haya WebSockets.

## 6. Cuatro idiomas sin cuatro aplicaciones

**Kotlin/Compose:** cadenas en recursos; castellano completo en `res/values/strings.xml`, alternativas `values-en`, `values-fr`, `values-de`; Compose consume `stringResource`. Android selecciona recursos por configuración y usa los predeterminados si no encuentra idioma [S23]. Selector con API de idioma por app/AndroidX; para Compose con `AppCompatDelegate.setApplicationLocales`, la actividad debe extender `AppCompatActivity`, y hay consideraciones de persistencia anteriores a Android 13 [S24].

**Flutter:** `flutter_localizations`, `intl`, catálogos ARB para `es`, `en`, `fr`, `de`, generación `gen-l10n`; configurar `supportedLocales` y alternativa castellana (por defecto la primera locale compatible o la primera de la lista). El selector modifica la locale de la app y debe conservar la elección; no requiere backend de traducción [S25].

En ambos hay que traducir de verdad botones, validaciones, errores presentables, estados, filtros, etiquetas demo y diálogos del recorrido, no solo la portada. Formatear fechas/horas/números; guardar estados como códigos estables, no como la palabra traducida. No mostrar directamente todos los mensajes de error del proveedor. Comprobar alemán largo, textos franceses, tamaño de fuente y reinicio tras selección. Descripciones, reseñas y mensajes conservan el idioma original; idiomas hablados del profesional son datos del catálogo, no la locale de su teléfono. No se aprueba una reducción silenciosa a un solo idioma.

## 7. APK, alojamiento y ensayo

Una APK instalada **no necesita alojamiento de una web** para ejecutar UI Android; sí Internet para el recorrido compartido remoto. El APK puede entregarse directamente a los dos teléfonos o por App Distribution, que figura sin coste [S10]. El panel web comercial no se considera implementado por tener consola del proveedor: la consola es una herramienta administrativa de desarrollo con privilegios.

- Nativa: generar APK firmada desde Android Studio; conservar keystore privado fuera del repositorio. Android exige firma para instalar/actualizar; debug se firma automáticamente, pero no es una publicación de tienda. Mantener mismo `applicationId`/certificado para actualizaciones compatibles [S26].
- Flutter: configurar firma release y permiso `INTERNET` en manifiesto principal; `flutter build apk` produce una APK universal más grande, `--split-per-abi` produce las específicas. Probar exactamente la arquitectura y el artefacto entregados, no solo `flutter run`. Un AAB no es la APK directamente instalable [S27].
- Instalar por USB/ADB durante preparación o mediante el flujo permitido por el dispositivo para APK externas; ensayar permisos de instalación y selección de multimedia. No depender de que el proyector, Wi-Fi o cuenta Google de la universidad permitan hacerlo en el último momento.
- **No condicionar el 28 a publicar en producción en Play:** para cuentas personales nuevas, Google exige al menos 12 testers durante 14 días continuos antes de solicitar acceso a producción; la revisión también consume tiempo. Pruebas internas son distintas de producción [S28].
- **Contexto 2026:** la verificación de desarrolladores Android empezó el 30 de septiembre en Brasil, Indonesia, Singapur y Tailandia para dispositivos certificados/tiendas participantes; la expansión global está anunciada para 2027. No inferir que una demo en España debe esperar una publicación Play, ni prometer instalación universal sin comprobar el teléfono. La página oficial también ofrece distribución limitada para estudiantes de hasta 20 dispositivos sin tasa ni identificación gubernamental [S29]. Revisar alcance vigente al distribuir fuera del ensayo privado.

### Evidencia mínima antes de llamar “lista” a la demo (propuesta)

1. Misma APK y proyecto remoto en dos teléfonos, cuentas A cliente/B profesional; búsqueda y perfil ficticio sin datos personales reales.
2. A envía texto, foto y vídeo; B los recibe y reproduce. Cerrar y reabrir en ambos conserva historial; probar una red distinta demuestra que no es memoria local compartida.
3. A solicita, B confirma, B marca realizada y A completa el tramo acordado y reseña; comprobar datos desde una sesión nueva. Las decisiones de estados exactos pertenecen al mapa, no a este informe.
4. Doble pulsación/reintento no duplica reseña; intento concurrente de reserva no confirma solapamiento. Una tercera identidad de prueba no lee conversación, dirección ni archivos ajenos.
5. Cortar conexión muestra pendiente/error, no “confirmado” falso; al volver no se pierde ni duplica información.
6. Recorrer pantalla a pantalla ES/EN/FR/DE, selector persistente y alternativa castellana con un idioma no soportado.
7. Revisar cuota, billing, reglas y proyecto activo antes del ensayo; disponer de vídeo de respaldo autorizado **identificado como grabación**, no como persistencia online en directo.

Estos ensayos **no se han ejecutado**: el encargo es investigación sin app.

## 8. Decisiones humanas que este informe no sustituye

1. Cliente A/B/C y preferencia de aprendizaje; aceptación o rechazo de billing variable frente a cortes de Free.
2. Región de datos, método de acceso verificable y si el público podrá registrarse o solo entrarán dos cuentas preparadas.
3. Límites de foto/vídeo, formatos y conservación; aprobar o modificar la propuesta técnica sin confundirla con la especificación acordada.
4. Mecanismo de exclusión de citas y estados/actores del tramo de finalización; cobertura de notificaciones y panel durante la demo.
5. Ruta de entrega APK y teléfonos reales disponibles.

Para producto real habrá que revisar privacidad, eliminación de cuenta/archivos, moderación, recuperación de datos, correo transaccional, observabilidad, concurrencia y costes a volumen; pagos y suscripción requieren selección y cumplimiento separados. Supabase facilita conservar un modelo Postgres portable pero Auth/Storage/Realtime siguen siendo integraciones a migrar; Firestore exige adaptar el modelo si se cambia a SQL. Multiplataforma evita reescribir parte de UI en otros destinos, no elimina pruebas/configuración por plataforma.

## Fuentes primarias

Consultadas el 19/11/2026. Las referencias sustentan capacidades/cuotas; propuestas, cuentas de volumen y evaluaciones de riesgo se identifican como análisis propio.

- [S1 — Android: configurar Compose](https://developer.android.com/develop/ui/compose/setup).
- [S2 — Firebase: setup Android, SDK y retirada KTX](https://firebase.google.com/docs/android/setup).
- [S3 — Supabase: quickstart Flutter y enlaces de autenticación](https://supabase.com/docs/guides/getting-started/quickstarts/flutter).
- [S4 — Supabase: referencia Kotlin, mantenimiento comunitario](https://supabase.com/docs/reference/kotlin/introduction).
- [S5 — Firebase: setup y plugins Flutter](https://firebase.google.com/docs/flutter/setup).
- [S6 — Flutter: herramientas Android](https://docs.flutter.dev/platform-integration/android/setup).
- [S7 — Android Studio: instalación y requisitos locales](https://developer.android.com/studio/install).
- [S8 — Supabase: grants, RLS, vistas y claves privilegiadas](https://supabase.com/docs/guides/database/postgres/row-level-security).
- [S9 — Firestore: cuotas y límites Standard](https://firebase.google.com/docs/firestore/quotas).
- [S10 — Firebase: precios por servicio y plan](https://firebase.google.com/pricing).
- [S11 — Firebase Storage: requisito Blaze, buckets nuevos y regiones gratuitas](https://firebase.google.com/docs/storage/android/start).
- [S12 — Firebase Auth: correo/contraseña](https://firebase.google.com/docs/auth/android/password-auth).
- [S13 — Firebase: evitar facturas imprevistas y alcance de spend caps](https://firebase.google.com/docs/projects/billing/avoid-surprise-bills).
- [S14 — Supabase: precios, Free, Pro, Realtime, almacenamiento y copias](https://supabase.com/pricing).
- [S15 — Supabase: control de costes y excepciones del Spend Cap](https://supabase.com/docs/guides/platform/cost-control).
- [S16 — Firebase: condiciones y validación en reglas Storage](https://firebase.google.com/docs/storage/security/rules-conditions).
- [S17 — Supabase: control de acceso a Storage](https://supabase.com/docs/guides/storage/security/access-control).
- [S18 — Supabase: límites del SMTP integrado y SMTP propio](https://supabase.com/docs/guides/auth/auth-smtp).
- [S19 — Firestore: condiciones, identidad y consultas autorizadas](https://firebase.google.com/docs/firestore/security/rules-conditions).
- [S20 — Firestore: persistencia offline y sincronización](https://firebase.google.com/docs/firestore/manage-data/enable-offline).
- [S21 — Firestore: transacciones y escrituras atómicas](https://firebase.google.com/docs/firestore/manage-data/transactions).
- [S22 — Supabase: Postgres Changes y publicación Realtime](https://supabase.com/docs/guides/realtime/postgres-changes).
- [S23 — Android: localización y recursos predeterminados](https://developer.android.com/guide/topics/resources/localization).
- [S24 — Android: selector de idioma por aplicación](https://developer.android.com/guide/topics/resources/app-languages).
- [S25 — Flutter: internacionalización, ARB y resolución de locale](https://docs.flutter.dev/ui/internationalization).
- [S26 — Android: firma de APK y claves](https://developer.android.com/studio/publish/app-signing).
- [S27 — Flutter: generar, firmar e instalar APK Android](https://docs.flutter.dev/deployment/android).
- [S28 — Google Play: pruebas obligatorias de cuentas personales nuevas](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [S29 — Android: calendario y rutas de verificación de desarrolladores](https://developer.android.com/developer-verification).
