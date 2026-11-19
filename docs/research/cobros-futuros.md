# De pagos simulados a cobros reales en España

Investigación: 19 de noviembre de 2026. No constituye asesoramiento jurídico,
fiscal ni una selección comercial de proveedor.

- Decisión investigada: [Investigar cómo evolucionar los pagos y la suscripción simulados a cobros reales](https://github.com/DRChanca/teloarreglo/issues/3).
- Contexto: [Teloarreglo: camino hacia la demo universitaria del 28 de noviembre](https://github.com/DRChanca/teloarreglo/issues/1).
- Alcance: demo Android para dos principiantes asistidos por agentes, con objetivo
  de infraestructura de unos 10 €/mes. Visión futura: España, pagos opcionales
  postservicio, ninguna comisión propia por trabajo, costes del proveedor al
  profesional; suscripción opcional de 5 €/mes por gestión y posicionamiento.

Se ha leído la actualización universitaria local de `docs/especificacion.md`,
que aún contiene cambios sin publicar en `main`. Este informe no modifica esa
especificación ni convierte las alternativas investigadas en decisiones aceptadas.

## Resultado ejecutivo

1. **La demo puede y debe ser honesta sin integrar proveedores:** pagos,
   suscripción y verificación simulados, sin dinero, tarjetas, IBAN, documentos
   de identidad ni credenciales de proveedores. El recorrido real entre dos
   cuentas no necesita depender de estas integraciones.
2. **Separar dos ventas:** reparación física del profesional al cliente y
   suscripción digital de la plataforma al profesional. Google Play prohíbe usar
   Play Billing para el servicio físico y exige, como regla general, usarlo para
   funciones digitales compradas en una app distribuida por Play [G1].
3. **Existe un camino técnicamente plausible sin comisión propia:** estudiar
   Connect con cargos directos al profesional, tarifas del procesador cobradas a
   su cuenta y sin comisión de aplicación. No basta con escoger el nombre
   «Connect»: configuración, aceptación del negocio, responsabilidad por pérdidas
   y contrato deben validarse [S1–S4].
4. **5 €/mes no equivale a 5 € de margen.** Impuestos, cuotas de distribución,
   procesamiento, soporte y riesgo dejan poco margen. No se acredita que operar
   pagos comerciales, revisión documental y reclamaciones quepa en 10 €/mes.
5. **Ningún procesador garantiza la reparación.** Verificar identidad para cobrar
   no certifica oficio, habilitación, seguro, solvencia ni calidad. No comprar
   una verificación adicional por defecto ni vincular la insignia a la cuota.

## 1. Demo del 28 de noviembre: qué mostrar y qué no afirmar

Propuesta de presentación, no ampliación de alcance:

| Área | Simulación admisible | Texto visible y límite |
| --- | --- | --- |
| Trabajo | Importe final ficticio, aceptación expresa, resultado y devolución de ejemplo | «Simulación: no se realizará ningún cargo». Botón «Simular pago», nunca recibo bancario auténtico |
| Pago externo | Registro de que se ha elegido pagar fuera de la app | «Pago externo declarado; Teloarreglo no lo ha procesado» |
| Suscripción | Activar/cancelar un periodo ficticio y mostrar herramientas/destacados | «Plan demo. Precio futuro orientativo: 5 €/mes. Sin cobro ni renovación real» |
| Verificación | Estados ficticios, claramente marcados | «Profesional ficticio. Verificación simulada; no se ha revisado documentación» |

No pedir números de tarjeta ni subir DNI «para que parezca real». No presentar
una pantalla propia como un diálogo auténtico de Google o Stripe. Un cambio
simulado de estado no demuestra SCA/3DS, KYC/KYB, liquidación bancaria, renovación
real, devolución real ni cumplimiento de Play. Tampoco demuestra aceptación de la
app en la tienda.

La demo puede conservar datos ficticios de estos estados, pero diferenciados de
operaciones reales y externas. No etiquetar como «cobrado» lo que solo es un
resultado simulado. Mantener verificación, plan de pago y habilitación para
cobros como dimensiones independientes.

**Guion breve:** «Hoy mostramos el flujo con pagos, suscripción y verificación
simulados. No movemos dinero ni revisamos documentos personales. Una versión
comercial necesitaría integración y contratos con proveedores, reglas de Google
Play y revisión legal y fiscal. No ofrecemos protección universal de reparaciones».

## 2. Android: servicio físico frente a ventaja digital

### Clasificación aplicable

- **Reparación presencial:** servicio físico. La política de pagos, sección 3,
  indica que no debe utilizar Play Billing; un PSP apto para plataforma puede
  procesar el pago opcional después del servicio [G1].
- **Estadísticas, exportación, plantillas y destacado:** desbloquean funciones y
  ventajas digitales dentro de la app. Que el comprador sea autónomo, que el
  servicio anunciado sea físico o que exista una web no convierte la suscripción
  en una reparación. La política cita expresamente software de productividad
  empresarial. La lectura prudente para una compra en la app de Play es Play
  Billing, salvo participación válida en una excepción/programa [G1].
- **Google Pay no es Google Play Billing.** Un monedero usado con un procesador
  para una reparación no sustituye al sistema exigido para la suscripción [G1, S4].
- No mezclar la cuota digital con el importe de una reparación ni descontarla
  silenciosamente de liquidaciones para eludir políticas.

### Opciones que quedan abiertas al promotor

| Ruta futura | Ventaja | Coste/condición |
| --- | --- | --- |
| Play Billing para la suscripción y PSP para trabajos | Separación clara y sin adhesión a facturación alternativa | Integración de dos ciclos de facturación y cuota Play |
| Programa EEE de facturación alternativa | Permite PSP alternativo para digital bajo sus condiciones | Registro empresarial, adhesión, APIs, asistencia, notificación de transacciones en 24 horas y cuota Google; no es «Stripe sin comisión de Play» |
| Distribución fuera de Play | Debe analizarse por separado | No se ha evaluado aquí su adquisición, distribución, seguridad ni requisitos; no se adopta como atajo |

En la consulta, Google publica para suscripciones de renovación automática en el
EEE una cuota del **10 % más 5 % de facturación cuando se usa Play Billing** [G2].
Para el programa EEE **alternativo sin elección de usuario** publica un **10 %**
desde el 30 de junio de 2026 [G3]. No confundir ese programa con otros de elección
o enlaces externos, ni aplicar porcentajes históricos de 2024 a un lanzamiento
de noviembre de 2026. Revalidar condiciones y contrato en Play Console antes de
presupuestar; no se ha abierto cuenta ni solicitado adhesión.

La oferta debe explicar ventajas recurrentes reales, precio total, periodicidad,
renovación, cancelación y continuidad gratuita. Incluir acceso sencillo para
cancelar; cancelación ordinaria no equivale a devolución y normalmente mantiene
acceso hasta fin del periodo, sin perjuicio de derechos legales [G4]. Destacado
debe identificarse y no garantizar posición ni clientes.

## 3. Cobros por trabajos: proveedores candidatos, no elección

### Stripe Connect en España

Las páginas españolas de Connect y Payments ofrecen un punto de partida público
para comparar tarifas [S1, S2]. El modelo que **merece validar** para esta visión
es un cargo directo en la cuenta conectada del profesional, omitiendo comisión
de aplicación. El profesional sería quien vende el trabajo; Teloarreglo no
recaudaría primero en su banco para repartirlo manualmente [S3, S4].

Connect permite elegir quién soporta determinadas tarifas. En la modalidad
«Stripe gestiona las tarifas de tus usuarios», Stripe publica que no hay cargos
adicionales de Connect para la plataforma por cuenta o liquidación cuando Stripe
cobra directamente el procesamiento a las cuentas conectadas [S1]. **No significa
que los pagos sean gratis ni que toda configuración traslade todo riesgo.**

La alternativa «Tú controlas las tarifas» publica **2 € por cuenta activa/mes y
0,25 % + 0,10 € por liquidación**, además del procesamiento; hay condiciones
adicionales según funciones [S1]. Un profesional gratuito también podría activar
cobros: esos costes no pueden estimarse únicamente por número de suscriptores.

La página general de precios y la detallada de Connect no presentan todo el
coste con el mismo desglose. Para contratar, pedir confirmación escrita del
modelo completo: cuenta, volumen, liquidación, impuestos de las tarifas,
contracargos, herramientas y pérdidas. No sumar todos los modelos como si
fueran acumulativos ni asumir el más barato por defecto [S1, S2].

**Responsabilidad importante:** en cargos directos los reembolsos y el principal
disputado reducen el saldo de la cuenta conectada; quién paga la tarifa de
disputa y quién responde del saldo negativo dependen de configuración/contrato.
En cargos de destino o cargos separados con transferencias, Stripe carga
tarifas, devoluciones y contracargos al saldo de la plataforma. Recuperarlos del
profesional no está garantizado [S3]. Por tanto, «costes al profesional» requiere
diseñar y contratar ese reparto, no solo escribirlo en condiciones de uso.

El onboarding alojado permite al proveedor recabar datos empresariales y de
identidad según país, entidad y capacidades, reduciendo el trabajo propio.
En Android utiliza navegador/Custom Tabs, no un WebView embebido. Volver desde el
formulario no prueba que la cuenta esté habilitada; hay que consultar requisitos
y cambios posteriores [S5]. Las guías actuales incluyen variantes/versiones
preview: fijar una versión soportada durante una integración futura, no copiar
sin más ejemplos.

### Alternativa útil: Mangopay

Mangopay documenta infraestructura para plataformas con pay-in, wallets,
transferencias y payout, y distingue el contrato de servicios de pago para
plataformas del EEE del de dinero electrónico para Reino Unido [M1, M2].
Incluye guía española KYB para sociedades y autónomos: por ejemplo, admite
determinados certificados censales o documentación registral como parte del
expediente, no como prueba universal de competencia profesional [M3].

Su precio público actual es **a medida, sin niveles fijos públicos ni plan
gratuito**, en función de volumen, productos y flujos [M1]. No es posible afirmar
que resulte más barato ni que entre en el presupuesto. Habría que solicitar, en
otra fase autorizada, aceptación del caso español, mínimos, costes de identidad,
pay-in/payout, devolución, disputas y quién soporta pérdidas. El modelo wallet
puede añadir conciliación innecesaria si solo se cobra postservicio a un
profesional por operación.

La guía KYC indica cambios desde el 1 de julio de 2026 para nuevas integraciones
EEE: restricciones a propietarios no verificados para recibir fondos, con
condiciones específicas sobre beneficiarios [M2]. No diseñar «que cobren primero
y ya verificaremos al retirar» basándose en artículos antiguos.

**Comparación resultante:** Connect ofrece más precio público para esta
estimación; Mangopay es un candidato para contrastar arquitectura y oferta
contractual, no una segunda tarifa equivalente demostrada. Ninguno está
seleccionado o contratado.

## 4. Identidad profesional, dinero y reclamaciones

### Tres controles distintos

1. **Acceso a cuenta:** autenticación y correo; no prueba negocio.
2. **Revisión del perfil por Teloarreglo:** relación de la persona con el negocio
   y comprobaciones concretas que defina el promotor. La insignia debe decir
   alcance y fecha; no certificar titulaciones, seguros o calidad sin comprobarlos.
3. **KYC/KYB para pagos:** identidad, empresa, representantes/titulares reales,
   cuenta bancaria y requisitos que determine el PSP. Su resultado puede cambiar
   y bloquear cargos o liquidaciones [S5, M2, M3].

Pagar 5 € no satisface ninguno de esos controles. No comprar Stripe Identity
como supuesto requisito general de Connect: es un producto aparte con tarifa
propia; comparar primero qué incluye el onboarding contratado [S1, S2, S5].
En producción, minimizar copias propias de DNI, controlar acceso y conservación,
y revisar bases jurídicas y contratos de datos; delegar onboarding no elimina
todas las obligaciones de privacidad de la plataforma.

### Flujo comercial mínimo que habrá que especificar

1. Profesional habilitado propone importe final del trabajo y concepto.
2. Cliente revisa y autoriza explícitamente después del servicio. Finalizar la
   cita o no responder durante siete días nunca inicia un cargo.
3. Servidor comprueba identidad, permisos, importe y moneda, crea operación
   idempotente; SDK del PSP recoge tarjeta y resuelve autenticación adicional
   cuando corresponda. Nunca guardar PAN/CVC ni claves secretas en Android [S4].
4. Webhook validado y conciliación con la API determinan resultado, no un
   booleano enviado por el móvil. Reintentos no deben duplicar cargos [S4].
5. Separar **pago cobrado**, **saldo disponible** y **liquidación bancaria**.
   Esta última tiene estados pendiente, en tránsito, pagada o fallida; los
   plazos dependen de disponibilidad y calendario. No prometer ingreso inmediato
   ni una fecha universal [S6].
6. Registrar importe bruto, costes reales, neto, IDs del proveedor, devoluciones
   y disputas. Estimación previa de tarifa no es coste definitivo para todas
   las tarjetas.

**Devolución voluntaria:** total o parcial, autorizada por quien corresponda,
limitada al importe cobrado y con registro/notificación. Stripe no devuelve las
comisiones de procesamiento originales; falta de saldo puede dejar la devolución
pendiente o hacerla fallar. La documentación actual advierte de fallo por saldo
insuficiente tras 45 días y de que puede ser necesaria otra vía. No mostrar
«dinero devuelto» cuando solo se ha solicitado [S3, S7].

**Contracargo:** reclamación por la red bancaria, distinta de una devolución y de
la mediación sobre calidad. Puede retirar dinero aun después de liquidado al
banco. Requiere responsable, alertas, plazos de respuesta, prueba de aceptación
del importe y del trabajo, conciliación y prevención de doble reembolso.
La configuración elegida debe aclarar principal, tarifas y pérdidas [S3, S7].
SCA/3DS no garantiza ganar cualquier disputa.

**Incidencia de reparación:** canal para contactar, aportar pruebas y mediar, sin
promesa de reembolso automático, custodia tipo escrow, seguro ni protección
equivalente a envíos de segunda mano. No se presupone que el PSP resuelva si una
reparación se hizo bien. Los derechos legales no se eliminan mediante un aviso.

## 5. Costes y sensibilidad de una cuota de 5 €

Son cálculos ilustrativos con las páginas consultadas, no ofertas. Moneda EUR,
tarjeta estándar EEE, sin conversión ni servicios extra [S1, S2].

| Caso | Cálculo | Resultado |
| --- | --- | --- |
| Trabajo de 100 € | 1,5 % × 100 + 0,25 | 1,75 € procesamiento; 98,25 € al saldo profesional antes de otros costes/impuestos |
| Trabajo de 20 € | 1,5 % × 20 + 0,25 | 0,55 €; el fijo pesa más |
| Suscripción web de 5 € con Stripe Billing por consumo | 1,5 % × 5 + 0,25 + 0,7 % × 5 | 0,36 € aproximadamente, antes de impuestos y cualquier cuota Google aplicable |
| Connect con tarifas controladas por plataforma: 10 profesionales activos | 10 × 2 € | 20 €/mes solo de cuentas, sin procesamiento ni liquidaciones |

La tercera fila **no autoriza vender la suscripción dentro de Play mediante
Stripe**. Si se usa facturación alternativa EEE se añade la cuota aplicable de
Google y sus requisitos [G1–G3]. Billing externo no se añade como coste si la
suscripción se procesa únicamente con Play Billing.

Para una suscripción nacional hipotética de **5 € con IVA incluido al 21 %**,
la base sería `5 / 1,21 = 4,13 €` y el IVA aproximadamente 0,87 €. Si la cuota
Play total fuese 15 % aplicada a esa base, quedarían aproximadamente
`4,13 × 0,85 = 3,51 €`, **antes** de infraestructura, soporte, asesoría, fraude,
otros impuestos y adquisición. La base exacta de liquidación, factura y
tratamiento de comisiones dependen del contrato y situación fiscal; es un
escenario, no un resultado garantizado [G2, A1].

Solo para visualizar escala, `10 / 3,51` exige alrededor de tres suscriptores
para cubrir **únicamente** 10 € de infraestructura en ese escenario. No acredita
viabilidad empresarial, sueldo ni cobertura de incidentes.

Stripe publica **20 € por disputa recibida** y otros **20 € por responder
manualmente**, devolviéndose esta última tarifa al ganar; existen más costes
según ruta [S2]. Una sola tarifa de 20 € equivale a casi seis meses del margen
ilustrativo de 3,51 €, sin contar principal ni trabajo de soporte. El riesgo
real para Teloarreglo depende de quién responda contractualmente; incluso
trasladándolo al profesional queda coste operativo.

Por tanto, los ~10 €/mes son un **objetivo de infraestructura de demo**, no un
presupuesto comercial validado. Antes de cobrar, modelar al menos profesionales
activos (incluidos gratuitos), trabajos/mes, ticket, tipo de tarjeta,
liquidaciones, reembolsos, disputas, soporte, altas/verificaciones y abandono.

## 6. Fiscalidad y obligaciones: hechos, supuestos y pendientes

**Verificado en fuentes primarias:**

- AEAT publica tipo general de IVA del 21 % [A1].
- No todas las reparaciones llevan el mismo IVA: AEAT indica 21 % como regla
  general y 10 % para determinadas obras de renovación/reparación de vivienda
  si cumplen condiciones. La página consultada, actualizada el 19/11/2026,
  incluye destinatario/uso, antigüedad, materiales no superiores al 40 % de
  base y determinados medios bancarios de pago. No copiar una regla abreviada
  antigua ni deducir el tipo solo del oficio [A2].

**Supuestos para trabajar, a confirmar con asesoría:**

- El profesional vende/factura la reparación y Teloarreglo su servicio digital.
  El recibo del PSP no se presume factura fiscal de la reparación.
- Los 5 € son precio final IVA incluido en el escenario anterior; el promotor
  todavía debe confirmar si esa es su definición comercial.
- Descontar comisiones del neto no permite presumir que solo el neto sea la
  venta del profesional, ni que el volumen de trabajos sea ingreso propio de
  Teloarreglo. Hay que distinguir facturación, cobros por cuenta ajena y gastos.

**Bloqueos de lanzamiento que este informe no resuelve:**

- Forma de actividad y altas; IVA nacional/UE, tratamiento B2B de suscriptores,
  localización, facturas de Google/PSP y posible inversión del sujeto pasivo.
- Obligaciones de facturación y software vigentes en la fecha de lanzamiento.
- Posible encaje como operador de plataforma sujeto a DAC7/modelo 238, diligencia
  y comunicación de datos: no asumir exención porque no se cobre comisión o
  porque el pago sea externo. Es una cuestión a verificar, no una conclusión
  fiscal de esta investigación.
- Condiciones de intermediación, derechos del cliente en contratación a distancia
  y reparaciones urgentes, alcance de mediación y transparencia del ranking.
- Si el flujo de fondos y funciones previstas introduce obligaciones regulatorias
  adicionales. No custodiar/repartir dinero por cuenta propia sin revisión.

No se ha podido recuperar la página concreta de AEAT sobre modelo 238 ni el
contenido de EUR-Lex intentado; por ello no se presenta una clasificación DAC7
como acreditada. La búsqueda web general también falló; los hallazgos positivos
se basan en lectura directa de las fuentes enlazadas. Estas limitaciones no
impiden resolver la investigación exploratoria, pero sí impiden declarar listo
un lanzamiento.

## 7. Evolución viable y puertas de salida

No son tareas de implementación nuevas ni compromisos comerciales:

1. **Ahora, demo:** simulación explícita y datos ficticios; ningún PSP ni
   documentación personal. Probar que suscripción no altera verificación y que
   finalizar una cita no autoriza pago.
2. **Antes de decidir proveedor:** promotor fija quién vende y responde, confirma
   precio con/sin IVA, distribución Android y límite de riesgo; compara ofertas
   completas para España con asesoría. No se elige por él.
3. **Prueba técnica posterior autorizada:** sandbox separado, datos de prueba
   oficiales, webhooks firmados, eventos repetidos/fuera de orden,
   autenticación adicional, rechazo, devolución pendiente y payout fallido.
   Para digital: verificar compra en servidor antes de dar ventajas,
   reconocerla y procesar cancelaciones, expiraciones y compras revocadas [G5].
4. **Piloto comercial solo tras aprobación:** contratos y actividad aceptados por
   proveedor, profesionales reales incorporados de forma segura, políticas de
   soporte y devolución, fiscalidad/facturas, conciliación, seguridad y
   presupuesto/reservas revisados. Mantener posibilidad de pagar fuera.

La suscripción debe tener un ciclo independiente del pago de trabajos y del
estado de verificación. Cancelarla conserva perfil gratuito y citas; las
ventajas se conservan hasta fin de periodo salvo devolución/revocación u otra
causa aplicable. Una app no debería conceder un destacado por cambiar una
bandera local: en producción debe derivarlo del derecho validado en servidor
[G4, G5].

**Conclusión:** se puede explicar una transición razonable sin contratar ni
implementar cobros para la universidad. La decisión comercial pendiente no es
«añadir un botón de pago», sino aceptar un modelo concreto de responsabilidades,
economía y cumplimiento.

## Fuentes primarias consultadas

Todas consultadas el 19/11/2026; páginas vivas, sujetas a cambios. Los resultados
son lectura de políticas y documentación, no confirmación contractual individual.

- **G1 — Google Play, política de pagos**, secciones 2, 3, 4, 8 y 9:
  https://support.google.com/googleplay/android-developer/answer/9858738?hl=es
- **G2 — Google Play, cuotas del servicio**, tablas regionales 2026:
  https://support.google.com/googleplay/android-developer/answer/112622?hl=es
- **G3 — Google Play, facturación alternativa EEE sin elección**:
  https://support.google.com/googleplay/android-developer/answer/12348241?hl=es
- **G4 — Google Play, política de suscripciones**; distingue además una
  actualización futura anunciada para enero de 2027:
  https://support.google.com/googleplay/android-developer/answer/9900533?hl=es
- **G5 — Android Developers, seguridad de Billing**, verificación, reconocimiento
  y revocación de compras:
  https://developer.android.com/google/play/billing/security
- **S1 — Stripe España, tarifas de Connect**:
  https://stripe.com/es/connect/pricing
- **S2 — Stripe España, tarifas de Payments, disputas, Billing e Identity**:
  https://stripe.com/es/pricing
- **S3 — Stripe, tipos de cargos y reparto de devoluciones/contracargos**:
  https://docs.stripe.com/connect/charges
- **S4 — Stripe, cargos directos en Android**, servidor, SDK y eventos:
  https://docs.stripe.com/connect/direct-charges.md?platform=android
- **S5 — Stripe, onboarding alojado**, variante Accounts v2:
  https://docs.stripe.com/connect/hosted-onboarding.md?accounts-namespace=v2
- **S6 — Stripe, liquidaciones de cuentas conectadas**:
  https://docs.stripe.com/connect/payouts-connected-accounts
- **S7 — Stripe, devoluciones, fallos y conservación de comisiones**:
  https://docs.stripe.com/refunds
- **M1 — Mangopay, precios y productos incluidos en oferta**:
  https://www.mangopay.com/pricing
- **M2 — Mangopay, KYC/KYB y restricciones EEE 2026**:
  https://docs.mangopay.com/guides/users/verification
- **M3 — Mangopay, documentación KYB española**, secciones ES Business/Soletrader:
  https://docs.mangopay.com/guides/users/verification/requirements/kyb-local#spain-es
- **A1 — AEAT, tipos de IVA**:
  https://sede.agenciatributaria.gob.es/Sede/iva/calculo-iva-repercutido-clientes/tipos-impositivos-iva.html
- **A2 — AEAT, obras de renovación o reparación en viviendas**:
  https://sede.agenciatributaria.gob.es/Sede/iva/iva-operaciones-inmobiliarias/que-tipo-se-aplica-obras-viviendas/obras-renovacion-reparacion.html
