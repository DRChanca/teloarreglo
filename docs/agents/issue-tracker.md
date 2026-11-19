# Issue tracker: GitHub

Las decisiones, especificaciones y tareas viven en GitHub Issues de
`DRChanca/teloarreglo`. Usar `gh` desde este repositorio.

Antes de crear issues, comprobar el destino con
`gh repo view --json nameWithOwner`. Publicar únicamente en
`DRChanca/teloarreglo`; confirmar cualquier cambio de destino con el usuario.

## Operaciones

- Crear: `gh issue create --title "..." --body-file <archivo>`.
- Leer: `gh issue view <numero> --json number,title,body,labels,comments,assignees`.
- Listar: `gh issue list --state open --json number,title,labels,assignees`.
  Usar filtros y paginación cuando proceda.
- Comentar: `gh issue comment <numero> --body-file <archivo>`.
- Etiquetar: `gh issue edit <numero> --add-label "<etiqueta>"`.
- Cerrar: publicar primero la resolución como comentario y después ejecutar
  `gh issue close <numero>`.
- Publicar una especificación significa crear una issue.
- Obtener un ticket significa leer su cuerpo y comentarios.

Consultar `docs/agents/triage-labels.md` para clasificar el trabajo.

## Subissues y dependencias

Usar relaciones nativas de GitHub para que el mapa sea visible en su interfaz.
Comprobar las opciones disponibles en la versión instalada de `gh`.

- En versiones compatibles: `gh issue edit <padre> --add-sub-issue <hijo>`
  y `gh issue edit <hijo> --add-blocked-by <bloqueador>`.
- Alternativa mediante API: obtener el identificador numérico de base de datos
  con `gh api repos/DRChanca/teloarreglo/issues/<numero> --jq .id`.
  No confundirlo con el número de issue ni con `node_id`.
- Añadir una subissue:
  `gh api --method POST repos/DRChanca/teloarreglo/issues/<padre>/sub_issues -F sub_issue_id=<id-hijo>`.
- Añadir un bloqueo:
  `gh api --method POST repos/DRChanca/teloarreglo/issues/<hijo>/dependencies/blocked_by -F issue_id=<id-bloqueador>`.
- Consultar hijos:
  `gh api --paginate repos/DRChanca/teloarreglo/issues/<padre>/sub_issues`.
- Consultar bloqueadores:
  `gh api --paginate repos/DRChanca/teloarreglo/issues/<hijo>/dependencies/blocked_by`.
  Un ticket está desbloqueado cuando todos sus bloqueadores están cerrados.

Si las relaciones nativas no están disponibles, usar una lista de tareas
en el padre y una referencia `Part of #<padre>` en cada hijo; para bloqueos,
usar `Blocked by: #<numero>, ...` en el cuerpo. Conservar los títulos junto a
los enlaces para que sean legibles.

## Pull requests como entrada de triaje

**PRs as a request surface: no.**

## Operaciones de Wayfinder

- El mapa es una issue con etiqueta `wayfinder:map`.
- Sus decisiones son subissues con una etiqueta de tipo:
  `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling` o
  `wayfinder:task`. Crear las etiquetas necesarias al iniciar el mapa.
- Mapas y decisiones llevan únicamente etiquetas `wayfinder:*`.
- Crear primero las issues y después sus referencias y dependencias,
  utilizando identificadores reales.
- Frontera: hijos abiertos, sin asignación y sin bloqueadores abiertos.
  Consultar los hijos y sus bloqueadores, filtrar y elegir por orden de creación
  si el usuario no indica otro orden.
- Reclamar antes de trabajar: `gh issue edit <numero> --add-assignee @me`.
- Resolver mediante comentario, cerrar y añadir al mapa un enlace con el
  título de la decisión y un resumen de una línea. El detalle vive en el ticket.
- Al comunicar decisiones, referirse a ellas por su título enlazado,
  no por un número aislado.
