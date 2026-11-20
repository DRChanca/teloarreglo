# Conversación de texto — #15

## Interfaz compartida

Migraciones nuevas, posteriores a portadas: `20261120000600_chat.sql` y
`20261120000700_chat_text.sql`. No modifican migraciones anteriores ni cargan,
borran o reinician datos preparados. Revisar/aplicar ambas en orden en la única
base; la prueba local no las despliega en el proyecto alojado.

- `open_conversation(p_profile_id uuid)` → conversación JSON:
  `id`, `profile_id`, `client_id`, `professional_id`, `name`, `created_at`.
  Requiere cuenta verificada y perfil publicado con propietario diferente.
  Reabrir devuelve la misma conversación para cliente/perfil.
- `send_text(p_conversation_id uuid, p_request_id uuid, p_body text)` → mensaje
  JSON: `id`, `conversation_id`, `author_id`, `request_id`, `body`, `created_at`.
  Autor derivado de Auth; hora e ID del servidor. Texto no vacío tras `btrim`,
  hasta 4000 caracteres. El mismo UUID de petición por autor/conversación
  devuelve el mismo mensaje; cambiar el texto de una petición existente falla.
- `GET conversations`: RLS filtra a los dos participantes.
- `GET chat_messages?conversation_id=eq.UUID&order=created_at.asc,id.asc`:
  RLS de participantes. Android pagina de 200 en 200 para recuperar todo el
  historial, sin confundir el límite de PostgREST con el final de la conversación.
- No se conceden INSERT/UPDATE/DELETE directos a las cuentas de la app.
  Tampoco acceso anónimo. El rol administrativo de la aplicación **no** da
  acceso general al chat. Las futuras pruebas de incidencia tendrán su propia
  frontera explícita; no añadir una excepción administrativa a estas políticas.

`Chat` es la frontera Kotlin (`open`, `conversations`, `messages`, `send`).
No exige cita, propuesta o aceptación previa. No almacena ni consulta direcciones.
`name` es el nombre público del perfil al abrir la conversación, no información
de identidad privada del cliente. La bandeja profesional distingue clientes con
un sufijo del identificador; no publica correo ni nombre privado.

## Sincronización y recuperación

La bandeja y el chat consultan el servidor cada 2,5 segundos **mientras están
visibles**. No se promete notificación push ni sincronización con la app cerrada
en esta entrega. Volver a abrir recupera el historial completo y los huecos de
reconexión; los fallos se muestran y se reintentan, conservando el historial visible.

Un mensaje aparece como confirmado solo al recibir su respuesta del servidor o
leerlo en el historial. Antes del HTTP, se guarda una salida pendiente cifrada
con Android Keystore, separada por cuenta/conversación. Navegar fuera, perder la
respuesta o cerrar la app no cambia su UUID al reintentar. Hasta resolver ese
envío no se permite editarlo ni enviar otro texto con una clave diferente.
La copia pendiente se retira tras confirmación; no se borran mensajes del servidor.
Desinstalar o borrar datos locales elimina esta protección local y no forma
parte del recorrido de reapertura.

## Comprobación reproducible

```bash
supabase test db
python3 scripts/contract_chat.py --local
python3 scripts/android.py check
python3 scripts/android.py ui-test --serial SERIAL_DESECHABLE
```

El contrato HTTP solo admite `--local`: crea cuentas/perfil aleatorios y elimina
exclusivamente sus fixtures en loopback. No escribe en la base compartida ni
acepta credenciales administrativas alojadas. CI ejecuta este contrato junto
a identidad, catálogo y portadas. Resultados y recorrido pendiente:
[comprobación](comprobacion-chat.md).
