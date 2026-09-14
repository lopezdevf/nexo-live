# Jugar en el PC y emitir desde el móvil (sin capturadora)

Senda Studio recibe la pantalla y el sonido de tu ordenador y los mezcla con la cámara, los textos y el
micrófono del móvil. El móvil hace el directo; el PC solo juega y envía la señal con **Senda Studio PC**,
una aplicación propia para Windows.

```
PC (juego + Senda Studio PC) ──WiFi o cable USB──▶ móvil (Senda Studio: escenas + emisión) ──▶ Twitch, YouTube…
```

## Cómo se usa

1. **En el móvil:** Fuentes → **+** → **PC (Senda Studio PC)**. En sus propiedades verás un **código de 4 cifras**.
2. **En el PC:** descarga `SendaStudioPC.exe` desde la [última versión](https://github.com/lopezdevf/senda-studio/releases/latest)
   y ábrelo (no hace falta instalar nada).
3. El móvil aparece solo en la lista si está en la misma red. Elígelo, escribe el código, elige la pantalla
   y la calidad, y pulsa **Conectar**.

La fuente PC del móvil muestra el escritorio o el juego al momento, y el sonido del PC entra en el
mezclador como un canal más (se puede bajar, silenciar o monitorizar).

Si el móvil se desconecta (sales de la app, se corta la WiFi…), Senda Studio PC lo vuelve a intentar cada
dos segundos. Abrir las propiedades o cambiar de escena en el móvil no corta la conexión.

## Retraso

Todo está pensado para el menor retraso posible: codificación H.264 por hardware (Intel Quick Sync,
NVIDIA NVENC o AMD AMF) en modo de baja latencia y sin fotogramas B, envío inmediato de cada fotograma,
decodificación por hardware en el móvil sin búfer de espera y, si la red se atasca, se salta al
fotograma más reciente en lugar de acumular retraso.

Senda Studio PC mide el retraso real: el móvil devuelve la marca de cada fotograma que muestra y el PC
calcula cuánto pasó desde la captura. La cifra que ves en las dos apps incluye esa vuelta por la red,
así que el retraso real es algo menor.

| Medido en | Resultado |
|-----------|-----------|
| Portátil Intel Core Ultra 5 (Quick Sync) → Galaxy S25 Ultra por WiFi, 1080p a 60 fps | 9–34 ms, media de unos 28 ms; 9 % de un núcleo en el PC |

Para menos retraso: conecta el móvil al PC por cable y activa en el móvil **Ajustes → Conexiones →
Zona WiFi y anclaje → Anclaje USB**, o conecta el PC al router por cable.

| Conexión | Cómo |
|----------|------|
| **WiFi** | PC y móvil en la misma red. |
| **Cable USB** | Anclaje USB en el móvil; el PC lo ve como una red nueva y el móvil aparece en la lista. |
| **Zona WiFi del móvil** | Conecta el PC a la zona WiFi del móvil (el móvil también emite por datos). |

## Si el móvil no aparece en la lista

- Comprueba que Senda Studio está abierto y que la escena con la fuente PC está en pantalla.
- Algunas redes (de empresa, hoteles, invitados) bloquean la búsqueda entre dispositivos: escribe la
  dirección que muestra la fuente PC en **Dirección** (por ejemplo `192.168.1.10:9000`).
- Si Windows pregunta por el cortafuegos al abrir Senda Studio PC, permite el acceso en redes privadas.

## Calidades

| Calidad | Resolución | Bitrate |
|---------|------------|---------|
| Máxima | hasta 1080p a 60 fps | 16 Mbps |
| Alta | hasta 1080p a 30 fps | 10 Mbps |
| Fluida | hasta 720p a 60 fps | 8 Mbps |
| Ligera | hasta 720p a 30 fps | 4 Mbps (WiFi lenta) |

La imagen conserva la proporción de tu pantalla (un monitor 16:10 se envía a 1728×1080). El móvil vuelve a
codificar para la plataforma: emite a 1080p30 o 720p60 si quieres que se caliente menos.

## Compilar Senda Studio PC

Requisitos: Windows 10 (1903) u 11, Visual Studio o Build Tools con «Desarrollo para el escritorio con C++».
No tiene dependencias: solo el SDK de Windows (Windows.Graphics.Capture, Media Foundation y WASAPI).

```bat
pc\build.cmd
```

El resultado es `pc\build\SendaStudioPC.exe`, un único archivo de unos 370 KB.

## Protocolo Senda Link

Documentado en [`engine/.../pclink/SendaLink.kt`](../engine/src/main/java/com/senda/studio/engine/pclink/SendaLink.kt):
TCP con enteros big-endian, saludo con código de emparejamiento, vídeo H.264 Annex-B, audio PCM de 16
bits y búsqueda por difusión UDP en el puerto 9750. [`pc/tools/senda_link_sender.py`](../pc/tools/senda_link_sender.py)
es un emisor de prueba en Python que habla el mismo protocolo sin capturar la pantalla.
