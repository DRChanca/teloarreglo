# Registro, verificación, recuperación y acceso con Google

Ticket: [Registrarse, verificar correo, recuperar contraseña y acceder con Google](https://github.com/DRChanca/teloarreglo/issues/13).
Rama: `feat/13-registro-google`. Responsable: Marcos.
Estado: **código escrito (20/11) pero sin compilar ni probar todavía**: este portátil no tiene SDK Android.
Nada configurado aún en Supabase, Brevo ni Google Cloud.

Parte de la base ya fusionada de [Acceder por correo y conservar sesión](https://github.com/DRChanca/teloarreglo/issues/12):
módulo `identity/` (`Identity`, `SupabaseIdentity`), `current_account()` en servidor
y una única base Supabase compartida con los datos de presentación.

## 1. Qué cambia en la interfaz de identidad

`Identity` crece con operaciones nuevas; la presentación no habla con el SDK directamente.

| Operación | Comportamiento esperado |
| --- | --- |
| `signUp(email, password)` | Crea la cuenta y deja el estado en «revisa tu correo». No conecta: la cuenta sin verificar no accede. |
| `resendVerification(email)` | Reenvía el correo. Respeta los límites de Auth y lo dice si se superan. |
| `requestPasswordReset(email)` | Envía el enlace. Misma respuesta exista o no la cuenta, para no revelar qué correos están registrados. |
| `handleAuthLink(uri)` | Recibe el enlace de vuelta a la app (verificación o recuperación) y canjea el código. |
| `setNewPassword(password)` | Solo en el estado de recuperación abierto por un enlace válido. |
| `signInWithGoogle(...)` | Acceso Google real. Cancelar no es un error. |

Estados nuevos: `AWAITING_VERIFICATION`, `RECOVERING_PASSWORD`. Errores nuevos:
`LINK_INVALID` (enlace caducado, usado o abierto en otro dispositivo),
`RATE_LIMITED`, `WEAK_PASSWORD`, `EMAIL_IN_USE` (solo donde no revele cuentas).

Invariantes heredadas: las capacidades salen de `current_account()`, nunca de
metadatos que el usuario pueda editar; registrarse no concede administración.

## 2. Decisiones propuestas (pendientes de acordar)

1. **Google con el selector nativo de Android** (Credential Manager), no con el
   navegador. supabase-kt lo ofrece en `compose-auth` (`googleNativeLogin`) y
   canjea el token con `signInWith(IDToken)`. Es más fluido y se cancela con
   «atrás». Requiere cliente OAuth Web (para Supabase) y clientes Android por
   paquete y huella de firma.
2. **Flujo PKCE** para los enlaces de correo. La app vuelve con un esquema por
   variante: `es.teloarreglo.pruebas://auth` y `es.teloarreglo.presentacion://auth`
   (las dos variantes pueden convivir instaladas sin pedir «abrir con…»).
3. **Verificar desde otro dispositivo sigue valiendo**: si el correo se abre en el
   ordenador, la cuenta queda verificada en servidor y se entra con contraseña;
   la app no lo trata como fallo. La **recuperación** sí exige abrir el enlace en
   el móvil que la pidió (PKCE); si no, `LINK_INVALID` con explicación.
4. **Contraseña mínima de 8 caracteres** en Auth (por defecto son 6).
5. **Remitente Brevo verificado sin dominio propio** para la demo. Comprar
   dominio requiere aprobación aparte. Riesgo conocido: un remitente @gmail
   enviado por Brevo puede ir a spam o ver su dirección sustituida; se comprueba
   entregando a Gmail y Outlook reales antes de dar el ticket por bueno.
6. **Cuentas de prueba en la base compartida** con alias `+`
   (`tucorreo+registro1@…`). No se borran usuarios sin consultarlo con David.

## 3. Huellas de firma: punto delicado

Google solo acepta la app si su **paquete + huella SHA-1** está registrado.
Cada ordenador genera su propio `debug.keystore`, así que las APK de David
(Mac), de Marcos y de GitHub Actions tendrán huellas distintas.

Propuesta: registrar en Google Cloud la huella de cada máquina que compile APK
para probar Google (dos clientes Android por máquina: `.pruebas` y
`.presentacion`). Alternativa: un keystore de depuración compartido fuera de Git.
Decidir con David antes de grabar la demo.

## 4. Guía de pasos humanos (en orden)

Ninguno exige comprar nada. No compartir claves por chat ni subirlas a Git.

### A. Acceso al proyecto Supabase (David)
- [x] Invitar a Marcos a la organización de Supabase del proyecto único, con
      permiso para cambiar la configuración de Auth (20/11: rol Administrator).

### Hallazgo del 20/11

El proyecto `teloarreglo-pruebas` está en plan **Free** pero en la región
**West EU (Ireland, eu-west-1)**, no en Frankfurt como dice la especificación.
La región no se puede cambiar sin crear otro proyecto. Decidir con David si se
acepta Irlanda (sigue en la UE) y actualizar la especificación.

### B. Brevo (Marcos)
- [x] Cuenta Brevo Free del equipo (300 correos/día), titular hackathonuniovi@gmail.com.
- [x] Remitente verificado: `Teloarreglo <hackathonuniovi@gmail.com>` (Brevo avisa de que
      no se recomienda un dominio gratuito; sin firma DKIM propia).
- [x] Clave SMTP `supabase-teloarreglo` creada el 20/11 (activa, caduca el 20/11/2027).
      Servidor `smtp-relay.brevo.com`, puerto 587, usuario `c45278001@smtp-brevo.com`.
      La clave solo está en Supabase y en el gestor de contraseñas de Marcos.
      No activar el bloqueo de IP no autorizadas: Supabase envía desde IP variables.

### C. Supabase Auth (Marcos, con acceso de A)
- [x] Authentication → Emails → SMTP Settings (20/11): SMTP propio activo con Brevo.
      Límite de envío subido automáticamente a **30 correos/hora** y 60 s mínimo
      entre correos al mismo usuario (un reenvío inmediato dará `RATE_LIMITED`).
- [x] Authentication → Rate Limits: revisado (20/11). **2 correos/hora** y bloqueado
      mientras no haya SMTP propio; con Brevo se podrá subir.
- [x] Authentication → Providers → Email (20/11): **Confirm email activo**, longitud
      mínima **8** guardada. «Require current password when updating» está
      desactivado: debe seguir así o la recuperación de contraseña fallará.
- [x] Authentication → URL Configuration → Redirect URLs (20/11): añadidas
      `es.teloarreglo.pruebas://auth**` y `es.teloarreglo.presentacion://auth**`
      (la app añade `?flow=signup` o `?flow=recovery`; el comodín lo cubre).
- [x] Plantillas de correo revisadas (20/11): las de serie, con el enlace estándar
      de confirmación; solo se pueden editar tras configurar el SMTP propio.

### D. Google Cloud (Marcos, compartido con David)
- [x] Proyecto Google Cloud `teloarreglo` (21/11), cuenta del equipo, sin organización
      ni facturación. David accede con la cuenta del equipo.
- [x] Pantalla de consentimiento (21/11): app «Teloarreglo», **Externo**, estado **Prueba**,
      asistencia y contacto `hackathonuniovi@gmail.com`.
- [x] Usuarios de prueba (21/11): `marcosarov7k@gmail.com` y `hackathonuniovi@gmail.com`.
      En modo Prueba solo esas cuentas pueden entrar con Google (límite 100). Para que
      David pruebe con su cuenta personal, hay que añadirla aquí.
- [x] Cliente OAuth **Aplicación web** `Supabase Auth (web)` (21/11):
      ID `852089134796-mihgv1588q6lq17lbovpupgq6i7ljhsd.apps.googleusercontent.com`,
      redirección `https://ipqydkulietvxdbdlkcv.supabase.co/auth/v1/callback`.
      Secreto vigente `****Xt89` (solo en Supabase); el primero, `****zlNN`, se perdió
      sin usarse y se deja habilitado por decisión de Marcos (21/11). El ID está en el `.env` local como `GOOGLE_WEB_CLIENT_ID`.
- [x] Supabase → Providers → Google (21/11): **activado** con ese ID y secreto; *Skip
      nonce checks* y *Allow users without an email* desactivados.
      `/auth/v1/settings` anuncia `email` y `google`.
- [ ] **El lunes, al poder compilar:** clientes OAuth tipo **Android** para
      `es.teloarreglo.pruebas` y `es.teloarreglo.presentacion` con la SHA-1 de
      cada máquina (`keytool -list -v -keystore ~/.android/debug.keystore`,
      alias `androiddebugkey`, contraseña `android`).

## 5. Comprobaciones previstas para aceptar el ticket

- Registro nuevo → correo real recibido (Gmail y Outlook) → verificación →
  acceso; sin verificar, el acceso se rechaza con `UNVERIFIED`.
- Reenvío y límite superado con mensaje claro.
- Recuperación completa y vuelta a la app; enlace caducado, reutilizado y de
  otro dispositivo con `LINK_INVALID`.
- Google: acceso, cancelación y vuelta; cuenta Google con el mismo correo que
  una de contraseña (comportamiento de vinculación de Supabase a comprobar).
- Registro con metadatos que intenten marcar administración: sigue sin serlo.
- Sesión persistente y cierre de sesión, igual que en el ticket de acceso.
- Todo en ES/EN/FR/DE.

## 6. Qué hay implementado (pendiente de compilar)

- `Identity`: `signUp`, `resendVerification`, `requestPasswordReset`, `handleAuthLink`,
  `setNewPassword`, `completeGoogleSignIn`, `reportGoogleFailure`, `dismiss`.
  Estados `AWAITING_VERIFICATION` y `RECOVERING_PASSWORD`; avisos `AccessNotice`.
- `SupabaseIdentity`: PKCE; enlaces `<applicationId>://auth?flow=…`; códigos de error
  de Auth traducidos a `LINK_INVALID`, `RATE_LIMITED`, `WEAK_PASSWORD`. La recuperación
  no se convierte en sesión normal al volver a primer plano; un enlace inválido sigue
  visible tras la restauración.
- App: Auth con PKCE y esquema por variante; `ComposeAuth` con Google nativo solo si
  `GOOGLE_WEB_CLIENT_ID` (público) está configurado; si no, no aparece el botón.
  `MainActivity` en `singleTask` recibe los enlaces en `onCreate`/`onNewIntent`.
- Pantalla: formularios de entrar, registrarse y recuperar; «revisa tu correo» con
  reenvío; nueva contraseña; botón de Google. 29 textos nuevos en ES/EN/FR/DE.
- `RegistrationTest`: 10 escenarios con el servicio HTTP simulado (registro sin rol,
  contraseña débil, límite de reenvío, sin verificar, enlace de verificación, recuperación
  completa y resistente a la restauración, enlace de otro dispositivo, código usado,
  error en el enlace, enlaces ajenos).

### Dudas que solo resolverá la prueba real

- Si el **reenvío** de verificación conserva el flujo PKCE (la API de reenvío no
  envía un nuevo `code_challenge`). Si no, el enlace reenviado verificará en servidor
  y habrá que entrar con contraseña.
- Si Gmail en Android abre bien la redirección al esquema propio de la app.
- Con Credential Manager, un fallo de configuración de Google (huella o cliente) puede
  llegar como «cerrado por el usuario» y no mostrar error: revisar el registro de Android.
- Comportamiento de Supabase al entrar con Google con un correo ya registrado con
  contraseña (vinculación de identidades).

## 7. Prueba real del correo (21/11)

Registro directo contra Auth (sin app) con `marcosarov7k+registro1@gmail.com`
(cuenta de prueba en la base compartida; credenciales solo en el `.env` local):

- Alta: HTTP 200, sin sesión, `confirmation_sent_at` registrado.
- Antes de abrir el enlace: acceso rechazado con `email_not_confirmed`.
- Correo «Confirm your email address» recibido vía Brevo.
- Enlace pulsado desde el ordenador: el navegador no carga nada al final
  (redirige a `es.teloarreglo.pruebas://…` sin la app instalada), pero la cuenta
  queda verificada en servidor (`email_confirmed_at` 01:29 UTC).
- Después: acceso HTTP 200, `current_account` correcto con `is_administrator: false`,
  cierre de sesión 204.

Pendiente: carpeta en la que llega (bandeja/spam) y remitente mostrado; recuperación
y verificación con PKCE desde la app en el móvil.
