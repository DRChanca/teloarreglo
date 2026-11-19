# Documentación de dominio

## Antes de explorar el dominio

- Leer `GLOSSARY.md` en la raíz si existe.
- Leer las decisiones de `docs/adr/` relacionadas con el área de trabajo.
- Si no existen, continuar sin bloquearse. Se crean progresivamente con
  `/domain-modeling` cuando se resuelven términos o decisiones.

## Organización

El proyecto utiliza un contexto único:

- `GLOSSARY.md`: vocabulario de dominio.
- `docs/adr/`: decisiones de arquitectura.

Usar los términos del glosario en código, documentación, pruebas e issues.
Si falta un concepto necesario, señalarlo para `/domain-modeling`.

Cuando una propuesta contradiga una decisión registrada, indicar la decisión
afectada y discutir explícitamente su sustitución antes de actuar.
