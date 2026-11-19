# Comprobación de acceso y sesión

Ticket: [Acceder por correo y conservar sesión](https://github.com/DRChanca/teloarreglo/issues/12).
Fecha: 19 de noviembre de 2026. Rama: `feat/12-acceso-sesion`.

## Estado de aceptación

**Implementación local; aceptación del ticket pendiente.** No se han configurado
todavía los dos proyectos Supabase alojados ni demostrado su acceso desde la APK.
No hay planes, facturación ni compras activados por este trabajo.

| Área | Estado / evidencia |
| --- | --- |
| Kotlin/Compose, minSdk 26 | Compilación de pruebas comprobada; variantes separadas de pruebas/presentación |
| Auth real y operación protegida | Contrato HTTP ejecutado contra Supabase **local**: acceso, credenciales incorrectas, RPC, renovación y logout |
| Identidad/permisos | `supabase test db supabase/tests/identity.test.sql`: 6/6, tras comprobar fallo antes de crear la RPC |
| Interfaz de acceso | `AccessTest` en Pixel 7/API 35: 2/2; acceso incorrecto y acceso/cierre, con servicio externo controlado |
| Identidad Kotlin | Tests de credenciales incorrectas, token renovable, token revocado y fallo de conexión; HTTP controlado |
| Compilación y lint | `python3 scripts/android.py check`: correcto |
| Sesión cifrada entre clientes nuevos | `PersistedIdentityTest` en API 35: 1/1, almacenamiento/Keystore reales y HTTP controlado |
| Idiomas | Recursos ES/EN/FR/DE completos; `LanguageTest` en API 35: 1/1, selección de alemán y persistencia al recrear actividad |
| Dos emuladores | Creados `Teloarreglo_Cliente_API35` y `Teloarreglo_Profesional_API35`, puertos 5556/5558; no se sobrescribió el existente API 37 |
| Supabase alojado Free/Frankfurt ×2 | **Pendiente del asistente humano** |
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

## Pasos que faltan para aceptar el ticket

1. Ejecutar `bash scripts/setup-supabase.sh`; confirmar en dashboard región y
   plan de cada proyecto, que son distintos y que **Confirm email** sigue activo.
2. Aplicar la migración a pruebas con revisión/dry-run, ejecutar
   `python3 scripts/contract_identity.py --environment pruebas`.
3. Compilar pruebas con la configuración, instalar en ambos emuladores y entrar
   con cuentas Auth verificadas diferentes. No registrar ni grabar contraseñas.
4. Forzar cierre (`adb -s SERIAL shell am force-stop es.teloarreglo.pruebas`),
   reabrir desde el lanzador y observar cuenta verificada tras consulta al servidor.
5. Quitar conectividad y repetir: aviso comprensible y sin acceso confirmado falso;
   restaurar conexión y reintentar. Intentar logout sin conexión: no declarar éxito.
6. Con una cuenta de prueba desechable, revocar su sesión desde Auth, cerrar/reabrir:
   pedir acceso de nuevo. No revocar cuentas de presentación preparadas.
7. Revisar ES/EN/FR/DE, selector, persistencia al reabrir, teclado, legibilidad,
   tamaño de texto y aspecto con David. Dispositivo en idioma no soportado: ES.
8. Tras validar pruebas, promover explícitamente a presentación; repetir contrato
   y recorrido con la APK de presentación. No reiniciar ni repoblar datos.
9. Ejecutar Checks en GitHub y conservar SHA, APK, firma/hash y resultados de ambos
   entornos, sin secretos. Adjuntar evidencias depuradas antes de cerrar el ticket.

El contrato de logout comprueba que el refresh token deja de funcionar. Un JWT
de acceso ya emitido puede seguir siendo válido hasta caducar; no se afirma una
revocación instantánea de todos los JWT ni funcionamiento completo sin conexión.
