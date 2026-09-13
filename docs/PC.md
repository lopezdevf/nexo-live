# Jugar en el PC y emitir desde el móvil (sin capturadora)

Nexo Live puede recibir la pantalla y el sonido de tu PC y mezclarlos con la cámara, los textos y el
micrófono del móvil. El móvil hace el directo; el PC solo juega y envía la señal.

```
PC (juego + OBS) ──WiFi o cable USB──▶ móvil (Nexo Live: escenas + emisión) ──▶ Twitch, YouTube…
```

## 1. En el móvil

1. Fuentes → **+** → **PC (OBS por WiFi o USB)**.
2. Abre sus propiedades: verás la dirección que tienes que poner en OBS, por ejemplo
   `tcp://192.168.1.23:9000`.

Elige cómo se conectan:

| Conexión | Cómo | Retardo |
|----------|------|---------|
| **Cable USB** (recomendado) | En el móvil: Ajustes → Conexiones → Zona WiFi y anclaje → **Anclaje USB**. Usa la dirección «Cable USB». | El más bajo y sin cortes |
| **WiFi** | PC y móvil en la misma red. Mejor si el PC va por cable al router. | Bajo |
| **Zona WiFi del móvil** | Conecta el PC a la zona WiFi del móvil. | Medio (el móvil también emite por datos) |

## 2. En OBS (una sola vez)

1. **Ajustes → Salida → Modo de salida: Avanzado.**
2. Pestaña **Grabación** → **Tipo: Salida personalizada (FFmpeg)**.
3. **Tipo de salida FFmpeg: Enviar a URL**. URL: la dirección que muestra el móvil.
4. **Formato del contenedor: mpegts**.
5. **Codificador de vídeo:** el de tu gráfica (NVENC, AMF o QuickSync) H.264, **8000–12000 kbps**,
   intervalo de fotogramas clave **1 s** (en la configuración del codificador: `keyint=60` a 60 fps).
6. **Codificador de audio: AAC, 160 kbps.**
7. Aceptar.

Para empezar: con la fuente PC ya añadida en Nexo, pulsa **Iniciar grabación** en OBS. La «grabación» no
guarda nada en el PC; solo envía la señal al móvil. En la vista previa de Nexo el aviso «Esperando al PC»
se cambia por la imagen del juego.

## Sin OBS: ffmpeg

```bash
ffmpeg -f gdigrab -framerate 60 -i desktop -f dshow -i audio="Mezcla estéreo" -c:v h264_nvenc -b:v 10M -g 60 -c:a aac -b:a 160k -f mpegts tcp://IP_DEL_MOVIL:9000
```

## Consejos

- Emite desde el móvil a 1080p30 o 720p60: el móvil decodifica el PC y vuelve a codificar, y así se calienta menos.
- El audio del PC entra en el mezclador como un canal más: puedes bajarlo, silenciarlo o monitorizarlo.
- Si paras la grabación en OBS y la vuelves a iniciar, Nexo se reconecta solo; no hace falta tocar el móvil.
- En OBS, añade **Captura de juego** o **Captura de pantalla** a la escena: es lo que llega al móvil.
- Si OBS dice «No se pudo abrir la URL» o «error no especificado durante la grabación», comprueba que la fuente PC está añadida en Nexo y que la
  dirección y el puerto coinciden.
