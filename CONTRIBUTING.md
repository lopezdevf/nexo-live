# Cómo contribuir a Sirga Studio

¡Gracias por querer ayudar! Estas pautas mantienen el proyecto coherente.

## Antes de empezar

- Para errores, abre un *issue* con modelo de móvil, versión de Android, pasos para reproducirlo y, si
  puedes, el registro de `adb logcat`.
- Para funciones grandes, abre primero un *issue* para comentar el enfoque.

## Código

- Kotlin con el estilo oficial (`kotlin.code.style=official`).
- `engine/` no depende de Compose: la lógica que se pueda probar sin Android va en clases puras con tests
  (mira `ConnectionLink`, `ThermalGovernor` o `AudioMixer`).
- La interfaz está en español; los textos visibles deben ser claros para alguien sin conocimientos técnicos.
- Todo archivo nuevo lleva la cabecera:

  ```kotlin
  // SPDX-License-Identifier: GPL-2.0-or-later
  // Copyright (C) 2026 Sirga Studio contributors
  ```

- Nada de analíticas, rastreadores ni dependencias propietarias.
- Las claves de emisión nunca se registran en logs ni se muestran en mensajes de error.

## Antes de enviar un pull request

```bash
./gradlew :engine:testDebugUnitTest lint assembleDebug
```

La integración continua ejecuta lo mismo en cada pull request.

## Licencia de las contribuciones

Al enviar una contribución aceptas publicarla bajo `GPL-2.0-or-later`, la misma licencia del proyecto.

El nombre, el isotipo y el logotipo no forman parte de esa licencia (consulta [TRADEMARKS.md](TRADEMARKS.md)):
no se aceptan cambios en los archivos de la marca salvo que los pida el titular.
