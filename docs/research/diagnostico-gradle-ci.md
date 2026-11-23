# Diagnóstico: Gradle en android-ui (35)

Consulta: 20-11-2026. [Run 44634627066, intento 1, job 135949838642](https://github.com/DRChanca/teloarreglo/actions/runs/44634627066/job/135949838642).

## Evidencia

- El job arrancó con `Gradle User Home cache not found. Will initialize empty` y `cache-read-only: true`. La acción de caché indica que no guardará el estado. Esto aumenta la necesidad de descargas; no demuestra por sí solo por qué fallaron. `gradle/actions/setup-gradle@v4` documenta la caché de dependencias, su modo de solo lectura por defecto fuera de la rama predeterminada y las restricciones de acceso entre ramas ([documentación v4](https://github.com/gradle/actions/blob/v4/docs/setup-gradle.md#caching-build-state-between-jobs)).
- Gradle 8.11.1 falló en la resolución de `classpath`, antes de las pruebas, con muchos `Could not find` y URLs buscadas en Google Maven, Maven Central y Gradle Plugin Portal. Entre ellos `guava:32.0.1-jre`; el POM de Kotlin Gradle Plugin también falla al resolver su BOM. La lista de URLs no establece qué respuesta HTTP devolvió cada servidor.
- El [POM de Guava en Maven Central](https://repo.maven.apache.org/maven2/com/google/guava/guava/32.0.1-jre/guava-32.0.1-jre.pom) se pudo recuperar desde este entorno durante la investigación. Esto demuestra disponibilidad ahora y desde aquí, no durante el fallo ni desde aquel runner.
- En el mismo intento inicial, `android`, `android-ui (26)` y `supabase` terminaron correctamente. Son jobs distintos: su éxito no prueba que el runner fallido tuviera idéntica red o caché. En la consulta había un reintento posterior en curso; no confundir sus IDs con el job original.

## Interpretación e incertidumbre

Un problema transitorio o específico de la ruta de red, un intermediario o una respuesta inesperada del repositorio son hipótesis compatibles con ausencias simultáneas de numerosos módulos conocidos, pero **no hay evidencia suficiente para determinar la causa raíz**. No se ha observado un 404, 429, 5xx, error DNS o TLS concreto en el log normal; tampoco puede afirmarse una caída global de Maven Central ni atribuir el fallo al emulador o a API 35.

Sonatype documenta límites de consumo y respuestas [HTTP 429, incluso por tráfico agregado en salida compartida](https://central.sonatype.org/faq/429-error/); esto es una posibilidad a comprobar, no un diagnóstico de este caso. Desaconseja intensificar reintentos ante 429 y recomienda reducir descargas mediante caché o un gestor de repositorios.

Gradle documenta [reintentos con espera creciente para ciertos errores transitorios y deshabilitación del repositorio ante determinados fallos](https://docs.gradle.org/current/userguide/graph_resolution.html#repository-disabling). Esa página describe la versión actual, no garantiza los detalles exactos de 8.11.1; no implica que se reintente cualquier `Could not find`. Evitar recomendar propiedades internas de retry sin verificar comportamiento en esa versión.

## Siguiente comprobación

Si vuelve a fallar, repetir el job con diagnóstico de Actions habilitado: [GitHub permite reejecutar un job concreto con debug](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs#re-running-a-specific-job-in-a-workflow), y [setup-gradle v4 añade `--info` y `--stacktrace`](https://github.com/gradle/actions/blob/v4/docs/setup-gradle.md#increased-logging-from-gradle-builds). Revisar con cuidado los logs antes de compartirlos: pueden contener información sensible. Buscar la primera respuesta o excepción de red y comparar la restauración de caché y las horas con los otros jobs. Si se captura un 429, seguir la guía de Sonatype; si no, investigar el código y destino efectivamente observados. Un reintento exitoso por sí solo no identifica la causa.
