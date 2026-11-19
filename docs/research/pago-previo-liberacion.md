# Pago previo y liberación diferida a profesionales en España

Investigación documental: 19 de noviembre de 2026. No es asesoramiento jurídico,
oferta contractual, selección de proveedor ni autorización para operar cobros.

- Pregunta: [Validar la viabilidad futura del pago previo y la liberación diferida](https://github.com/DRChanca/teloarreglo/issues/10).
- Mapa: [Teloarreglo: camino hacia la demo universitaria del 28 de noviembre](https://github.com/DRChanca/teloarreglo/issues/1).
- Regla aprobada: [Acordar el guion y los criterios de éxito de la demo universitaria](https://github.com/DRChanca/teloarreglo/issues/4#issuecomment-7015305261).
  La referencia vinculante es su comentario de resolución, no los ejemplos
  históricos incompatibles de la especificación.
- Antecedente leído: [De pagos simulados a cobros reales en España](https://github.com/DRChanca/teloarreglo/blob/research/cobros-futuros/docs/research/cobros-futuros.md),
  commit `4fef0df`, que estudiaba pago **posterior** al servicio.

Se han leído el ticket, el mapa, la resolución del guion y la especificación
local actualizada. Esta última tiene cambios del usuario aún no publicados en
main; no se copia ni modifica. El worktree parte de main. No se encontraron
GLOSSARY.md ni ADR relacionados en la exploración inicial.

## Conclusión

**El flujo es técnicamente plausible mediante un proveedor para plataformas,
pero no está validado para producción.** Cobrar antes de la cita y posponer
la entrega exige elegir contractualmente dónde queda el saldo, quién puede
moverlo y quién responde de devoluciones y pérdidas. No basta con reutilizar la
opción de cargos directos sugerida para cobros posteriores [S2–S5, M1–M5].

La demo mueve únicamente importes ficticios, muestra aprobación manual del
cliente y liberación simulada. Las **48 horas se explican como regla prevista**:
no se promete una automatización implementada o probada, ni ingreso bancario
en ese plazo. Ninguna de las rutas estudiadas permite afirmar «Teloarreglo
custodia tu dinero», «escrow» o «reparación garantizada» [S3, S6, S8, M1, M6].

## 1. Regla de producto que esta investigación no cambia

Según la resolución del guion:

1. Pago integrado completo previo para confirmar la cita; cita gratuita sin
   pago y acuerdo para pago directo como alternativas.
2. Profesional marca realizado; cliente confirma para liberar el importe.
3. Sin respuesta **ni incidencia**, liberación prevista a las 48 horas desde la
   marca del profesional, no desde el pago ni desde la hora programada.
4. Incidencia antes de liberar: detener entrega y revisar; no devolución
   automática ni retención indefinida asumida.
5. Cancelación antes del inicio: devolución total. Después del inicio: pendiente
   de definición, sin inventar penalizaciones o protección económica.
6. Variación del precio aceptada expresamente: aumento con pago de diferencia
   antes del adicional; reducción con devolución de diferencia.

Son condiciones de producto, no garantías de aceptación del proveedor. La
investigación anterior conserva su utilidad para separar suscripción digital,
reparación física y verificación; sus ejemplos de cobrar al terminar y «el
silencio nunca inicia un cargo» no describen este nuevo cobro previo. Aquí el
silencio no crea un nuevo cargo: desencadenaría una entrega de fondos ya cobrados.

## 2. No confundir las cinco operaciones

| Concepto | Qué sucede | Qué no demuestra |
| --- | --- | --- |
| Autorización | El emisor reserva capacidad de pago temporalmente | Que el PSP haya recibido el pago o pueda mantenerlo hasta una cita lejana |
| Captura / pay-in | Se ejecuta el cobro; entra en el circuito y saldo del proveedor | Que el dinero ya sea disponible para transferir o esté libre de contracargo |
| Saldo pendiente/disponible | Registro del proveedor; pasa a disponible según liquidación, riesgo y requisitos | Una cuenta bancaria propia de Teloarreglo o saldo segregado por cita por defecto |
| Transferencia interna | Saldo de plataforma a cuenta conectada, o wallet a wallet | Ingreso en el banco; revertirla tampoco equivale a devolver al cliente |
| Payout / liquidación externa | Saldo del proveedor hacia banco del profesional | Cobro al cliente ni firmeza irreversible del pago |

Las palabras anteriores describen operaciones de Stripe y Mangopay, no una
equivalencia jurídica entre sus productos [S1–S7, M1, M3, M6].
**Liberar** debe concretarse después como autorizar una transferencia o un
payout en una arquitectura determinada; nunca mostrarlo como «ya ingresado».

Ejemplo: cita dentro de tres semanas y finalización al día siguiente. Esperar
esas semanas más 48 horas para capturar una autorización ordinaria puede dejarla
caducar. Capturar al reservar y transferir después es otro modelo, con costes
y responsabilidad distintos [S1, S2, M2].

## 3. Stripe Connect: alternativas que hay que contrastar

España figura como región admitida para cargos separados y transferencias [S4].
La admisión pública del país no significa aprobación de Teloarreglo, del oficio,
de importes, del plazo hasta la cita ni del contrato concreto.

### Autorización no es pago previo completo

Stripe publica para tarjetas online ventanas ordinarias de 7 días en muchas
combinaciones; Visa MIT tiene 5 días nominales (4 días y 18 horas exactas).
Hay que consultar `capture_before` para la operación concreta. Extensiones
dependen de elegibilidad; no son una solución universal. Caducada la autorización,
se libera la reserva y se cancela el pago [S1].

Por tanto, no describir `capture_method=manual` como «hemos cobrado y el
proveedor guarda indefinidamente hasta que se arregle». Capturar antes de
vencer cambia cuándo paga el cliente, no resuelve por sí solo la entrega al
profesional [S1, S2].

### Dónde se retrasa el dinero

| Ruta candidata, sin selección | Encaje y límite | Responsabilidad documentada |
| --- | --- | --- |
| Cargo directo + payout controlado | El saldo ya está en la cuenta conectada del profesional; se retrasa su salida al banco, no una transferencia futura a esa cuenta | Reembolsos/principal disputado reducen saldo conectado; tarifas y pérdidas dependen de configuración |
| Destination charge + payout controlado | La transferencia al profesional es inmediata tras el cargo; por sí sola no cumple «transferir al confirmar realización» | Devoluciones y contracargos reducen saldo de plataforma |
| Cargo separado + transferencia posterior | Se desacopla el cobro previo de la transferencia; se podría solicitar esta tras el evento de liberación | Procesamiento, devoluciones y contracargos cargados al saldo de plataforma; recuperar del profesional no está garantizado |

Fuentes: tipos de cargos [S2], transferencias [S4] y control de calendario [S5].
No cobrar comisión propia no elimina esos costes y obligaciones.

**Payout manual:** Stripe indica máximo **90 días en países distintos de
Tailandia y EE. UU.**, por tanto España. Es un límite de permanencia bajo esa
modalidad, no «90 días desde que termina la reparación» ni una autorización
para escrow. Debe contemplarse desde que los fondos permanecen en saldo, con
confirmación contractual del cómputo exacto. Una reserva lejana, un aplazamiento
o una incidencia larga pueden agotar el plazo [S3].

Controlar calendario depende de permisos/modelo de responsabilidad o controles
de plataforma; no se debe asumir que una cuenta autónoma con Dashboard completo
queda sin posibilidad de retirar por voluntad de Teloarreglo. Un retraso fijo
`delay_days_override` (hasta 31) cuenta desde el cargo, no desde «realizado»,
y no sustituye una condición por trabajo [S5].

La cuenta contiene fondos agregados: hay que conciliar por operación para que
un payout de otro trabajo no extraiga dinero pendiente por incidencia. En cargos
separados, Stripe documenta segregación de fondos como **private preview**:
no asumir acceso ni protección por operación en el producto estándar. Asimismo,
devolver el cargo no revierte automáticamente sus transferencias [S4].

### Plazos que no son las 48 horas

- Tabla pública de España: disponibilidad inicial de referencia **7 días
  naturales**, posterior por defecto **3 días laborables**, sujeta a riesgo.
  La primera liquidación puede tardar normalmente 7–14 días según actividad,
  país y riesgo. No sumar esos dos valores como retrasos independientes [S6].
- El calendario de payouts no acelera la disponibilidad del saldo. Un payout
  manual suele tardar **1–4 días laborables**; hay rutas EUR más rápidas para
  elegibles, sin prometerlas a todos [S6].
- La liberación comercial a las 48 horas podría autorizar una operación todavía
  pendiente por saldo o verificación. Una API aceptada no equivale a llegada
  bancaria. Hay transferencias fallidas que Stripe no reintenta solo [S4, S6].

Stripe dice expresamente que **no proporciona servicios ni cuentas escrow**.
Su capacidad de retrasar payouts no cambia esa afirmación [S3].

## 4. Mangopay: wallets y transferencias, no custodia de la plataforma

Mangopay documenta pay-in → wallet → transferencia entre wallets → payout al
banco. Los fondos se mantienen en su entorno, no en la cuenta bancaria de
Teloarreglo. La documentación distingue **Payment Services para plataformas
EEE con Mangopay S.A.** de Electronic Money Services para Reino Unido: no
trasladar sin más condiciones UK a España [M1, M3].

Posible ruta a validar: capturar al reservar en la wallet admitida para ese
flujo, y condicionar transferencia/payout al evento de liberación. Debe
acordarse titular y beneficiario desde el pay-in, permisos, derechos sobre saldo
y bloqueo por incidencia. Crear una wallet no acredita por sí solo escrow ni
permite ocultar el beneficiario hasta después del cobro [M1, M3].

- Preautorizaciones documentadas de **7 o 30 días**; siguen siendo reservas
  antes de captura y no sustituyen el pago completo previo. Mangopay advierte
  que algunos bancos de España adeudan el importe al usuario antes de capturar,
  manteniéndolo el banco hasta captura/liberación. No prometer cómo aparecerá
  universalmente en el extracto [M2].
- Desde **1/7/2026**, para nuevas integraciones EEE, un OWNER no verificado
  no puede recibir transferencias y solo puede recibir ciertos pay-ins si se
  declaran beneficiarios todos verificados o PLATFORM. El payout en Payment
  Services requiere KYC/KYB verificado [M3, M4].
- El sistema publica wallets «as long as needed», y su página de límites
  KYC no impone límite de importe de saldo para PS EEE. **Ninguna de esas
  frases valida un plazo contractual ilimitado de retención por disputa**:
  confirmar reservas anticipadas, duración máxima e incidencias expresamente.
  No se ha acreditado aquí un máximo numérico equivalente a los 90 días [M1, M4].
- Transferencias OWNER → OWNER pueden requerir SCA. Para iniciarlas sin usuario
  presente, `USER_NOT_PRESENT` exige proxy legal, consentimiento otorgado al
  proveedor y aprobación pertinente; si falta o se revoca puede fallar.
  No asumir que un temporizador de 48 horas basta para mover dinero [M5].
- Payout a cuenta bancaria a nombre del usuario, Recipient registrado y rutas
  según contrato. EUR/SEPA SCT publica entrega típica el mismo día laborable
  antes del corte **12:30 CET**, después siguiente día laborable; SCT Inst está
  disponible según condiciones. Son tiempos indicativos, con posibles revisiones
  AML y demoras bancarias, no promesa de dinero en 48 horas [M6].

El campo `Fees=0` significa que la plataforma no toma tarifa en esa transacción,
**no que Mangopay no cobre**. Sus costes se facturan a la plataforma por contrato
y pueden adeudarse en su banco. Trasladar únicamente costes al profesional
necesita acuerdo y conciliación, no se consigue suponiendo que `Fees` sea la
comisión del procesador [M9].

## 5. Devoluciones, cambios de precio y contracargos

### Cancelar antes del inicio: devolver el total no equivale a coste cero

Tras capturar hay que **reembolsar**, no cancelar una autorización. En Stripe las
comisiones originales de procesamiento no se devuelven. Para devolver 100 € al
cliente aunque el saldo neto sea menor debe financiarse la diferencia; decidir
quién la soporta sin descontarla silenciosamente del reembolso total acordado.
Stripe indica llegada habitual del reembolso en **5–10 días laborables**,
según banco. Falta de saldo puede dejar pendiente una devolución de tarjeta;
si persiste 45 días falla. Otros métodos pueden fallar directamente [S7].

En cargos separados puede requerirse devolución al cliente **y** reversión de
transferencia; una no prueba la otra. No confiar en recuperar automáticamente
fondos ya retirados [S2, S4]. En Mangopay, si hubo transferencia, pueden ser
necesarios transfer refund y pay-in refund: el primero solo devuelve saldo
entre wallets. Los transfer refunds requieren transferencia de los últimos
13 meses; el plazo del pay-in depende del método y su estado. No se ha
acreditado un plazo universal de abono bancario de reembolso Mangopay [M7].

### Precio modificado, siempre con aceptación expresa

Consecuencia propuesta del flujo, no implementación: conservar presupuesto y
versión aceptada; aumento de 100 a 130 € implica nuevo pago de 30 € consentido,
con posible autenticación y fallo, no aumentar silenciosamente el cargo ya
capturado. Reducción a 80 € implica refund de 20 € y reducir el importe elegible
para entrega. Ambos proveedores documentan reembolsos parciales con límites;
hay que conciliar múltiples cobros/reembolsos de la misma cita [S1, S7, M7].

Si el adicional falla, no mostrar «total pagado» ni ejecutar trabajo adicional
como si estuviera aprobado y cobrado. Cancelación total deberá considerar también
las diferencias ya cobradas y devoluciones ya efectuadas.

### Incidencia interna ≠ disputa bancaria

La incidencia previa detiene la entrega prevista mientras se revisa. Una vez
pagado al banco no puede afirmarse «dinero congelado» por cambiar un estado local.
Una incidencia debe tener responsable, plazo de atención, pruebas, escalado y
salida antes de los límites contractuales; detener no significa reembolsar ni
retener indefinidamente [S3–S7, M6–M8].

Las **48 horas no extinguen** derechos o contracargos:

- Stripe explica ventana habitual de disputa de tarjeta de **120 días**, con
  excepciones más largas; para servicios futuros puede contarse desde la fecha
  del servicio. Respuesta usual **7–21 días**, resolución total **2–3 meses**;
  el emisor decide, no Stripe ni Teloarreglo [S8].
- Con cargos indirectos Stripe debita importe y tarifa a la plataforma aun con
  `on_behalf_of`; con directos el principal reduce saldo conectado y el resto
  depende de configuración. Confirmación del cliente o 3DS no garantizan
  inmunidad frente a reclamaciones [S2, S8].
- Mangopay debita la **Repudiation Wallet de la plataforma**. Si se pierde,
  la plataforma debe cubrir su deuda, recuperando desde wallet del usuario o
  aportando fondos. El plazo de pruebas viene en `ContestDeadlineDate`,
  normalmente **7–18 días**; algunas disputas no admiten oposición [M8].

Hace falta reserva de liquidez, responsable de alertas y pruebas, conciliación
para evitar doble reembolso y política de pérdidas. «Sin comisión propia»
no permite afirmar «sin riesgo para la plataforma» [S2, S7, M8, M9].

## 6. KYC y responsabilidad: condiciones previas, no insignia de calidad

Stripe exige datos según país, estructura, capacidades y contrato; puede añadir
requisitos, suspender cargos o payouts y revisar posteriormente. Un retorno del
onboarding no demuestra habilitación: verificar capacidades/requisitos vigentes.
Los nombres `charges_enabled` y `payouts_enabled` son del ejemplo Accounts v1;
una integración futura debe fijar su versión, no mezclar APIs [S9].

En Mangopay deben respetarse categorías, titular, beneficiario y cambios de
verificación; no diseñar «primero cobran y ya verificarán al retirar» para una
nueva plataforma española en 2026 [M3, M4].

KYC/KYB identifica para pagos; no verifica calidad, oficio, seguros o reparación.
La revisión comercial del perfil y suscripción permanecen independientes.
Contratos deben identificar vendedor del servicio, intermediario, proveedor,
responsable de principal/tarifas/saldos negativos, financiación de devoluciones
y protección aplicable. No ingresar cobros de terceros en el banco propio para
repartirlos manualmente ni atribuir a la plataforma custodia no autorizada.
El encaje regulatorio y los derechos del consumidor requieren revisión profesional;
esta lectura de proveedores no acredita cumplimiento legal español.

## 7. Presentación: formulación honesta

**Se puede decir:**

> «La demo usa dinero simulado: el cliente paga antes para confirmar y,
> cuando confirma que el trabajo se realizó, mostramos la liberación simulada.
> La regla prevista para el futuro es liberar tras 48 horas desde que el
> profesional marca realizado, si no hay respuesta ni incidencia. Aquí ese
> plazo solo se explica; no estamos demostrando una automatización de 48 horas.
> Una incidencia previa detiene la entrega y cancelar antes de empezar implica
> devolución total. Para dinero real necesitamos validar proveedor y contratos;
> liberar no significa ingreso inmediato en el banco ni garantía de reparación».

**No se puede decir:** «guardamos el dinero», «escrow», «fondos garantizados»,
«el técnico cobra en su banco exactamente a las 48 horas», «el PSP resuelve la
calidad», «después de 48 horas no se reclama», «devolución instantánea sin coste»
o «ya tenemos pagos reales integrados». No pedir tarjetas, IBAN o DNI reales;
no crear cuentas ni usar logos para aparentar un contrato inexistente.

## 8. Qué exige validar antes de producción

No son tareas de construcción aprobadas ni elección comercial:

1. **Proveedor y contrato español:** aceptación de reparaciones, cobro previo,
   anticipo máximo de reservas y aplazamientos, titular y beneficiario del saldo,
   condición exacta de liberación, máximo de retención y tratamiento de incidencias
   largas/cierre de cuentas; confirmar límites sin extrapolar un producto a otro.
2. **Quién controla la salida:** impedir payouts/retiros prematuros, posibilidad
   de separar por cita, SCA/proxy cuando proceda, restricciones KYC y conducta
   ante revocación/bloqueo. No presentar capacidades privadas como contratadas.
3. **Economía y responsabilidad:** oferta completa (procesamiento, cuenta,
   transferencia/payout, KYC, reembolso, disputa, reservas y mínimos), cómo se
   descuentan costes al profesional, quién financia devolución íntegra y pérdidas.
   No se revalida aquí el margen de suscripción ni el presupuesto de 10 €/mes.
4. **Operativa y legal:** definición observable de inicio, resolución de
   incidencias, cambios aceptados, cancelación tras inicio aún pendiente,
   avisos y pruebas, derechos de consumidores, condiciones de pago e información
   transparente sobre tiempos, privacidad y obligaciones regulatorias.
5. **Validación técnica posterior autorizada:** cobro confirmado por proveedor
   y coordinación con disponibilidad de cita; fallo de autenticación, reintentos
   idempotentes, webhooks duplicados/fuera de orden, saldo insuficiente y
   conciliación; no confirmar citas por una respuesta manipulable del móvil.
   Las APIs documentan estados/eventos que pueden requerir espera [S4, M5].
6. **Reloj y concurrencia futura:** hora de servidor para marca de realizado,
   notificación y plazo absoluto, impedir reinicios abusivos, resolver carrera
   incidencia/confirmación/liberación en una única decisión consistente, comprobar
   de nuevo antes de enviar al PSP y conciliar resultado incierto. Ningún proveedor
   conoce automáticamente esos hechos de la reparación: lógica y pruebas son de
   la integración, no una garantía derivada de estas páginas.

**Cierre de investigación:** hay alternativas documentadas suficientes para
explicar el producto y sus límites sin seleccionar proveedor. No hay evidencia
contractual ni integración que permita declararlo listo para cobrar. La demo
acordada sigue siendo simulada y la regla de 48 horas únicamente explicada.

## Fuentes oficiales consultadas

Consulta: 19/11/2026. Páginas vivas; revalidar antes de contratar. Solo se emplean
documentación de los propios proveedores para los hechos de pagos. Los tickets
son fuente de las reglas de producto, no evidencia de prestaciones del PSP.
Un intento inicial de `/guides/payment-flows` de Mangopay devolvió 404; se usó
su índice oficial para localizar las guías leídas. No se abrieron cuentas,
se contactó con comerciales ni se procesó dinero.

- **S1 — Stripe, autorización/captura y ventanas:**
  https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
- **S2 — Stripe, tipos de cargo y responsabilidad:**
  https://docs.stripe.com/connect/charges
- **S3 — Stripe, payouts manuales, 90 días y exclusión escrow:**
  https://docs.stripe.com/connect/manual-payouts
- **S4 — Stripe, cargos separados, transferencias, reversión y segregación:**
  https://docs.stripe.com/connect/separate-charges-and-transfers.md?platform=web&integration=custom&ui=elements&api-integration=paymentintents
- **S5 — Stripe, permisos y calendario de payouts:**
  https://docs.stripe.com/connect/manage-payout-schedule
- **S6 — Stripe, disponibilidad y plazos por país:**
  https://docs.stripe.com/payouts
- **S7 — Stripe, devoluciones, comisiones, plazos y fallos:**
  https://docs.stripe.com/refunds
- **S8 — Stripe, disputas y plazos:**
  https://docs.stripe.com/disputes/how-disputes-work
- **S9 — Stripe, requisitos de verificación y suspensiones (Accounts v1):**
  https://docs.stripe.com/connect/handling-api-verification.md?accounts-namespace=v1
- **M1 — Mangopay, sistema de wallets:**
  https://docs.mangopay.com/guides/e-wallet-system
- **M2 — Mangopay, preautorización y particularidad de bancos españoles:**
  https://docs.mangopay.com/guides/payment-methods/card/preauthorization
- **M3 — Mangopay, KYC/KYB y reglas EEE de julio de 2026:**
  https://docs.mangopay.com/guides/users/verification
- **M4 — Mangopay, límites KYC de payout/saldo:**
  https://docs.mangopay.com/guides/users/limits
- **M5 — Mangopay, SCA y proxy en transferencias:**
  https://docs.mangopay.com/guides/sca/transfers
- **M6 — Mangopay, rutas, cortes y plazos indicativos de payout:**
  https://docs.mangopay.com/guides/payouts
- **M7 — Mangopay, devoluciones de pay-in/transferencia/payout:**
  https://docs.mangopay.com/guides/refunds
- **M8 — Mangopay, disputas y deuda de plataforma:**
  https://docs.mangopay.com/guides/disputes
- **M9 — Mangopay, Fees frente a costes facturados a plataforma:**
  https://docs.mangopay.com/guides/fees
