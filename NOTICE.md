# Avisos de licencia y software de terceros

## Senda Studio

Copyright (C) 2026 Senda Studio contributors

El código fuente de Senda Studio se publica bajo la **GNU General Public License, versión 2 o cualquier
versión posterior** (`GPL-2.0-or-later`). Cada archivo lo indica con la cabecera
`SPDX-License-Identifier: GPL-2.0-or-later`.

## Por qué los APK se distribuyen bajo GPLv3

Senda Studio enlaza con bibliotecas bajo **Apache License 2.0**. Según la Free Software Foundation, la
Apache 2.0 es compatible con la GPLv3 pero no con la GPLv2. Como nuestro código admite «cualquier versión
posterior», la obra combinada (el APK) se distribuye bajo **GPLv3**. El código propio sigue pudiendo
reutilizarse bajo GPLv2 en proyectos que no incluyan esas dependencias.

## Dependencias

| Biblioteca | Licencia | Uso |
|------------|----------|-----|
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack/androidx) | Apache 2.0 | Interfaz y ciclo de vida |
| [Kotlin y kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 | Lenguaje y concurrencia |
| [RootEncoder](https://github.com/pedroSG94/RootEncoder) | Apache 2.0 | Protocolos RTMP, RTMPS y SRT |
| [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) | Apache 2.0 (incluye libuvc, BSD-3-Clause, y libusb, LGPL-2.1) | Webcams y capturadoras HDMI por USB (UVC) |

### Senda Studio PC

La aplicación de Windows (`pc/`) no tiene dependencias de terceros: solo usa el SDK de Windows
(Windows.Graphics.Capture, Direct3D 11, Media Foundation, WASAPI y GDI+). Se distribuye bajo
**GPL-2.0-or-later**.

Los servidores de ingest del catálogo de plataformas son datos públicos de conexión de cada servicio
(incluida la API de ingest de Twitch), no código.

Las marcas Twitch, YouTube, Kick, TikTok, Facebook, Instagram, X y demás pertenecen a sus propietarios.
Senda Studio no está afiliado a ninguna de ellas y usa monogramas propios en lugar de sus logotipos.
