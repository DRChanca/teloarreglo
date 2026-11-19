# Teloarreglo — Especificación funcional acordada

Estado: definición de producto previa al desarrollo.
Fecha: 19 de noviembre de 2026.
Repositorio: https://github.com/DRChanca/teloarreglo

Este documento consolida las decisiones de la conversación con el promotor. No implica que las funcionalidades estén implementadas ni que se hayan contratado proveedores. Las cuestiones pendientes se recogen al final.

## 1. Objetivo y ámbito

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

El flujo exacto de aceptación de cambios de duración u otras condiciones queda por concretar.

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
- Tras siete días sin respuesta se cierra como «Finalizada sin confirmación del cliente».
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
- Las citas cerradas sin respuesta no publican reseñas automáticas; el criterio para habilitar una reseña posterior en estos casos queda por concretar.

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

- Flujo detallado y estados de citas, propuestas, cambios y disputas.
- Reglas de agenda ante varias solicitudes pendientes y cambios de duración.
- Habilitación de reseñas tras cierres sin confirmación.
- Requisitos documentales concretos de verificación y revisión periódica.
- Reglas del posicionamiento destacado y métricas de la suscripción.
- Límites de fotos y vídeos, moderación y conservación.
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
