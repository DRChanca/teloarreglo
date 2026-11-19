# Teloarreglo — Especificación funcional acordada

Estado: definición de producto previa al desarrollo.
Fecha: 19 de noviembre de 2026.
Repositorio: https://github.com/DRChanca/teloarreglo

Este documento consolida las decisiones de la conversación con el promotor. No implica que las funcionalidades estén implementadas ni que se hayan contratado proveedores. Las cuestiones pendientes se recogen al final.

## Actualización: objetivo inmediato de demostración universitaria

Esta sección tiene prioridad sobre las referencias posteriores al alcance del primer lanzamiento. El resto del documento conserva la visión del producto futuro; no todo es requisito de la demostración.

- Contexto: programa universitario de emprendimiento en aplicaciones móviles.
- Equipo: dos personas.
- Sin experiencia previa en desarrollo Android; la implementación se realizará con ayuda de agentes.
- Dedicación disponible: unas tres horas al día por integrante, aproximadamente seis horas diarias combinadas. Esto no equivale a seis horas de trabajo independiente sin costes de coordinación.
- Reparto aceptado: dos líneas de trabajo con interfaces acordadas e integración frecuente, no una integración única al final.
- Objetivo interno: demo preparada para el 28 de noviembre de 2026.
- Presupuesto orientativo de infraestructura: unos 10 €/mes; no es una estimación comprobada de costes.
- Destino del mapa Wayfinder: resolver las decisiones necesarias para una demo Android presentable y documentar cómo evolucionarla a producto viable.
- Recorrido prioritario real y persistente entre dos cuentas: buscar profesional, consultar perfil, conversar, solicitar cita, confirmarla como profesional y dejar una reseña tras finalizarla.
- Se utilizarán profesionales ficticios, imágenes autorizadas y un entorno identificado como demostración.
- Pagos, suscripción y comprobación del negocio pueden simularse, sin cobros reales ni presentación de perfiles ficticios como negocios auténticos.
- La visión comercial mantiene la suscripción opcional de 5 €/mes con herramientas de gestión y ventajas de posicionamiento, y los pagos opcionales sin comisión propia.
- No se exige operar un negocio, revisar documentación real o procesar dinero real para esta demo.
- La planificación de implementación deberá permitir repartir trabajo entre los dos integrantes, con dependencias e integración explícitas.
- El cartel aportado anuncia vídeo demostrativo el 1 de diciembre de 2026 y presentación final el 4 de diciembre de 2026 a las 17:00. El día de la semana impreso para el 1 de diciembre no coincide con el calendario; las condiciones y fechas oficiales requieren confirmación. Se conserva el 28 de noviembre como objetivo interno.

## 1. Objetivo y ámbito

### Revisión acordada durante el guion de la demo

Las reglas de este apartado sustituyen las propuestas anteriores incompatibles sobre búsqueda, confirmación y momento del pago, tanto para el flujo demostrado como para la visión que se explicará del producto. El dinero sigue siendo simulado en la demo; la viabilidad del flujo real requiere nueva validación del proveedor.

- Catálogo de demostración: 18 profesionales ficticios, tres de cada uno de los seis oficios iniciales, con servicios, concejos y precios variados.
- La historia principal muestra una reparación de fontanería en Oviedo: búsqueda, perfil, conversación con foto y vídeo reales, propuesta de reparación con fecha y precio, confirmación mediante pago simulado, finalización y reseña.
- Demostración con la aplicación Android funcionando en dos emuladores: uno para el cliente y otro para el profesional, con persistencia compartida. No basta una maqueta navegable.
- Cuatro idiomas en la interfaz entregada; vídeo narrado en castellano y demostración breve del selector.
- Escena de suscripción de 5 €/mes con sus ventajas; sin cobros reales.
- Búsqueda por proximidad desde el GPS, con mapa y lista y alternativa de ubicación manual para denegación del permiso o trabajo en otra dirección.
- Primero se filtra por cobertura del lugar del trabajo; después se ordena por distancia a la ubicación profesional registrada, no por seguimiento en directo.
- Se solicita organización espacial por hexágonos, ampliando desde la celda de búsqueda a sus vecinas y calculando las distancias reales para ordenar. La tecnología y los detalles quedan para la decisión técnica.
- La propuesta del profesional especifica alcance, precio y hora. Para pago integrado, el cliente paga el total para confirmar la cita.
- Se conserva el pago directo: la cita puede confirmarse por acuerdo expreso de ambas partes sin pagar dentro de la aplicación.
- Una visita puede ser gratuita o tener un coste acordado. Una cita gratuita no requiere pasar por el pago. No se crean tipos rígidos de cita.
- Cambiar el importe requiere aceptación expresa: si aumenta se paga la diferencia antes del trabajo adicional; si disminuye se devuelve la diferencia, de forma simulada.
- El pago integrado queda pendiente de entrega al técnico hasta la confirmación de realización. En producción se necesitaría un proveedor adecuado, no custodia directa de fondos asumida por Teloarreglo.
- El profesional marca el servicio como realizado; el cliente confirma para liberar el pago simulado.
- Si el cliente no responde, se libera a las 48 horas desde esa marca, con recordatorios. Sustituye el plazo anterior de siete días para ese flujo.
- Si el cliente comunica una incidencia antes de la liberación, el pago queda pendiente de revisión; no se entrega ni se devuelve automáticamente.
- Cancelar antes de comenzar el servicio da lugar a devolución total simulada, sin penalizaciones. Tras comenzar se comunica una incidencia, sin devolución ni entrega automática.
- Las propuestas pendientes no reservan definitivamente el horario. Antes del pago se comprueba disponibilidad; no deben confirmarse citas solapadas. El mecanismo de concurrencia y su futura coordinación con el proveedor queda pendiente.
- Objetivo de integración del recorrido: 26 de noviembre; 27 y 28 reservados para pruebas, correcciones y grabación.
- El alcance del panel de administración no está aprobado: requiere una decisión específica y no se presume aceptada la propuesta de panel mínimo.
- Vídeo de un máximo de diez minutos según el usuario; objetivo acordado de siete a ocho minutos narrados.
- En el vídeo, el cliente confirma manualmente que el servicio se realizó y se muestra la liberación del pago simulado.
- La decisión posterior sobre citas, reseñas y privacidad exige que la liberación automática tras 48 horas sin respuesta ni incidencia funcione realmente en la demo, aunque ambas aplicaciones estén cerradas. Sustituye el alcance anterior de solo explicarla. En el vídeo se conserva la confirmación manual, sin esperar esas horas ni añadir un botón para adelantar el reloj.

#### Reglas aprobadas de citas, reseñas y privacidad

Fuente: [Precisar citas, reseñas y privacidad para una demo coherente](https://github.com/DRChanca/teloarreglo/issues/7).
Estas reglas prevalecen sobre los apartados anteriores incompatibles y sobre las cuestiones que más abajo se conservaban como pendientes.

**Solicitudes, propuestas y cambios**

- Puede haber varias solicitudes pendientes para un mismo intervalo. Ninguna lo reserva definitivamente.
- Para confirmar se comprueba la disponibilidad de todo el intervalo. No se confirman solapamientos ni se simula un cobro si existe conflicto.
- Las otras solicitudes no se cancelan automáticamente: se informa del conflicto y se propone otra hora.
- Cambiar precio, fecha, hora, duración, alcance o dirección requiere aceptación expresa de la otra parte. Se muestran condiciones anteriores y nuevas; mientras se decide siguen vigentes las anteriores.
- Una cita confirmada conserva su reserva mientras un cambio esté pendiente. Al aceptar otro horario se comprueba de nuevo su disponibilidad y solo entonces se sustituye la reserva anterior. Si hay conflicto, se mantiene la cita original sin cobrar diferencias.
- Rechazar una propuesta conserva chat e historial y permite enviar otra. Rechazar un cambio mantiene la cita original; rechazar la primera propuesta deja la cita sin confirmar ni reservar.
- El cambio de dirección debe respetar la cobertura profesional. El profesional puede solicitar una corrección, pero no sustituir por su cuenta el domicilio aportado por el cliente.
- Los aumentos de precio integrado requieren aceptación y pago simulado de la diferencia antes del trabajo adicional; las reducciones aceptadas producen devolución simulada. En pago directo se registra el acuerdo, sin certificar cobros externos.

**Inicio, finalización e incidencias**

- El profesional dispone de una acción «Comenzar trabajo», visible para el cliente, que distingue el inicio de la confirmación de cita y de la marca de realizado. No autoriza costes adicionales.
- Antes de comenzar, cualquiera de las partes puede cancelar sin penalización y con devolución total del pago integrado simulado.
- Tras comenzar, la solicitud de cancelación se trata como incidencia; el dinero queda pendiente de resolución, sin devolución ni entrega automática.
- El profesional marca «Realizado»; el cliente puede confirmar o comunicar una incidencia. La confirmación libera el pago integrado simulado.
- Las 48 horas se cuentan desde la marca de realizado con hora del servidor. El cierre y la liberación simulada deben ejecutarse aunque las dos aplicaciones estén cerradas, sin depender de la hora del teléfono.
- Se avisa al cliente al marcar realizado y se envía un recordatorio a las 24 horas si sigue pendiente y sin incidencia. Se explica el vencimiento y se ofrecen confirmar o comunicar incidencia.
- Una incidencia previa a la liberación bloquea la entrega automática. Confirmación, incidencia y vencimiento deben coordinarse para no entregar dos veces el mismo pago ni entregar uno bloqueado.
- Tras 48 horas sin respuesta ni incidencia, la cita queda «Finalizada sin confirmación del cliente» y se libera el pago integrado simulado. El mismo cierre se aplica a citas gratuitas o con pago directo, sin inventar cobros ni entregas.
- El cliente puede comunicar un problema tras el cierre; esto no revierte automáticamente una entrega ya registrada.
- La resolución administrativa de incidencias no se presupone implementada: alcance y permisos del panel siguen en su decisión separada.

**Reseñas**

- Se permite una reseña por cita realizada, también para citas gratuitas o con pago directo y después del cierre automático sin respuesta.
- Nunca se genera una reseña automáticamente.
- Una queja de calidad no impide reseñar si ambas partes reconocen que se realizó el servicio, aunque el pago siga pendiente de revisión.
- Si se discute que el servicio haya ocurrido, la reseña espera a la revisión.

**Privacidad, multimedia y conservación**

- Solo las dos personas implicadas pueden leer la conversación, adjuntos y cita privados. El domicilio exacto no aparece en la búsqueda ni al abrir el chat: se comparte al solicitar la cita.
- Cada persona actúa en su nombre. El profesional propone condiciones, comienza y marca realizado; el cliente acepta las condiciones y confirma la realización. Los permisos deben comprobarse realmente, no solo ocultar botones.
- Fotos y vídeos se pueden enviar y abrir desde el primer contacto, antes de cualquier respuesta o aceptación del profesional.
- Compresión integrada automática con progreso y posibilidad de cancelar, sin modificar el original del teléfono. Si falla o el resultado supera los límites, se explica y no se envía silenciosamente el original.
- Para la demo: vídeos de hasta un minuto; fotos de hasta 1600 píxeles en su lado mayor y vídeos de hasta 720p, sin ampliar archivos de menor resolución. Máximos tras comprimir: 2 MB por foto y 20 MB por vídeo. Compatibilidad concreta de formatos y estrategia técnica pendientes de la arquitectura.
- Se conservan entre sesiones los perfiles y operaciones preparados en la aplicación. No se añade una operación de reinicio ni borrado automático para la presentación.
- Se utilizan cuentas, direcciones y contenidos ficticios o autorizados sin exponer información privada de terceros. Perfiles ficticios y pagos simulados siguen identificados.
- Durante la preparación y presentación se conservan mensajes, adjuntos y propuestas sin edición ni borrado individual. No supone conservación indefinida en el producto final: la política de borrado comercial sigue pendiente.
- Este acuerdo no aprueba el diseño visual ni elige arquitectura o proveedor.

#### Criterios observables del recorrido acordado

- El catálogo contiene 18 perfiles ficticios de seis oficios y permite comparar sus servicios.
- La ubicación se obtiene mediante permiso GPS o entrada manual; los resultados se muestran en lista y mapa y respetan la cobertura de servicio.
- Una acción de una cuenta se refleja en la otra sin editar datos manualmente fuera de la aplicación.
- Las conversaciones permiten enviar y abrir una foto y un vídeo reales.
- El profesional propone alcance, fecha y precio; el cliente acepta y confirma mediante pago simulado claramente identificado.
- No se confirma una cita cuyo horario ya esté ocupado.
- El profesional marca realizado; el cliente confirma; la aplicación registra la liberación simulada y permite una reseña.
- Las operaciones del recorrido persisten al volver a abrir la aplicación.
- La interfaz entregada está disponible en castellano, inglés, francés y alemán; el vídeo muestra brevemente el selector.
- La escena de suscripción muestra precio y ventajas sin cobrar dinero; las imágenes y los datos ficticios están autorizados o creados para la demo.
- Visitas gratuitas o con coste y confirmación para pago directo siguen dentro del alcance, aunque el vídeo se centra en el pago integrado simulado.
- La automatización de cierre y liberación simulada a las 48 horas funciona con aplicaciones cerradas, respeta incidencias y evita entregas duplicadas; no se confunde la explicación del vídeo con evidencia de su funcionamiento.
- Fotos y vídeos se comprimen en la aplicación con los límites acordados, progreso, cancelación y aviso si no pueden enviarse.

Teloarreglo conecta a particulares que necesitan arreglos con profesionales independientes y pequeñas empresas. Permite encontrar profesionales, contactar, concertar citas, acordar precios, pagar opcionalmente y dejar reseñas.

- Aplicación para Android.
- Mercado: España; lanzamiento inicial en Asturias.
- Usuarios registrados y contratación: mayores de 18 años.
- Panel web privado para administración.
- Proyecto con intención de lanzamiento como negocio real.
- No es un servicio de emergencias ni garantiza asistencia inmediata.

## 2. Usuarios y registro

- Una misma cuenta puede contratar servicios y ofrecerlos mediante un perfil profesional opcional.
- Se pueden consultar perfiles y buscar sin registrarse.
- Para contactar, solicitar citas o dejar reseñas se requiere una cuenta.
- Acceso previsto con Google o correo electrónico; el mecanismo concreto de autenticación queda pendiente.
- Se verificará el correo electrónico.
- El teléfono del profesional es obligatorio y público, pero no se exige verificarlo.
- Verificar el correo no equivale a verificar el negocio.

## 3. Idiomas

La interfaz estará disponible desde el lanzamiento en castellano, inglés, francés y alemán.

- Idioma inicial según el dispositivo cuando esté disponible; castellano como alternativa.
- Selector manual en ajustes.
- Mensajes, reseñas y descripciones conservan su idioma original, sin traducción automática inicialmente.
- Los profesionales indican qué idiomas hablan.
- Los clientes pueden filtrar por esos idiomas.

## 4. Catálogo y búsqueda

Categorías iniciales:

1. Fontanería.
2. Electricidad.
3. Carpintería.
4. Pintura.
5. Albañilería.
6. Reparaciones generales.

Un profesional puede seleccionar varias categorías y describir los servicios que ofrece.

La búsqueda tiene en cuenta dónde se realizará el trabajo y las zonas que cubre el profesional, no únicamente su domicilio o local.

- Búsqueda por oficio y zona o concejo.
- Filtro por idiomas del profesional.
- Opciones de ordenación por valoración y número de reseñas.
- Relevancia, disponibilidad y reputación como criterios generales; ponderación pendiente.
- Espacios de posicionamiento de pago identificados como «Destacado».
- Un destacado solo aparece en zonas donde el profesional presta servicio.
- No se garantiza una primera posición permanente ni un número de clientes.

En Asturias se utilizará el término «concejo» para el municipio.

## 5. Perfil profesional y ubicación

El perfil incluye:

- Nombre y descripción del negocio o actividad.
- Servicios y categorías.
- Zona de trabajo.
- Teléfono visible para llamar.
- Fotos de trabajos anteriores.
- Reseñas y respuestas.
- Idiomas hablados.
- Tabla opcional de servicios y precios orientativos.
- Dirección comercial y ubicación en el mapa cuando corresponda.

Se recogerá una dirección para obtener su concejo o municipio. Los profesionales con local pueden mostrar su dirección comercial exacta. Quienes trabajan desde casa pueden mostrar solo el concejo y la zona de servicio, sin obligación de publicar su domicilio; podrán optar por publicarlo.

El profesional seleccionará los municipios o concejos donde presta servicio.

La dirección exacta del cliente nunca será pública: se comparte con el profesional al solicitar una cita. Para buscar bastará con una zona o localidad.

## 6. Revisión y verificación de profesionales

- Revisión básica antes de que el perfil aparezca públicamente.
- El profesional aporta datos y documentación para acreditar su relación con el negocio.
- Administración realiza la revisión desde su panel.
- Google Maps sirve como apoyo para contrastar negocio, dirección y ubicación, no como prueba suficiente de titularidad.
- Se admitirán profesionales sin local abierto al público o sin ficha en Google Maps.
- La insignia «Verificado» indicará el alcance de las comprobaciones realizadas.
- La verificación no garantiza la calidad del trabajo ni acredita automáticamente titulaciones.
- Pagar la suscripción no compra la verificación.
- Activar cobros integrados requerirá, además, completar las comprobaciones del proveedor de pagos.
- Un profesional puede recibir contactos y citas sin activar los cobros integrados.

## 7. Contacto y chat

- Chat dentro de la aplicación.
- Cliente y profesional pueden enviar mensajes, fotos y vídeos.
- El cliente puede enviar fotos y vídeos desde el primer contacto, antes de que el profesional responda, acepte una solicitud o confirme una cita. Sirven para aportar contexto del problema antes de acordar alcance y precio; no requieren aceptación previa del profesional.
- Teléfono del profesional visible para quienes prefieran llamar.
- El chat permite explicar el problema, solicitar información y compartir resultados.
- El profesional puede enviar propuestas de precio que el cliente acepte expresamente.
- Límites de archivos, formatos y conservación de contenidos pendientes.

## 8. Precios y propuestas

No es obligatorio fijar el precio del trabajo para solicitar una cita.

Cada profesional puede publicar voluntariamente una tabla de servicios básicos con sus propios precios; la plataforma no impondrá tarifas.

Cada entrada debe explicar:

- Servicio y alcance.
- Unidad: hora, trabajo, unidad, metro cuadrado u otra apropiada.
- Qué incluye.
- Materiales y desplazamientos incluidos o aparte.
- Si el precio es orientativo o cerrado.
- Precio con IVA incluido cuando corresponda para consumidores.

Si una visita tiene coste aunque no se realice una reparación, debe comunicarse antes de confirmar la cita.

Las propuestas en el chat permiten dejar constancia del trabajo y del importe aceptados. Si aparece un coste adicional, el profesional debe comunicar el nuevo importe y obtener aceptación expresa antes de cobrarlo en la aplicación.

Investigación de apoyo: [Servicios por oficio](investigacion-servicios.md). Sus propuestas de catálogo no constituyen una clasificación demostrada de demanda en Asturias.

## 9. Agenda y citas

### Concepto

Una cita es una cita: no se crean tipos separados para «valoración» y «realización del trabajo». Su alcance se acuerda entre las partes.

### Solicitud y confirmación

- El cliente consulta disponibilidad y solicita una hora de inicio.
- Aporta descripción del problema y dirección del trabajo.
- La solicitud aparece como «Pendiente de confirmación».
- Solicitar una hora no la reserva definitivamente.
- El profesional configura una duración habitual y puede ajustarla al revisar la solicitud.
- El profesional puede confirmar directamente la solicitud original si no modifica sus condiciones ni añade costes.
- Añadir un coste de visita o desplazamiento es opcional.
- Si añade costes, el cliente debe aceptarlos antes de confirmar la cita.
- No se pueden confirmar citas solapadas.

Los cambios de duración y demás condiciones requieren aceptación expresa según las reglas aprobadas del apartado 1.

### Organización de la agenda

- Una sola agenda por perfil profesional inicialmente.
- Sin gestión de empleados ni citas simultáneas de varios trabajadores.
- El profesional configura horarios disponibles.
- Puede bloquear tiempo para desplazamientos, descansos o trabajos externos.
- Las citas confirmadas ocupan sus intervalos.
- Cliente y profesional pueden cancelar o proponer otra fecha.
- Se notifica a la otra parte.
- Sin penalizaciones económicas por cancelación inicialmente.

### Citas acordadas por teléfono

Desde el lanzamiento, el profesional podrá registrar una cita acordada por llamada e invitar al cliente a confirmarla en su cuenta.

### Finalización y ausencias

- El profesional puede marcar una cita como realizada.
- El cliente puede confirmarlo o comunicar un problema.
- Si no responde, recibe recordatorios.
- Tras 48 horas sin respuesta ni incidencia desde la marca de realizado se cierra como «Finalizada sin confirmación del cliente», según la revisión del apartado 1; sustituye el plazo anterior de siete días.
- Se permite reclamar posteriormente.
- El silencio no autoriza cobros ni genera reseñas automáticas.
- Si se discute que la cita haya ocurrido, queda en revisión por administración.
- Ambas partes pueden comunicar una ausencia de la otra.
- Las ausencias se registran de forma privada, admiten reclamación y no generan inicialmente multas ni reseñas de trabajo realizado.

## 10. Pagos por trabajos

Los pagos dentro de la aplicación estarán incluidos en la primera versión y serán opcionales.

- Se paga al terminar el trabajo, sin anticipos inicialmente.
- El profesional indica el importe final y el cliente lo revisa y autoriza.
- No se cobran incrementos automáticamente.
- Se permite pagar directamente al profesional, por ejemplo en efectivo o con su datáfono.
- La aplicación distinguirá pagos externos de los que realmente procesa.
- No habrá comisión propia de Teloarreglo por trabajo inicialmente.
- Las comisiones del proveedor de pagos se descontarán al profesional, informándole previamente.
- No se añadirá inicialmente un recargo al cliente.
- Proveedor, tarifas, impuestos, liquidaciones y operativa de cobros pendientes de selección y revisión.

### Devoluciones y reclamaciones

- El profesional puede devolver total o parcialmente un pago integrado.
- La devolución queda registrada y se notifica al cliente.
- No se promete reembolsar automáticamente cualquier trabajo discutido.
- Una incidencia puede incluir pruebas y contacto con administración.
- Se distingue la mediación de la plataforma de las obligaciones legales del profesional.
- No se ofrece una garantía técnica universal ni cobertura económica del trabajo por parte de Teloarreglo.
- No se traslada automáticamente la protección de envíos de Wallapop a reparaciones presenciales.

## 11. Reseñas y confianza

- Una reseña por cita realizada.
- Puntuación de 1 a 5 estrellas y comentario.
- Fotos opcionales del trabajo.
- Se advertirá que no deben publicarse caras, direcciones ni otros datos privados.
- Se muestra la media y el número de valoraciones.
- El profesional puede responder públicamente.
- No puede modificar ni borrar la reseña del cliente, pero sí denunciarla.
- Pagar fuera de la aplicación no impide valorar una cita realizada y registrada.
- Una disputa sobre la calidad no impide por sí sola publicar una reseña si consta que el servicio se realizó.
- Si se discute si la cita ocurrió, se revisa antes de habilitar la reseña.
- Las citas cerradas sin respuesta no publican reseñas automáticas; el cliente puede escribir posteriormente una reseña, salvo disputa sobre si el servicio ocurrió pendiente de revisión.

## 12. Suscripción y posicionamiento

Esta es la decisión final y sustituye las propuestas intermedias de pago único, solo visibilidad o solo herramientas de gestión.

### Perfil gratuito

Conserva:

- Presencia en las búsquedas.
- Contactos y teléfono público.
- Chat con fotos y vídeos.
- Agenda y citas.
- Cobros opcionales dentro de la aplicación.
- Reseñas.
- Posibilidad de verificación independiente del pago.

### Suscripción opcional: 5 €/mes

Precio inicial provisional, pendiente de revisar costes y tratamiento fiscal.

Incluye:

1. Estadísticas de consultas y citas.
2. Exportación de agenda.
3. Plantillas de presupuestos.
4. Ventajas de posicionamiento mediante espacios «Destacados».

Reglas:

- Los destacados se identifican claramente.
- Solo se muestran en zonas donde se presta servicio.
- No se garantiza una posición fija, exclusiva o permanente ni un volumen de contactos.
- El reparto entre suscriptores y el detalle de las ventajas quedan pendientes.
- La suscripción es la única fuente de ingresos propia acordada inicialmente.
- No hay comisión propia por los trabajos.
- La renovación puede cancelarse en cualquier momento.
- Las ventajas se conservan hasta terminar el periodo pagado.
- Cancelar no elimina el perfil gratuito ni las citas.

La facturación de ventajas digitales en Android y el pago de servicios físicos se revisarán por separado conforme a las reglas de Google Play aplicables. No se presupone que ambos utilicen el mismo sistema.

Investigación de apoyo: [Modelo de Wallapop](modelo-wallapop.md).

## 13. Notificaciones y seguridad de uso

- Notificaciones de mensajes, solicitudes y cambios de citas.
- Recordatorios de confirmación de finalización.
- Gestión de notificaciones desde ajustes.
- Denuncia de perfiles, mensajes y reseñas.
- Bloqueo de nuevos contactos de otra persona.
- Bloquear no borra el historial ni cancela automáticamente citas o pagos pendientes.

## 14. Administración

Panel web privado para:

- Revisar altas y verificar negocios.
- Revisar documentación y el alcance de la verificación.
- Gestionar categorías.
- Atender denuncias e incidencias.
- Revisar disputas sobre realización de citas.
- Suspender cuentas cuando corresponda.

Los permisos, procedimientos y plazos de atención quedan por definir.

## 15. Alcance inicial y ampliaciones

El alcance funcional inicial acordado comprende las funcionalidades descritas, incluidos:

- Chat con fotos y vídeos.
- Citas telefónicas registrables.
- Pagos opcionales integrados sin comisión propia.
- Suscripción con herramientas de gestión y posicionamiento.
- Los cuatro idiomas.
- Panel de administración.

La secuencia de implementación y el lanzamiento de una prueba limitada se definirán antes de desarrollar. No se considera que una funcionalidad esté aplazada solo porque se propusiera así en un punto anterior de la conversación: los pagos se reincorporaron expresamente al alcance inicial.

Fuera del alcance inicial:

- Anticipos.
- Comisión propia por trabajo.
- Gestión de empleados y múltiples agendas por negocio.
- Traducción automática de contenido de usuarios.
- Penalizaciones económicas por cancelación o ausencia.
- Servicio de emergencias garantizado.
- Protección universal o garantía técnica financiada por la plataforma.

## 16. Decisiones pendientes antes del desarrollo o lanzamiento

### Producto y operativa

- Concretar en la arquitectura los estados y contratos que implementen las reglas aprobadas del apartado 1, incluida concurrencia en agenda y automatización de 48 horas.
- Resolución administrativa de disputas y permisos del panel, pendiente de su decisión específica.
- Requisitos documentales concretos de verificación y revisión periódica.
- Reglas del posicionamiento destacado y métricas de la suscripción.
- Compatibilidad técnica de formatos y compresión; moderación y política de conservación y borrado para el producto comercial. Los límites de la demo están aprobados en el apartado 1.
- Alcance y formato de exportaciones y plantillas.
- Políticas y plazos de reclamación, devolución y atención.

### Tecnología y proveedores

- Arquitectura y tecnologías de Android, servidor y panel.
- Proveedor de autenticación, mapas y obtención del concejo a partir de direcciones.
- Proveedor de pagos apto para plataforma de profesionales.
- Flujo de liquidaciones, devoluciones, contracargos y costes asociados.
- Facturación y gestión de suscripciones conforme a las reglas de distribución de Android.
- Almacenamiento de archivos, notificaciones y costes operativos.

### Legal y lanzamiento

- Disponibilidad del nombre Teloarreglo, marca y dominios.
- Privacidad, protección de datos, conservación y eliminación de cuentas.
- Tratamiento seguro de direcciones, documentación y conversaciones.
- Condiciones de uso, contratación, suscripción y responsabilidades.
- Fiscalidad y facturación del profesional y de la plataforma.
- Plan de prueba en Asturias, incorporación de profesionales y criterios de éxito.

Estas cuestiones no se consideran resueltas por este documento y requerirán decisiones explícitas o revisión especializada cuando corresponda.
