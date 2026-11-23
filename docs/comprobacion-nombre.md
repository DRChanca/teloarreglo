# Comprobación del nombre de la persona — #40

Fecha: 23 de noviembre de 2026. No se ha desplegado ni escrito en la base alojada.

## Ejecutado localmente

Todo se ejecutó contra la pila Supabase local compartida, con `spec11-notas/db-check.sh`.

- pgTAP `supabase/tests/account_names.test.sql`: 19 comprobaciones.
  - El nombre de registro y el de Google se guardan sin preguntar.
  - Una cuenta sin nombre recibe `null`, no su correo.
  - Se rechazan los nombres vacíos o de más de 80 caracteres.
  - Solo el propietario escribe, y siempre mediante RPC. Otra cuenta no ve la fila.
  - El profesional ve el nombre cambiado en `conversation_inbox()` y ningún correo.
  - Los metadatos nuevos de Google no sobrescriben un nombre elegido.
- Suite pgTAP completa: 18 ficheros y 378 comprobaciones superadas.
- `scripts/contract_account_names.py --local`: pasa contra Auth y PostgREST reales.
  - Un registro público con `data.full_name` deja el nombre guardado por el trigger.
  - Una cuenta con metadatos `name`, como las de Google, también lo guarda.
  - Escribir de forma anónima o directamente en la tabla da 401/403.
  - La bandeja muestra el nombre cambiado sin el correo.
- `scripts/contract_chat_unread.py --local` (#39) sigue pasando.
- Pruebas unitarias:
  - `AccountNameTest`: 8 pruebas, con HTTP simulado y sesión falsa.
  - `RegistrationTest`: 12 pruebas, dos de ellas nuevas. Comprueban que se envía `full_name`
    recortado y que sin nombre válido no se llama a Auth.
  - Total: 142 pruebas en 21 clases.
- `python3 scripts/android.py check`: compilación, pruebas unitarias y lint correctos.
- `AccountNameUiTest` (Compose, 3 pruebas) **compila pero no se ha ejecutado**: no había un
  emulador desechable asignado.

```bash
/Users/david/Desktop/copiaMovil/spec11-notas/db-check.sh <worktree> bash -c 'cd scripts && python3 contract_account_names.py --local'
./gradlew :app:testPruebasDebugUnitTest --tests 'es.teloarreglo.AccountNameTest' --tests 'es.teloarreglo.RegistrationTest'
python3 scripts/android.py check
```

## Pendiente antes de dar por aceptada la issue

1. Ejecutar `AccountNameUiTest` en un emulador desechable.
2. Desplegar la migración en la base alojada. Lo hace David, en un paso revisado.
3. Con dos emuladores, crear una cuenta con correo. Sin nombre no se crea. Tras verificar, el
   profesional ve ese nombre en la bandeja.
4. Entrar con Google: no se pregunta el nombre y la bandeja muestra el de Google.
5. Entrar con una cuenta de correo anterior sin nombre: aparece «¿Cómo te llamas?» antes de la
   dirección de casa. Si se corta la red, la app no se bloquea.
6. Cambiar el nombre desde «Cuenta» y comprobar que lo ve el profesional.
7. Revisar los textos en ES, EN, FR y DE.
