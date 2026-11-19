# Teloarreglo

Una app Android Kotlin/Compose para cliente y profesional. Base de identidad de
[Acceder por correo y conservar sesión](https://github.com/DRChanca/teloarreglo/issues/12).
Reglas: [especificación](docs/especificacion.md).

## Decisión vigente: una sola base

Se utiliza **un único proyecto Supabase Free en Frankfurt**, con la misma base
para desarrollo, pruebas funcionales y presentación. David prepara los datos de
la presentación en esa base. No hay una segunda base ni promoción de datos;
no se reinician, borran ni repueblan datos automáticamente.

El asistente configura **un solo proyecto**. Conserva los nombres heredados
`TEST_*` y `PRESENTATION_*` como alias de la **misma conexión y cuenta**, para
compatibilidad con las dos variantes actuales de APK; no son dos bases.
Esta decisión sustituye la separación original de los tickets y la revisión inicial.

## Preparación

- JDK 21 (el código genera bytecode Java 17), Python 3, Android Studio/SDK.
- Android Platform 35, Build Tools 35.0.0 y un emulador API 26 o posterior.
- Gradle **8.11.1**, AGP **8.9.2**, Kotlin **2.1.21**, Compose BOM **2025.05.01**,
  supabase-kt **3.1.4**, Ktor/OkHttp **3.1.3** fijados en el repositorio.
- Supabase CLI **3.2.0** y Docker para comprobaciones de servidor locales.

```bash
bash scripts/setup-supabase.sh
```

Tres pasos: abrir o crear el único proyecto, copiar URL/clave publicable y
configurar una cuenta verificada de esa misma base. Reutiliza tu proyecto y
cuenta existentes si ya los tienes. El asistente no ejecuta migraciones ni
modifica datos; antes de guardar pide confirmar la sincronización de los alias
locales, sustituyendo valores antiguos que apuntaran a otro proyecto.
Para el contrato funcional se usan `TEST_TEST_EMAIL` y `TEST_TEST_PASSWORD`;
sus alias `PRESENTATION_*` reciben los mismos valores. No compartas credenciales.
Comprueba personalmente el plan Free y la región Frankfurt.
No desactives **Confirm email**. El alta administrativa de una cuenta de prueba
con confirmación explícita no equivale a tener implementado registro público.

`.env` es local, ignorado por Git y con permisos 600. Contiene las claves
**publicables** (no secretas) y las credenciales locales de las cuentas de prueba.
El asistente **no** envía valores a GitHub. No compartas el archivo ni captures
tokens/contraseñas en evidencias.

## Migraciones en la base compartida

El historial de `supabase/migrations/` es común. No editar migraciones ya aplicadas.
`supabase/config.toml` configura la pila **local**, no el proyecto alojado.
Mantén activa la confirmación por correo.

Para aplicar a un proyecto alojado, autentícate en CLI fuera del repositorio:

```bash
supabase login
supabase link --project-ref REFERENCIA_DEL_UNICO_PROYECTO
supabase db push --remote --dry-run
supabase db push --remote
python3 scripts/contract_identity.py --environment pruebas
```

Revisa el proyecto enlazado y el listado antes de confirmar el despliegue.
No hay promoción a un proyecto de presentación: David prepara esa misma base
para mostrarla. No hay despliegue automático ni seed.
**No ejecutar `db reset` contra datos preparados**, ni borrar usuarios para
repetir la presentación. Contraseñas de base y tokens de despliegue se introducen
en CLI/gestor de credenciales, nunca en app/Git.

### Alternativa sin credenciales de administración en CLI: SQL Editor

Para aplicar **por primera vez** la migración inicial de identidad:

```bash
bash scripts/deploy-identity.sh
```

El asistente abre el único proyecto configurado e imprime en la consola el SQL
versionado envuelto en una transacción. Copia desde `begin;` hasta `commit;`
incluidos, pégalo en SQL Editor y revísalo antes de ejecutarlo. No escribe la
consulta en la página ni usa el portapapeles automáticamente.
Después comprueba el contrato completo contra la base compartida.
No aplica SQL por su cuenta, no solicita credenciales de administración y no
reinicia datos. Si el dashboard devuelve un error, detente sin repetir la migración.

**Historial de migraciones:** SQL Editor no lo actualiza. Antes de usar posteriormente
`db push`, autentícate/enlaza el mismo proyecto, revisa que la migración se aplicó
completa y registra esa versión con Supabase CLI 3.2.0:

```bash
supabase migration repair 20261119000100 --status applied --remote
supabase db push --remote --dry-run
```

No marques una versión como aplicada si la ejecución falló. `repair` registra
el historial, no vuelve a ejecutar el SQL. Solo después de comprobar el historial
se podrán desplegar migraciones posteriores; no volver a ejecutar esta por SQL Editor.

## Compilar y ejecutar

```bash
python3 scripts/android.py check
python3 scripts/android.py build --environment pruebas
```

El script lee `.env` **sin ejecutarlo**. De ese archivo solo pasa a Gradle las
cuatro variables públicas, nunca las contraseñas. También admite esas cuatro variables de entorno
o propiedades en `local.properties`. Son APKs debug para distribución directa,
con identificadores separados `es.teloarreglo.pruebas` y
`es.teloarreglo.presentacion`; no una firma de publicación comercial.
La segunda variante no es un entorno alojado diferente: con el asistente ambas
usan la misma base. Puedes utilizar `pruebas` tanto para probar como para presentar.
Sin configuración se muestra un error explícito: compilar no demuestra conexión.

APKs: `app/build/outputs/apk/{pruebas,presentacion}/debug/`.
Guardar APK y SHA del commit usados para grabar; no sustituirlas durante la sesión.

### Dos emuladores

En Device Manager crea dos dispositivos distintos API 35 (por ejemplo Pixel 7),
con la misma imagen Google APIs: `Teloarreglo_Cliente_API35` y
`Teloarreglo_Profesional_API35`. En Apple Silicon usa arm64-v8a; en Linux x86_64.
No clones ni sobrescribas datos del emulador ya preparado.

Desde **dos terminales**, con `$ANDROID_HOME` apuntando al SDK:

```bash
"$ANDROID_HOME/emulator/emulator" -avd Teloarreglo_Cliente_API35 -port 5556
```

```bash
"$ANDROID_HOME/emulator/emulator" -avd Teloarreglo_Profesional_API35 -port 5558
```

```bash
python3 scripts/android.py install --serial emulator-5556
python3 scripts/android.py install --serial emulator-5558
```

Abrir la misma variante `pruebas` en ambos, conectada a la única base, y entrar con cuentas verificadas distintas.
No hay aplicación separada por rol. Este ticket solo entrega acceso, no perfiles/chat.

## Comprobaciones

```bash
supabase start
supabase test db
python3 scripts/contract_identity.py --local
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial emulator-5556
```

**Las pruebas instrumentadas se ejecutan solo en emuladores desechables, antes
de preparar las sesiones de presentación.** Gradle puede instalar/desinstalar
la app probada y perder sus datos locales. No ejecutarlas sobre un dispositivo
con una sesión o datos locales que deban conservarse. Después de probar, instalar
de nuevo la APK con el comando `install`.

- SQL/pgTAP: identidad de sesión, acceso anónimo y elevación de permisos.
- Las pruebas funcionales contra Supabase alojado usan la base normal. La pila
  local de CI sirve para pruebas aisladas; no es otro proyecto alojado requerido.
- Contrato HTTP **real**: credenciales incorrectas, acceso protegido, recuperación
  con refresh token y cierre contra Auth. `--local` crea y elimina únicamente su
  propia cuenta desechable en loopback; nunca usa administración remota.
- Tests Kotlin: comportamiento de la interfaz de identidad contra HTTP controlado.
- Tests Compose: resultados visibles del recorrido; el servicio externo se sustituye
  solo en esos tests. **No acreditan acceso real a un proyecto alojado**.
- GitHub ejecuta compilación, tests, lint, migraciones/contratos locales y pruebas
  de interfaz API 26 y 35. Los workflows no se han ejecutado hasta hacer push.
  Las variables públicas opcionales son `TEST_SUPABASE_URL`,
  `TEST_SUPABASE_PUBLISHABLE_KEY`, `PRESENTATION_SUPABASE_URL` y
  `PRESENTATION_SUPABASE_PUBLISHABLE_KEY`. Sin ellas el artefacto no está conectado.
  No se requieren secretos privilegiados.

Resultados y comprobaciones pendientes: [evidencia del ticket](docs/comprobacion-acceso.md).

## Contrato compartido de identidad

- `Identity`: `restore`, `signIn`, `signOut`, `state` observable.
- `current_account()` (RPC sin argumentos): `{user_id, email, is_administrator}`.
  Actor derivado de Auth y correo confirmado; no admite un ID ni rol del cliente.
- `private.administrators`: lista privada gestionada por SQL de confianza.
  Ninguna ruta para autoconcederse rol. El permiso no da acceso general a chats.
  El perfil profesional opcional se integrará en su ticket; no se deduce de metadatos.
- Tokens guardados cifrados mediante Android Keystore; copia de seguridad desactivada.
  La contraseña solo se usa durante el acceso, sin persistirla.
- Recuperar no equivale a confiar en un token guardado: se renueva y se consulta
  la cuenta al servidor. Si falla la red no se muestra una sesión comprobada.
  El SDK mantiene la renovación mientras la app está abierta.
- Logout pide confirmación a Auth antes de mostrar éxito. Los JWT de Supabase ya
  emitidos pueden seguir siendo válidos hasta expirar; logout invalida renovación,
  no promete revocación instantánea de todos los access tokens.
- Recursos ES (alternativa), EN, FR y DE; idioma del dispositivo y selector
  persistido por AppCompat, también en Android anterior a 13.
