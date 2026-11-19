# Teloarreglo

Una app Android Kotlin/Compose para cliente y profesional. Base de identidad de
[Acceder por correo y conservar sesión](https://github.com/DRChanca/teloarreglo/issues/12).
Reglas: [especificación](docs/especificacion.md).

## Preparación

- JDK 21 (el código genera bytecode Java 17), Python 3, Android Studio/SDK.
- Android Platform 35, Build Tools 35.0.0 y un emulador API 26 o posterior.
- Gradle **8.11.1**, AGP **8.9.2**, Kotlin **2.1.21**, Compose BOM **2025.05.01**,
  supabase-kt **3.1.4**, Ktor/OkHttp **3.1.3** fijados en el repositorio.
- Supabase CLI **3.2.0** y Docker para comprobaciones de servidor locales.

```bash
bash scripts/setup-supabase.sh
```

El asistente guía la creación de **dos proyectos Free en Frankfurt** y cuentas
de prueba verificadas. No añade tarjetas, cambia planes ni guarda contraseñas
de base de datos. Comprueba personalmente plan y región en cada proyecto.
No desactives **Confirm email**. El alta administrativa de una cuenta de prueba
con confirmación explícita no equivale a tener implementado registro público.

`.env` es local, ignorado por Git y con permisos 600. Contiene las claves
**publicables** (no secretas) y las credenciales locales de las cuentas de prueba.
El asistente **no** envía valores a GitHub. No compartas el archivo ni captures
tokens/contraseñas en evidencias.

## Migraciones y entornos

El historial de `supabase/migrations/` es común. No editar migraciones ya aplicadas.
`supabase/config.toml` configura la pila **local**, no los proyectos alojados.
Verifica por separado que ambos mantienen la confirmación por correo.

Para aplicar a un proyecto alojado, autentícate en CLI fuera del repositorio:

```bash
supabase login
supabase link --project-ref REFERENCIA_DE_PRUEBAS
supabase db push --linked --dry-run
supabase db push --linked
python3 scripts/contract_identity.py --environment pruebas
```

Revisa el proyecto enlazado y el listado antes de confirmar el despliegue.
Promoción a presentación: **solo tras validar pruebas y revisar los cambios**,
volver a enlazar explícitamente la referencia de presentación y repetir
`db push --linked --dry-run`, `db push --linked` y el contrato con
`--environment presentacion`. No hay despliegue automático ni seed.
**No ejecutar `db reset` contra datos preparados**, ni borrar usuarios para
repetir la presentación. Contraseñas de base y tokens de despliegue se introducen
en CLI/gestor de credenciales, nunca en app/Git.

## Compilar y ejecutar

```bash
python3 scripts/android.py check
python3 scripts/android.py build --environment pruebas
python3 scripts/android.py build --environment presentacion
```

El script lee `.env` **sin ejecutarlo**. Solo pasa a Gradle las cuatro variables
públicas, nunca las contraseñas. También admite esas cuatro variables de entorno
o propiedades en `local.properties`. Son APKs debug para distribución directa,
con identificadores separados `es.teloarreglo.pruebas` y
`es.teloarreglo.presentacion`; no una firma de publicación comercial.
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

Abrir la app del mismo entorno en ambos y entrar con cuentas verificadas distintas.
No hay aplicación separada por rol. Este ticket solo entrega acceso, no perfiles/chat.

## Comprobaciones

```bash
supabase start
supabase test db
python3 scripts/contract_identity.py --local
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial emulator-5556
```

- SQL/pgTAP: identidad de sesión, acceso anónimo y elevación de permisos.
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
