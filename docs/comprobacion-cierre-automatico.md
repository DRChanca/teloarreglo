# Comprobación del cierre automático y los recordatorios desde el servidor — #27

Fecha: 23 de noviembre de 2026.
Contrato: [citas-contrato.md](citas-contrato.md#cierre-automático-y-recordatorios-desde-el-servidor--27).

No se ha desplegado nada en la base alojada ni se ha escrito en ella. La migración `20261123012900_completion_clock.sql`
solo se ha aplicado a la pila Supabase local, en la rama `ticket/27`, que incluye el último `spec/11-demo`. No se ha
activado Cron ni se ha programado ningún trabajo en el proyecto alojado. Las entregas son simuladas.

## Ejecutado localmente

| Suite | Resultado | Qué cubre |
| --- | --- | --- |
| pgTAP `supabase/tests/appointment_clock.test.sql` | 29/29 (suite completa: 820/820 en 31 archivos) | Sin recordatorio antes de las 24 h. A las 24 h hay un solo `reminder`, sin actor y dirigido al cliente. Se convierte en aviso persistido (`event = reminder`) y no cambia la etapa ni el saldo. Repetir la ejecución no envía otro. No hay cierre antes de las 48 h. A las 48 h, una ejecución cierra cuatro citas (`{"closed":4,"blocked":1}`). La integrada queda `completed/no_answer`, con saldo `8500/0/8500` y movimientos `paid` del cliente y `delivered` del sistema. Las dos partes reciben aviso `completed`. Directa y gratuita cierran sin movimientos. Una incidencia real (`open_incident`) mantiene la cita `done` con el dinero pendiente y sin recordatorio. Una cita confirmada por el cliente conserva `client_confirmed`. Recuperación: una cita atrasada en ambos vencimientos se cierra sin recordatorio tardío. Una segunda ejecución no hace nada nuevo y nunca hay una segunda entrega. Una confirmación tardía devuelve el cierre `no_answer` ya hecho. El registro privado anota `reminded` y `closed`. Con un solo participante de prueba siguen las 48 h; con los dos, 120 s y 300 s. Una cuenta no puede apuntarse como cuenta de prueba. Ni `authenticated` ni `anon` pueden ejecutar el reloj |
| `python3 scripts/contract_clock.py --local` | 4 pruebas OK (24 s) | Auth, PostgREST y **pg_cron reales**. Comprueba que `pg_cron` está disponible y programa un trabajo cada 5 s. Las fechas se adelantan con SQL de confianza. Desde «Realizado» ninguna app llama a nada: no hay recordatorio antes de su vencimiento, el trabajo lo envía y después cierra. Comprueba también que no actúa antes de los vencimientos (`created_at ≥ reminder_due_at`, `completed_at ≥ close_due_at`) y que hay ejecuciones `succeeded` en `cron.job_run_details`. Las apps «reabiertas» leen por HTTP `completed/no_answer`, `0/8000` y una entrega. El cliente tiene avisos `done`, `reminder` y `completed`; el profesional, `completed`. Carreras: tres veces, `confirm_done` simultáneo con el reloj produce una sola entrega y respuestas coherentes. Otras tres veces, `open_incident` simultáneo con el reloj bloquea o queda como comunicación posterior al cierre, sin entrega con una incidencia bloqueante. También cubre la recuperación tras días sin ejecuciones (integrada, directa y gratuita) y comprueba que las apps no llaman al reloj ni escriben en la tabla de prueba |
| `python3 scripts/contract_clock.py --local --real` | 4 pruebas OK (350 s, 19:10–19:16 CET) | Igual que la anterior, pero **sin adelantar fechas**: hay dos cuentas de prueba, el trabajo usa la programación de producción `* * * * *` y se espera de verdad. A los 100 s no hay recordatorio. Después llega el recordatorio. 15 s antes del cierre la cita sigue `done`. Después de los 5 min queda cerrada sin respuesta |
| Contratos relacionados `--local` | OK: `completion` 2, `incidents` 3, `notices` 1, `appointments` 1, `start_cancel` 2 | `mark_done` redefinido sigue cumpliendo #25 (24 h/48 h por defecto) |
| `IncidentsTest` (unitaria) | +1 | `silentCloseAt` devuelve el vencimiento solo si la cita está `done` y sin incidencia bloqueante |
| `python3 scripts/android.py check` | OK; 196 unitarias en `pruebas` | Compilación, unitarias y lint. No hay textos nuevos: `notices_topic_reminder` y `completion_*_no_answer` ya existían (#25/#32) |

La ejecución con `--real` es una prueba corta en la pila local, sin apps instaladas. No sustituye a la prueba en el
proyecto alojado con las apps cerradas. No se ha esperado 48 h reales; las 24/48 h están comprobadas con fechas
controladas (pgTAP) y en el contrato de #25.

## No ejecutado

- No hay pruebas instrumentadas: no hay cambios de interfaz más allá de ocultar la fecha de cierre automático mientras
  hay una incidencia bloqueante. No se ha usado emulator-5554.
- No hay push real: el `reminder` entra en la cola de #32 igual que los demás eventos, pero aquí no se ha enviado
  con FCM.
- No se ha medido la puntualidad ni la disponibilidad de Cron en Supabase Free.

## Pendiente antes de dar por aceptada la issue (pasos de David)

1. Desplegar la migración en la base alojada como paso revisado.
2. Activar Cron y programar `completion-clock` cada minuto con el SQL de
   [citas-contrato.md](citas-contrato.md#programación-paso-manual-de-david-por-proyecto). Comprobar en
   `cron.job_run_details` que se ejecuta con estado `succeeded`.
3. Prueba real aislada: crear dos cuentas desechables (cliente y profesional) y añadirlas a
   `private.completion_trial_accounts`. Usar una cita integrada, comenzarla y marcarla realizada. **Cerrar las dos
   apps**. A los 2 min debe llegar el recordatorio (push si FCM está configurado). Pasados los 5 min, reabrir Android:
   la cita debe aparecer como «Finalizada sin confirmación del cliente», con lo pendiente entregado una sola vez, y los
   avisos `reminder` y `completed` deben estar en «Avisos». Guardar las filas de `private.completion_clock_log` y
   `cron.job_run_details`, sin credenciales.
4. Repetir la prueba con una incidencia abierta antes de los 5 min: no debe llegar ni el recordatorio ni el cierre, y el
   saldo debe seguir pendiente.
5. Borrar las cuentas de prueba de `private.completion_trial_accounts`. Comprobar que una cita de las cuentas
   preparadas sigue mostrando un vencimiento a 48 h.
