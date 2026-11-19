# Comprobación de acceso y sesión

Ticket: [Acceder por correo y conservar sesión](https://github.com/DRChanca/teloarreglo/issues/12).
Fecha: 19 de noviembre de 2026. Rama: `feat/12-acceso-sesion`.

## Estado de aceptación

**Aceptación del ticket pendiente.** Configurado el único proyecto Supabase
alojado; acceso real a Auth comprobado desde el contrato HTTP, pero todavía no
se ha demostrado el recorrido completo desde la APK ni está disponible la RPC.
No hay planes, facturación ni compras activados por este trabajo.

**Cambio posterior de David:** una única base para desarrollo, pruebas
funcionales y presentación; él prepara los datos de la presentación.
El asistente ya pide un solo proyecto y sincroniza los alias de las dos
variantes con la misma conexión y cuenta. No crea ni exige una segunda base.
Los resultados técnicos siguientes describen lo comprobado antes de este cambio;
no constituyen una exigencia vigente de mantener dos bases.
La adaptación del asistente pasa `bash -n` y verificación estática de sus tres
etapas, biblioteca intacta y escritura de ambos alias con los mismos valores.
David ha ejecutado el recorrido interactivo y guardado la conexión real.

## Avance: conexión al proyecto alojado

- `.env` contiene los ocho campos esperados, con los mismos valores para ambos
  alias, y permisos 600. Con autorización de David se retiró únicamente una
  primera línea ajena al formato; se conservaron sus credenciales y demás líneas.
- Auth alojado: configuración consultada (HTTP 200); confirmación por correo
  activa. Acceso con la cuenta configurada (HTTP 200), correo confirmado.
- Se cerró únicamente la sesión creada para esta comprobación (HTTP 204).
  No se crearon ni borraron usuarios, ni se reiniciaron datos.
- `current_account` devuelve HTTP 404 incluso autenticado: la operación protegida
  todavía no está disponible. El contrato completo falla en su primer control,
  no se declara superado.
- No hay acceso de administración utilizable desde CLI. Falta desplegar
  `supabase/migrations/20261119000100_identity.sql` mediante acceso autorizado;
  no se ha aplicado automáticamente contra la base compartida.
  David ha elegido SQL Editor: `scripts/deploy-identity.sh` guía su revisión,
  ejecución manual transaccional y comprobación posterior. El asistente aún no
  se ha ejecutado; después habrá que registrar esa aplicación en el historial
  antes de utilizar `db push`.
- APK `pruebas` compilada con la conexión alojada, sin contraseñas incorporadas:
  `app/build/outputs/apk/pruebas/debug/app-pruebas-debug.apk`.
  SHA-256: `64df949bf26ab5a6ea5b5be562faa57815e16582d62b16b149cd03de03746a7e`.
  Sigue pendiente instalarla/comprobar el recorrido real tras desplegar la RPC.
- Plan Free y región Frankfurt: confirmación humana del asistente, no contrastada
  todavía mediante API de administración.

| Área | Estado / evidencia |
| --- | --- |
| Kotlin/Compose, minSdk 26 | Ambas APKs debug compiladas; variantes separadas de pruebas/presentación |
| Auth real y operación protegida | Contrato HTTP ejecutado contra Supabase **local**: acceso, credenciales incorrectas, RPC, renovación y logout |
| Identidad/permisos | `supabase test db supabase/tests/identity.test.sql`: 6/6, tras comprobar fallo antes de crear la RPC |
| Interfaz de acceso | `AccessTest` en Pixel 7/API 35: 2/2; acceso incorrecto y acceso/cierre, con servicio externo controlado |
| Identidad Kotlin | 6 tests: credenciales incorrectas, token renovable, token revocado, conexión, evento obsoleto y error de servidor en renovación; HTTP controlado |
| Compilación y lint | `python3 scripts/android.py check`: correcto |
| Sesión cifrada entre clientes nuevos | `PersistedIdentityTest` en API 35: 1/1, almacenamiento/Keystore reales y HTTP controlado |
| Idiomas | Recursos ES/EN/FR/DE completos; `LanguageTest` en API 35: 1/1, selección de alemán y persistencia al recrear actividad |
| Dos emuladores | Creados `Teloarreglo_Cliente_API35` y `Teloarreglo_Profesional_API35`, puertos 5556/5558; no se sobrescribió el existente API 37 |
| Supabase alojado Free/Frankfurt ×1 | Conexión configurada; Auth comprobado; plan/región confirmados manualmente, sin contraste por API |
| Acceso real desde APK y reabrir contra alojado | **Pendiente** |
| GitHub Checks | Workflow versionado; **no ejecutado en GitHub**, falta push/revisión |
| Revisión visual con David | **Pendiente**; este diseño no se declara aprobado |

La primera prueba en el emulador preexistente API 37 falló dentro de Espresso
(`InputManager.getInstance` no disponible), no en el acceso. Se conservó ese
dispositivo y se prepararon dos API 35. CI incluye API 26 y 35; la ejecución
API 26 no se ha observado localmente en Apple Silicon.

Se corrigió durante la prueba UI un error que quedaba fuera de la zona visible
con teclado abierto: al enviar se cierra el teclado y el aviso entra en pantalla.
Las pruebas no sustituyen una revisión visual y funcional humana.

Revisión contra `main`: [informes por eje y correcciones](revision-acceso.md).
Se reprodujeron y corrigieron dos defectos de la renovación de sesión antes de
la entrega. La restauración al entrar/regresar queda concentrada en la actividad.

## Batería final local

Después de las correcciones se ejecutó sin errores:

```bash
./gradlew :app:test :app:lint :app:assemblePruebasDebug :app:assemblePresentacionDebug :app:connectedPruebasDebugAndroidTest
supabase test db
python3 scripts/contract_identity.py --local
```

- Kotlin: 6 escenarios en cada una de las 4 variantes debug/release de los dos
  entornos (24 ejecuciones).
- Instrumentación: 4 pruebas en cada uno de los dos API 35 (8 ejecuciones).
- PostgreSQL: 6 comprobaciones; contrato HTTP real local: 1 recorrido completo.
- Lint: sin errores; mantiene avisos no bloqueantes, entre ellos versiones más
  recientes de dependencias y ausencia de icono propio de lanzamiento.
- `bash -n scripts/setup-supabase.sh` y análisis sintáctico de los 3 scripts Python:
  correctos. No se ejecutó el asistente interactivo ni ShellCheck (no instalado).
- APK de pruebas reinstalada y abierta correctamente en ambos emuladores tras
  los tests, sin cuenta conectada; muestra el aviso de configuración ausente.

APKs debug de esta comprobación, **sin conexión alojada configurada**:

| Archivo bajo `app/build/outputs/apk/` | SHA-256 |
| --- | --- |
| `pruebas/debug/app-pruebas-debug.apk` | `9c4469fe4ad9b0dc3f943ef645c81defcbe9ee8523bae182ce7cb2666197002b` |
| `presentacion/debug/app-presentacion-debug.apk` | `ea845754af336b933a4875b39a28a7152328d375401fdb63cfa441a40c7dbce6` |

Al recompilar con configuración alojada cambiarán sus hashes; conservar los
nuevos junto con el SHA de código utilizado. Ejecutar instrumentación solo en
emuladores desechables: Gradle puede desinstalar la app probada. Nunca usarla
para reiniciar una sesión o datos locales preparados de presentación.

## Pasos que faltan para aceptar el ticket

1. Ejecutar `bash scripts/setup-supabase.sh` para el **único proyecto**. Confirmar
   en dashboard plan Free, Frankfurt y **Confirm email** activo. No crear otro
   proyecto para presentación. El asistente solo guarda configuración local.
2. Aplicar la migración a la base compartida con revisión/dry-run, ejecutar
   `python3 scripts/contract_identity.py --environment pruebas`.
3. Compilar pruebas con la configuración, instalar en ambos emuladores y entrar
   con cuentas Auth verificadas diferentes. No registrar ni grabar contraseñas.
4. Forzar cierre (`adb -s SERIAL shell am force-stop es.teloarreglo.pruebas`),
   reabrir desde el lanzador y observar cuenta verificada tras consulta al servidor.
5. Quitar conectividad y repetir: aviso comprensible y sin acceso confirmado falso;
   restaurar conexión y reintentar. Intentar logout sin conexión: no declarar éxito.
6. Con una cuenta de prueba desechable autorizada, revocar su sesión desde Auth,
   cerrar/reabrir y pedir acceso de nuevo. No alterar las cuentas que David haya
   preparado para presentar en esa misma base.
7. Revisar ES/EN/FR/DE, selector, persistencia al reabrir, teclado, legibilidad,
   tamaño de texto y aspecto con David. Dispositivo en idioma no soportado: ES.
8. David prepara los datos de presentación en la misma base. No promover a otra
   base ni reiniciar, borrar o repoblar automáticamente los datos.
9. Ejecutar Checks en GitHub y conservar SHA, APK, firma/hash y resultados de la
   base compartida, sin secretos. Adjuntar evidencias depuradas antes de cerrar el ticket.

El contrato de logout comprueba que el refresh token deja de funcionar. Un JWT
de acceso ya emitido puede seguir siendo válido hasta caducar; no se afirma una
revocación instantánea de todos los JWT ni funcionamiento completo sin conexión.
