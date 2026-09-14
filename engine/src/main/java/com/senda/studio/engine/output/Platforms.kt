// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

package com.senda.studio.engine.output

enum class StreamProtocol { Rtmp, Srt }

data class IngestServer(val label: String, val url: String)

/**
 * Plataforma de destino. Todas se conectan por enlace (servidor + clave), así que
 * cualquier servicio compatible con RTMP/RTMPS o SRT funciona aunque no esté aquí.
 *
 * Servidores y límites según los datos públicos de ingest de cada plataforma y la API
 * de ingest de Twitch (septiembre 2026).
 */
class PlatformInfo(
    val id: String,
    val name: String,
    val monogram: String,
    /** Vacío: la plataforma asigna un servidor por cuenta o por directo y el usuario lo pega. */
    val servers: List<IngestServer> = emptyList(),
    val serverHint: String = "rtmp://servidor/app",
    val keyHelp: String,
    val keyLink: String? = null,
    val maxVideoKbps: Int? = null,
    val maxAudioKbps: Int? = null,
    val keyframeSec: Int = 2,
    val supportsHevc: Boolean = false,
    /** Plataformas que se ven en vertical (9:16). */
    val verticalFirst: Boolean = false,
    val protocol: StreamProtocol = StreamProtocol.Rtmp,
    private val hostPattern: Regex? = null,
) {
    val isCustom: Boolean get() = hostPattern == null
    val needsUserServer: Boolean get() = servers.isEmpty()

    fun matchesHost(host: String): Boolean = hostPattern?.matches(host.lowercase()) ?: false
}

object PlatformCatalog {

    val Twitch = PlatformInfo(
        id = "twitch", name = "Twitch", monogram = "TW",
        servers = listOf(IngestServer("Automático (el más cercano)", "rtmp://ingest.global-contribute.live-video.net/app")),
        keyHelp = "Panel de creador → Configuración → Transmisión → Clave de transmisión principal.",
        keyLink = "https://dashboard.twitch.tv/settings/stream",
        maxVideoKbps = 6_000, maxAudioKbps = 320,
        hostPattern = Regex("""(.+\.)?twitch\.tv|ingest\.global-contribute\.live-video\.net|[a-z]{3}\d{2}\.contribute\.live-video\.net"""),
    )

    val YouTube = PlatformInfo(
        id = "youtube", name = "YouTube", monogram = "YT",
        servers = listOf(
            IngestServer("RTMPS (cifrado, recomendado)", "rtmps://a.rtmps.youtube.com:443/live2"),
            IngestServer("RTMP", "rtmp://a.rtmp.youtube.com/live2"),
        ),
        keyHelp = "YouTube Studio → Emitir en directo → Emisión → Clave de emisión.",
        keyLink = "https://www.youtube.com/live_dashboard",
        maxVideoKbps = 51_000, maxAudioKbps = 160,
        supportsHevc = true,
        hostPattern = Regex("""(.+\.)?youtube\.com"""),
    )

    val Facebook = PlatformInfo(
        id = "facebook", name = "Facebook", monogram = "FB",
        servers = listOf(IngestServer("RTMPS", "rtmps://rtmp-api.facebook.com:443/rtmp/")),
        keyHelp = "Live Producer → Software de streaming → Clave de transmisión.",
        keyLink = "https://www.facebook.com/live/producer",
        maxVideoKbps = 9_000, maxAudioKbps = 128,
        hostPattern = Regex("""(.+\.)?facebook\.com"""),
    )

    val Kick = PlatformInfo(
        id = "kick", name = "Kick", monogram = "KK",
        serverHint = "rtmps://xxxxxxxx.global-contribute.live-video.net",
        keyHelp = "En el panel de creador de Kick copia la «URL del stream» y la «Clave del stream». El servidor es propio de tu canal.",
        hostPattern = Regex("""[0-9a-f]{12}\.global-contribute\.live-video\.net"""),
    )

    val TikTok = PlatformInfo(
        id = "tiktok", name = "TikTok", monogram = "TT",
        serverHint = "rtmp://push-rtmp-….tiktokcdn.com/game/",
        keyHelp = "Solo las cuentas con acceso a LIVE para software externo reciben servidor y clave (LIVE Center). Suelen cambiar en cada directo.",
        verticalFirst = true,
        hostPattern = Regex("""(.+\.)?tiktokcdn(-\w+)?\.com|(.+\.)?tiktok\.com"""),
    )

    val Instagram = PlatformInfo(
        id = "instagram", name = "Instagram", monogram = "IG",
        serverHint = "rtmps://edgetee-upload-….fbcdn.net:443/rtmp/",
        keyHelp = "Desde instagram.com: Crear → Directo → configura el software de streaming. La clave suele cambiar en cada directo.",
        verticalFirst = true,
        hostPattern = Regex("""(.+\.)?fbcdn\.net"""),
    )

    val X = PlatformInfo(
        id = "x", name = "X", monogram = "X",
        servers = listOf(
            IngestServer("EE. UU. Este", "rtmp://va.pscp.tv:80/x"),
            IngestServer("EE. UU. Oeste", "rtmp://ca.pscp.tv:80/x"),
            IngestServer("Sudamérica (Brasil)", "rtmp://br.pscp.tv:80/x"),
        ),
        keyHelp = "Media Studio → Producer → Fuentes: crea una fuente y copia su clave.",
        keyLink = "https://studio.twitter.com/producer/sources",
        maxVideoKbps = 12_000, maxAudioKbps = 128, keyframeSec = 3,
        hostPattern = Regex("""(.+\.)?pscp\.tv"""),
    )

    val Trovo = PlatformInfo(
        id = "trovo", name = "Trovo", monogram = "TR",
        servers = listOf(IngestServer("Global", "rtmp://livepush.trovo.live/live/")),
        keyHelp = "Trovo Studio → Mi canal → Stream → Clave.",
        keyLink = "https://studio.trovo.live/mychannel/stream",
        maxVideoKbps = 9_000, maxAudioKbps = 160,
        hostPattern = Regex("""(.+\.)?trovo\.live"""),
    )

    val Steam = PlatformInfo(
        id = "steam", name = "Steam", monogram = "ST",
        servers = listOf(IngestServer("Global", "rtmp://ingest-rtmp.broadcast.steamcontent.com/app")),
        keyHelp = "Configuración de retransmisión de Steam → clave RTMP.",
        hostPattern = Regex("""(.+\.)?steamcontent\.com"""),
    )

    val Vimeo = PlatformInfo(
        id = "vimeo", name = "Vimeo", monogram = "VM",
        servers = listOf(IngestServer("Global", "rtmp://rtmp.cloud.vimeo.com/live")),
        keyHelp = "Evento en directo → Configuración de transmisión → Clave.",
        hostPattern = Regex("""(.+\.)?vimeo\.com"""),
    )

    val Restream = PlatformInfo(
        id = "restream", name = "Restream", monogram = "RS",
        servers = listOf(IngestServer("Automático", "rtmp://live.restream.io/live")),
        keyHelp = "Restream reenvía a decenas de plataformas desde una sola conexión: Configuración → Streaming.",
        keyLink = "https://restream.io/settings/streaming-setup",
        hostPattern = Regex("""(.+\.)?restream\.io"""),
    )

    val LinkedIn = PlatformInfo(
        id = "linkedin", name = "LinkedIn", monogram = "IN",
        serverHint = "rtmps://….channel.media.azure.net:2935/live/…",
        keyHelp = "Crea un evento en directo con «herramienta de streaming externa» y copia URL y clave del evento.",
        hostPattern = Regex("""(.+\.)?media\.azure\.net"""),
    )

    val Rumble = PlatformInfo(
        id = "rumble", name = "Rumble", monogram = "RU",
        keyHelp = "Rumble Studio → Live → copia la URL del stream y la clave.",
        hostPattern = Regex("""(.+\.)?rumble\.com"""),
    )

    val CustomRtmp = PlatformInfo(
        id = "rtmp", name = "RTMP personalizado", monogram = "RT",
        keyHelp = "Cualquier servidor RTMP o RTMPS: Owncast, nginx-rtmp, MediaMTX, DLive, Picarto…",
    )

    val CustomSrt = PlatformInfo(
        id = "srt", name = "SRT", monogram = "SRT",
        serverHint = "srt://servidor:puerto",
        keyHelp = "La «clave» son los parámetros del enlace: streamid=…&passphrase=… (o solo el streamid).",
        protocol = StreamProtocol.Srt,
    )

    val all: List<PlatformInfo> = listOf(
        Twitch, YouTube, Kick, TikTok, Facebook, Instagram, X, Trovo,
        Restream, LinkedIn, Rumble, Steam, Vimeo, CustomRtmp, CustomSrt,
    )

    fun byId(id: String): PlatformInfo = all.firstOrNull { it.id == id } ?: CustomRtmp

    fun detect(host: String, protocol: StreamProtocol): PlatformInfo =
        if (protocol == StreamProtocol.Srt) CustomSrt
        else all.firstOrNull { it.matchesHost(host) } ?: CustomRtmp
}
