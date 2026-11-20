# Portadas y mejora del catálogo

## Alcance acordado

Aplicación Android, visitantes que comparan profesionales y propietarios que
quieren cambiar la portada de **su perfil existente**. David eligió «Portadas y
diseño ahora»: el alta profesional completa y el panel administrativo no se
implementan en este ajuste. El editor de portada se podrá reutilizar desde el
futuro registro.

La tarea no consiste en decorar: el visitante debe encontrar un profesional,
compararlo y volver sin rehacer su búsqueda; el propietario debe saber qué
imagen propone, si llegó al servidor y si ya puede verla el público.

## Evaluación con mejoraInterfaz

Evidencia: código Compose y contratos de servidor, interfaz ejecutada en
emulador desechable y pruebas indicadas abajo. Las mejoras de tiempo y satisfacción
son hipótesis: no se han medido con usuarios.

| Prioridad | Ubicación y evidencia inicial | Cambio | Validación |
|---|---|---|---|
| P1 | Lista recreada al volver del detalle; no conservaba su estado de desplazamiento | Estado de lista y filtros conservados; cambiar búsqueda/oficio lleva al comienzo | Regresión Compose con 30 perfiles y vuelta desde el número 20 |
| P2 | Tarjetas dominadas por una ilustración grande, sin servicios ni precios para comparar | Tarjetas compactas con miniatura, servicio, cobertura y referencia de precio; valoración sin inventar datos | Recorrido lista/perfil, capturas; mejora de velocidad no medida |
| P2 | No había búsqueda ni recuento y el vacío no ofrecía una salida | Búsqueda local por nombre/servicio sin distinguir acentos, recuento y restablecer filtros | Regresión de búsqueda sin coincidencias y recuperación |
| P1 | No existía cambio de portada por su propietario | Selector del sistema, previsualización, límites, confirmación del servidor y estado de revisión | Contrato real Auth/Storage y pruebas Compose de espera, error y reintento |

Cobertura de criterios:

- **Tarea y esfuerzo: revisado.** Volver conserva la posición; no se duplican datos
  del negocio para cambiar una imagen. Solo se pide la foto en ese momento.
- **Atención y ruido: revisado.** Jerarquía de nombre/servicio/precio, miniaturas
  más contenidas, secciones legibles y ayudas breves. Costes y revisión visibles.
- **Convenciones y consistencia: revisado.** Mismo tema verde en catálogo, cuenta
  y editor; Atrás de Android; selector de fotos del sistema.
- **Información y listados: revisado.** Recuento, filtros, búsqueda y estados
  distintos de fallo/vacío/sin coincidencias. No se añaden mapas ni proximidad.
- **Entrada: revisado.** Previsualización, límites, selección alternativa y
  conservación de la selección para reintentar. No se piden permisos generales
  de acceso a todos los archivos.
- **Respuesta y recuperación: revisado.** Procesamiento visible, envíos repetidos
  deshabilitados, idempotencia, consulta del estado tras fallo y confirmación al
  salir con una selección sin enviar.
- **Accesibilidad/adaptación: parcialmente revisado.** Semántica de imágenes,
  mensajes dinámicos, etiquetas, teclado de búsqueda y ES/EN/FR/DE. Pendientes
  revisión manual TalkBack, foco con teclado físico, tamaño de fuente ampliado,
  contraste medido y dispositivos estrechos. No es una auditoría de conformidad.
- **No aplicable:** fechas/reservas, edición múltiple, reordenación y pagos.

## Imagen genérica y foto propia

Hay seis ilustraciones originales distintas, una por oficio, incluidas en Compose
y disponibles sin conexión. Un perfil sin foto aprobada usa su primer oficio.
No hace falta generar una imagen cada vez que se registra un profesional.

Se intentó generar seis portadas fotográficas con la herramienta de imágenes,
pero las seis solicitudes fallaron con `Invalid credential`. **No se presentan
como generadas ni entregadas.** No se cambió de proveedor ni se contrató un
servicio: se mantienen las ilustraciones originales. Queda pendiente resolver ese
acceso si David desea sustituirlas por portadas fotográficas genéricas.

La foto propia se selecciona desde el dispositivo. Se lee con un máximo de
20 MB, se orienta y reduce a un máximo de 1600 píxeles, sin ampliar imágenes
pequeñas, y se vuelve a codificar a JPEG de hasta 2 MB. La copia nueva no conserva
GPS/EXIF; el original del teléfono no se modifica. No se sube el original cuando
la preparación falla.

## Contrato compartido y permisos

Migraciones nuevas `20261120000300`, `20261120000400` y `20261120000500`.
La última añade la prohibición expresa de autorrevisión, incluso para una cuenta
que por una configuración de confianza tuviese ambos roles.

- `approved_profiles.cover_path`: ruta opcional de la **última portada aprobada**.
  Compatible con clientes anteriores y con perfiles sin foto.
- Bucket privado `professional-covers`, solo JPEG de hasta 2 MB. No tiene lectura
  pública incondicional ni permisos de sustitución/borrado para clientes.
- `request_professional_cover(p_profile, p_request, p_sha256 = null)`: propietario
  verificado. Con hash reserva un objeto privado inmutable en estado `uploading`;
  sin hash propone volver a la imagen genérica, también con revisión.
- `submit_professional_cover(p_request)`: solo propietario actual; verifica objeto,
  MIME y tamaño del servidor antes de pasar a `pending`. No publica la foto.
- `professional_cover(p_profile)`: propietario actual; recupera estado y motivo
  de su última propuesta enviada.
- `review_professional_cover(p_request, p_approve, p_reason)`: administrador de
  la lista privada, motivo obligatorio, versión concreta y decisión inmutable.
  No reescribe el negocio. Rechazar mantiene la portada pública anterior.
  La decisión y su motivo quedan persistidos y el propietario los ve en el editor.
- Las propuestas antiguas no pueden aprobarse después de enviar una posterior.
  Los reintentos de la misma petición/decisión no repiten efectos.
- `can_read_professional_cover` y `can_write_professional_cover`: auxiliares RLS
  de Storage; no conceden roles ni aceptan identidad del cliente.

Storage solo permite leer un objeto al propietario de la propuesta, a una cuenta
administradora o cuando la instantánea pública aprobada apunta a ese objeto.
Las URL firmadas duran 15 minutos: una URL emitida antes de retirar una portada
puede seguir funcionando hasta caducar; no se promete revocación instantánea.
Las rutas nuevas usan UUID de perfil/propuesta, no identificadores de Auth.

No hay claves privilegiadas en Android, no se crean cuentas administradoras y
el propietario no puede aprobar su propio cambio. El panel futuro debe consumir
estas mismas operaciones con sesión administrativa real; no sustituirlas por
actualizaciones desde el cliente.

## Comprobaciones y límites

- pgTAP: propiedad, denegación a visitantes/terceros, aprobación administrativa,
  motivo obligatorio, idempotencia, versión pública intacta y objeto incompleto.
- `contract_covers.py --local`: Auth y Storage reales con tres cuentas
  desechables, rechazo de MIME/tamaño, aislamiento entre cuentas, objetos
  inmutables, revisión/rechazo, lectura anónima solo tras aprobación y vuelta a
  imagen genérica. Solo escribe/retira los UUID de esa ejecución en loopback.
- Compose: no mostrar «enviada» antes de la respuesta, reintento con la misma
  imagen/clave, conservación de lista y recuperación de búsqueda.
- Instrumentación de preparación: orientación, tamaño, eliminación de GPS y
  original intacto. El selector nativo y el retorno desde él requieren además
  comprobación manual en el emulador del usuario.

**Pendiente alojado:** revisar y aplicar las migraciones de portadas. La actualización
anterior de direcciones/servicios `20261120000200` ya fue confirmada por David y
verificada en remoto: 18 direcciones y tres precios por perfil; cuatro pruebas
de lectura correctas y dos pruebas de escritura omitidas deliberadamente.

La apariencia final y la edición de portada deben revisarse con David antes de
ampliar este diseño al resto de recorridos.

Resultados locales del 20/11/2026: 37 aserciones pgTAP, contrato real de Storage
completo, seis casos de catálogo y contrato de identidad correctos; 17 pruebas
instrumentadas API 35 correctas, incluidos orientación/GPS y editor. Suite JVM
completa, lint de las dos variantes y ambas APK debug correctos. Las dos pruebas
JVM de `ProfessionalCovers` ejercitan también el SDK real con HTTP controlado:
recuperación de subida duplicada y rechazo de falso éxito ante HTTP 503.

## Revisión independiente

### Standards

Tres defectos corregidos y cubiertos por regresión: impedir autorrevisión con
doble rol, no enviar la selección anterior tras fallar otra imagen y retirar la
clave de una propuesta genérica al comenzar otra intención. Se sustituyeron
además las cadenas de estado por el tipo serializado `CoverStatus`.

### Spec

Dos defectos corregidos: selección inválida no enviable y separación explícita
entre una previsualización nueva sin confirmar y la decisión de la propuesta
anterior. La revisión posterior confirmó la resolución de todos los hallazgos.
La entrega de fotografías genéricas generadas con IA, el despliegue alojado y
la aceptación visual/manual siguen distinguiéndose de lo implementado.
