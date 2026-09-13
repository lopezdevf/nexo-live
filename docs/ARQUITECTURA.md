# Nexo Live — Arquitectura

Estudio de emisión móvil al estilo OBS: escenas compuestas por fuentes, mezclador de audio,
emisión RTMP/SRT y grabación local. Android primero, en Kotlin.

## Módulos

| Módulo    | Contenido |
|-----------|-----------|
| `:engine` | Modelo del estudio, controlador de estado, compositor GL, codificadores, salidas y servicio en primer plano. Sin Compose. |
| `:app`    | UI en Jetpack Compose (mesa de control), tema Nexo, ViewModels. |

## Flujo de datos

```mermaid
flowchart LR
    subgraph Fuentes de vídeo
        CAM[Cámara<br/>CameraX] --> TEX
        SCR[Pantalla<br/>MediaProjection] --> TEX
        IMG[Imagen / Texto / Color] --> TEX
    end
    TEX[Texturas GL] --> COMP[Compositor EGL<br/>FBO al tamaño del lienzo]
    COMP --> PREV[Vista previa<br/>SurfaceView]
    COMP --> VENC[MediaCodec H.264/H.265]

    subgraph Fuentes de audio
        MIC[Micrófono] --> MIX
        INT[Audio interno<br/>AudioPlaybackCapture] --> MIX
    end
    MIX[Mezclador PCM<br/>ganancia · mute · vúmetros] --> AENC[MediaCodec AAC]

    VENC --> RTMP[RTMP/RTMPS<br/>RootEncoder]
    VENC --> SRT[SRT<br/>RootEncoder]
    VENC --> MP4[MediaMuxer MP4]
    AENC --> RTMP
    AENC --> SRT
    AENC --> MP4
```

`StudioController` es la única fuente de verdad (`StateFlow<StudioState>`). La UI llama a sus
operaciones y el compositor y las salidas observan el mismo estado, así que editar la escena
en pantalla cambia lo que sale al aire sin capas intermedias.

## Decisiones

- **Coordenadas normalizadas (0..1)** en `Transform`: una escena funciona igual en 720p, 1080p o vertical.
- **Fuentes globales** referenciadas por `SceneItem`: la misma cámara en varias escenas no se abre dos veces.
- **Modo estudio**: previo editable + programa al aire, con transición entre ambos.
- **minSdk 29**: el audio interno (AudioPlaybackCapture) está disponible siempre.
- **Compositor propio + RootEncoder solo para protocolos**: controlamos el render de escenas y
  delegamos RTMP/SRT a una librería probada (Apache 2.0).
- **Claves de retransmisión fuera del modelo**: `StreamDestination` no contiene la clave; `KeyVault`
  la cifra con AES-GCM y una llave de Android Keystore.

## Destinos y multistream

Todas las plataformas se conectan por **enlace de conexión** (servidor + clave RTMP/RTMPS/SRT). Es el
único método universal: no depende de APIs ni de aprobaciones de cada plataforma, y sirve para
cualquier servicio compatible aunque no esté en el catálogo.

```mermaid
flowchart LR
    PASTE[Enlace pegado<br/>o compartido con la app] --> LINK[ConnectionLink.parse<br/>detecta plataforma · separa clave]
    LINK --> EDIT[Editor de destino<br/>aviso si el servidor no es de la plataforma]
    EDIT --> REPO[DestinationRepository<br/>datos públicos en preferencias]
    EDIT --> VAULT[KeyVault<br/>clave cifrada · Keystore]
    REPO --> MS[MultiStreamer]
    VAULT -->|solo al conectar| MS
    ENC[Codificadores<br/>una sola vez] --> MS
    MS --> S1[RtmpClient · Twitch]
    MS --> S2[RtmpClient · YouTube]
    MS --> S3[SrtClient · servidor propio]
```

- **`PlatformCatalog`**: 15 destinos (Twitch, YouTube, Kick, TikTok, Facebook, Instagram, X, Trovo,
  Restream, LinkedIn, Rumble, Steam, Vimeo, RTMP y SRT personalizados). Servidores y límites de bitrate
  contrastados con la lista de servicios de OBS y la API de ingest de Twitch.
- **`ConnectionLink`**: RootEncoder toma los dos primeros segmentos de la ruta como «app», así que la
  clave va siempre al final y el servidor no admite «?». Los servidores de Amazon IVS (Twitch, Kick)
  se normalizan a `/app`.
- **`MultiStreamer`**: se codifica una vez y cada destino recibe su copia (`ByteBuffer.duplicate`) por su
  propia conexión, con reintentos independientes (2 s, 4 s, 8 s…). Basta un destino al aire para que
  la franja muestre «EN DIRECTO». «Probar conexión» conecta y desconecta sin enviar vídeo y nunca cuenta
  como emisión.
- **Subida**: el ancho de banda necesario es la suma de todos los destinos activos; la pestaña lo muestra.
- **Enlaces entrantes**: la app abre `rtmp://`, `rtmps://` y `srt://` y acepta texto compartido, pero
  solo rellena el editor; nunca guarda ni emite sin confirmación.

**Inicio de sesión con cuenta (OAuth)**, para crear el directo y obtener la clave automáticamente, es un
añadido posterior. Exige registrar una app de desarrollador en cada plataforma y pasar sus revisiones
(YouTube Data API, Twitch, Meta), y TikTok e Instagram no ofrecen esa vía a terceros de forma general.

## Hoja de ruta

1. **Esqueleto** ✅ — proyecto Gradle, modelo del estudio con tests y mesa de control en Compose
   (lienzo editable con gestos e imanes, escenas, capas, mezclador y modo estudio).
2. **Destinos** ✅ — catálogo de plataformas, enlaces de conexión, claves cifradas, multistream y prueba de conexión.
3. **Compositor GL** — hilo de render EGL, fuentes de cámara, color, texto e imagen, y vista previa real bajo la capa de edición.
4. **Salida** — codificadores MediaCodec conectados a `MultiStreamer`, grabación MP4, servicio en primer plano,
   bitrate adaptativo y estadísticas reales.
5. **Gaming** — captura de pantalla, audio interno y vúmetros reales en el mezclador.
6. **Persistencia** — colecciones de escenas y perfiles de salida.
7. **Extras** — inicio de sesión OAuth por plataforma, transiciones animadas, chroma key/LUT y overlay de chat.
