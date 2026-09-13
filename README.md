# Nexo Live

**Estudio de emisión en directo para Android, libre y de código abierto.**
Escenas, fuentes, mezclador de audio y emisión a varias plataformas a la vez, al estilo OBS pero
pensado desde cero para el móvil.

[![Android CI](https://github.com/lopezdevf/nexo-live/actions/workflows/android.yml/badge.svg)](https://github.com/lopezdevf/nexo-live/actions/workflows/android.yml)
[![Licencia: GPL v2+](https://img.shields.io/badge/licencia-GPL--2.0--or--later-blue.svg)](#licencia)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/lopezdevf?label=Apoyar)](https://github.com/sponsors/lopezdevf)

> **Estado:** alfa. Todo el flujo (compositor OpenGL, captura, codificación, emisión, grabación, audio y
> control térmico) está implementado, compila y pasa sus tests, pero **aún no se ha probado en muchos
> móviles reales**. Si lo pruebas, cuéntanos cómo va en *issues*.

## Características

| Área | Qué incluye |
|------|-------------|
| **Escenas** | Escenas ilimitadas, capas con orden, bloqueo y visibilidad, modo estudio (previo + programa) con transición |
| **Fuentes** | Cámaras del móvil, cámaras USB y capturadoras HDMI (UVC), pantalla, imagen, texto y color |
| **Audio** | Mezclador con ganancia en dB, balance y silencio; micrófonos integrados, con cable, USB o Bluetooth; audio interno de juegos; monitorización por audífonos |
| **Emisión** | Twitch, YouTube, Kick, TikTok, Facebook, Instagram, X, Trovo, Restream, LinkedIn, Rumble, Steam, Vimeo, RTMP y SRT personalizados, **varias a la vez** |
| **Calor** | Control térmico que baja bitrate, fps o resolución por pasos antes de que el sistema estrangule el procesador, y recupera calidad al enfriarse |
| **Privacidad** | Claves de emisión cifradas con Android Keystore; sin analíticas ni cuentas |

### Conectar una plataforma

Pestaña **DESTINOS** → **+** → pega el enlace de conexión (`rtmp://…`, `rtmps://…` o `srt://…`) o elige
la plataforma. Nexo detecta de qué servicio es, separa servidor y clave y avisa si el servidor no
pertenece a la plataforma elegida. También puedes *compartir* el enlace con la app desde otra aplicación.

### Protección contra el sobrecalentamiento

Nexo combina el estado térmico del sistema, el margen previsto (`getThermalHeadroom`) y la temperatura
de la batería:

| Nivel | Acción automática |
|-------|-------------------|
| Leve | −10 % bitrate, vista previa a 24 fps |
| Moderado | −25 % bitrate, máximo 30 fps, vista previa a 15 fps, un solo render en modo estudio |
| Severo | −45 % bitrate, fps mínimos, resolución al 75 % |
| Crítico | −60 % bitrate, resolución al 50 %, vista previa a 5 fps |
| Emergencia | Detiene el directo y guarda la grabación antes de que el sistema cierre la app |

Sube de nivel al instante y baja de uno en uno tras 90 s más frío, para no oscilar. Todo es
configurable: suelos de fps y bitrate, límite de batería, modo *solo avisar* o desactivado.

## Compilar

Requisitos: Android Studio Quail 4 (2026.1.4) o superior, JDK 17+, SDK de Android 37.

```bash
git clone https://github.com/lopezdevf/nexo-live.git
cd nexo-live
./gradlew assembleDebug
```

Tests del motor:

```bash
./gradlew :engine:testDebugUnitTest
```

Estructura: `engine/` (modelo, salida, audio, térmica; sin UI) y `app/` (Jetpack Compose).
La arquitectura está explicada en [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md).

## Hoja de ruta

- [x] Mesa de control: escenas, capas, lienzo editable con gestos, modo estudio con fundido
- [x] Destinos: 15 plataformas, enlaces de conexión, claves cifradas, multistream, prueba de conexión
- [x] Compositor OpenGL: cámaras (incluidas externas), cámaras USB/capturadoras UVC, pantalla, imagen y texto
- [x] Codificación por hardware H.264/H.265, emisión RTMP/SRT, grabación MP4 y bitrate adaptativo
- [x] Audio por dispositivo (integrado, cable, USB, Bluetooth), audio interno, mezclador y monitorización
- [x] Control térmico real (estado del sistema, margen previsto y batería)
- [x] Ajustes completos, propiedades de cada fuente y escenas guardadas entre sesiones
- [ ] Pruebas en una variedad de móviles y ajuste de la rotación de cámaras por fabricante
- [ ] Filtros (chroma key, LUT), overlay de chat y alertas
- [ ] Inicio de sesión con cuenta por plataforma (OAuth)

## Apoyar el proyecto

Nexo Live es gratis y sin anuncios. Si te resulta útil, puedes apoyar su desarrollo desde
[GitHub Sponsors](https://github.com/sponsors/lopezdevf). También ayuda muchísimo reportar errores,
traducir o enviar mejoras: lee [CONTRIBUTING.md](CONTRIBUTING.md).

## Licencia

Nexo Live es software libre: puedes redistribuirlo y modificarlo bajo los términos de la
**GNU General Public License, versión 2 o (a tu elección) cualquier versión posterior**
(`GPL-2.0-or-later`). Consulta [LICENSE](LICENSE) (GPLv2) y [LICENSE-GPL-3.0.txt](LICENSE-GPL-3.0.txt) (GPLv3).

Las dependencias bajo Apache 2.0 (AndroidX, RootEncoder…) solo son compatibles con la GPLv3, por lo que
**los APK compilados se distribuyen bajo GPLv3**. El detalle está en [NOTICE.md](NOTICE.md).

Este programa se distribuye con la esperanza de que sea útil, pero **sin ninguna garantía**.

---

*English:* Nexo Live is a free and open-source OBS-style live streaming studio for Android: scenes,
sources, audio mixer, multistreaming to any RTMP/SRT platform and thermal protection.
Licensed under GPL-2.0-or-later.
