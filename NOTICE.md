# Avisos de licencia y software de terceros

## Nexo Live

Copyright (C) 2026 Nexo Live contributors

El código fuente de Nexo Live se publica bajo la **GNU General Public License, versión 2 o cualquier
versión posterior** (`GPL-2.0-or-later`). Cada archivo lo indica con la cabecera
`SPDX-License-Identifier: GPL-2.0-or-later`.

## Por qué los APK se distribuyen bajo GPLv3

Nexo Live enlaza con bibliotecas bajo **Apache License 2.0**. Según la Free Software Foundation, la
Apache 2.0 es compatible con la GPLv3 pero no con la GPLv2. Como nuestro código admite «cualquier versión
posterior», la obra combinada (el APK) se distribuye bajo **GPLv3**. El código propio sigue pudiendo
reutilizarse bajo GPLv2 en proyectos que no incluyan esas dependencias.

## Dependencias

| Biblioteca | Licencia | Uso |
|------------|----------|-----|
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack/androidx) | Apache 2.0 | Interfaz y ciclo de vida |
| [Kotlin y kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 | Lenguaje y concurrencia |
| [RootEncoder](https://github.com/pedroSG94/RootEncoder) | Apache 2.0 | Protocolos RTMP, RTMPS y SRT |
| [AndroidX Media3](https://github.com/androidx/media) | Apache 2.0 | Recepción y decodificación de la señal del PC |
| [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) | Apache 2.0 (incluye libuvc, BSD-3-Clause, y libusb, LGPL-2.1) | Webcams y capturadoras HDMI por USB (UVC) |

Los servidores de ingest del catálogo de plataformas se contrastaron con la lista pública de servicios de
[OBS Studio](https://github.com/obsproject/obs-studio) y la API de ingest de Twitch; son datos de conexión,
no código.

Las marcas Twitch, YouTube, Kick, TikTok, Facebook, Instagram, X y demás pertenecen a sus propietarios.
Nexo Live no está afiliado a ninguna de ellas y usa monogramas propios en lugar de sus logotipos.
