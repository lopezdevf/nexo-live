// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

package com.nexo.live.engine.output

/** Resultado de leer lo que el usuario pega: un enlace completo, solo el servidor o «servidor clave». */
sealed interface LinkResult {
    data class Ok(val server: String, val secret: String?, val platform: PlatformInfo) : LinkResult
    data class Invalid(val reason: String) : LinkResult
}

/**
 * Enlaces de conexión RTMP/RTMPS/SRT. La clave es un secreto: nada de aquí la registra.
 *
 * RootEncoder toma como «app» los dos primeros segmentos de la ruta y el resto como
 * nombre de stream, así que la clave siempre va al final y el servidor no lleva «?».
 */
object ConnectionLink {

    private val LINK = Regex("""^(rtmps?|rtmpts?|srt)://([^/?#\s]+)(/[^?#\s]*)?(\?[^#\s]*)?$""", RegexOption.IGNORE_CASE)
    private const val IVS_SUFFIX = "live-video.net"

    fun protocolOf(server: String): StreamProtocol? = when (LINK.find(server.trim())?.groupValues?.get(1)?.lowercase()) {
        null -> null
        "srt" -> StreamProtocol.Srt
        else -> StreamProtocol.Rtmp
    }

    fun hostOf(server: String): String? =
        LINK.find(server.trim())?.groupValues?.get(2)?.substringAfterLast('@')?.substringBeforeLast(':')?.lowercase()

    fun parse(raw: String): LinkResult {
        val text = raw.trim().removeSurrounding("\"").trim()
        if (text.isEmpty()) return LinkResult.Invalid("El enlace está vacío.")

        // «servidor clave» copiados juntos (en una línea o en dos)
        val tokens = text.split(Regex("""\s+"""))
        if (tokens.size == 2 && protocolOf(tokens[0]) != null && protocolOf(tokens[1]) == null) {
            return when (val server = parse(tokens[0])) {
                is LinkResult.Ok -> server.copy(secret = tokens[1])
                is LinkResult.Invalid -> server
            }
        }
        if (tokens.size > 1) return LinkResult.Invalid("Pega un solo enlace, o el servidor y la clave separados por un espacio.")

        val match = LINK.find(text)
            ?: return LinkResult.Invalid(
                if ("://" !in text) "Falta el protocolo: el enlace debe empezar por rtmp://, rtmps:// o srt://."
                else "El enlace no es válido. Revisa que lo hayas copiado entero."
            )
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val segments = match.groupValues[3].split('/').filter { it.isNotEmpty() }
        val query = match.groupValues[4].removePrefix("?").ifEmpty { null }
        val host = authority.substringAfterLast('@').substringBeforeLast(':').lowercase()

        if (scheme == "srt") return parseSrt(authority, segments, query)

        val platform = PlatformCatalog.detect(host, StreamProtocol.Rtmp)
        val base = "$scheme://$authority"

        // Amazon IVS (Twitch, Kick): la app es siempre «app»; lo que venga detrás es la clave
        if (host.endsWith(IVS_SUFFIX)) {
            val keyParts = if (segments.firstOrNull() == "app") segments.drop(1) else segments
            val secret = keyParts.joinToString("/").ifEmpty { null }?.withQuery(query)
            return LinkResult.Ok("$base/app", secret, platform)
        }

        // Servidor conocido del catálogo: todo lo que sobra es la clave
        platform.servers.firstOrNull { sameEndpoint(it.url, scheme, authority, segments) }?.let { known ->
            val knownSegments = pathSegments(known.url)
            val secret = segments.drop(knownSegments.size).joinToString("/").ifEmpty { null }?.withQuery(query)
            return LinkResult.Ok(known.url.trimEnd('/'), secret, platform)
        }

        return when {
            segments.isEmpty() -> LinkResult.Invalid("Al servidor le falta la ruta de la aplicación (por ejemplo /live o /app).")
            segments.size == 1 -> {
                if (query != null) LinkResult.Invalid("El servidor no puede llevar parámetros «?». Pégalos junto a la clave.")
                else LinkResult.Ok("$base/${segments[0]}", null, platform)
            }
            else -> LinkResult.Ok(
                server = "$base/${segments.dropLast(1).joinToString("/")}",
                secret = segments.last().withQuery(query),
                platform = platform,
            )
        }
    }

    private fun parseSrt(authority: String, segments: List<String>, query: String?): LinkResult {
        if (!authority.contains(':')) return LinkResult.Invalid("Los enlaces SRT necesitan puerto: srt://servidor:puerto.")
        val params = buildList {
            query?.let { add(it) }
            if (segments.isNotEmpty() && query?.contains("streamid=") != true) add("streamid=${segments.joinToString("/")}")
        }.joinToString("&").ifEmpty { null }
        return LinkResult.Ok("srt://$authority", params, PlatformCatalog.CustomSrt)
    }

    /** Une servidor y clave en la URL de publicación que recibe RootEncoder. */
    fun join(server: String, secret: String?): String {
        val cleanServer = server.trim()
        val cleanSecret = secret?.trim().orEmpty()
        if (cleanSecret.isEmpty()) return cleanServer
        return if (protocolOf(cleanServer) == StreamProtocol.Srt) {
            val params = cleanSecret.removePrefix("?").let { if ('=' in it) it else "streamid=$it" }
            cleanServer + (if ('?' in cleanServer) "&" else "?") + params
        } else {
            cleanServer.trimEnd('/') + "/" + cleanSecret.removePrefix("/")
        }
    }

    /** Devuelve un error legible o null si el servidor es utilizable. */
    fun validateServer(server: String, platform: PlatformInfo): String? {
        val text = server.trim()
        if (text.isEmpty()) return "Indica el servidor."
        val match = LINK.find(text) ?: return "Debe empezar por rtmp://, rtmps:// o srt:// y no llevar espacios."
        val protocol = protocolOf(text)
        if (protocol != platform.protocol) {
            return if (platform.protocol == StreamProtocol.Srt) "Este destino es SRT: el enlace debe empezar por srt://."
            else "Este destino usa RTMP: el enlace debe empezar por rtmp:// o rtmps://."
        }
        if (protocol == StreamProtocol.Rtmp) {
            if (match.groupValues[4].isNotEmpty()) return "El servidor no puede llevar parámetros «?». Pégalos junto a la clave."
            if (match.groupValues[3].trim('/').isEmpty()) return "Al servidor le falta la ruta de la aplicación (por ejemplo /live o /app)."
        } else if (!match.groupValues[2].contains(':')) {
            return "Los enlaces SRT necesitan puerto: srt://servidor:puerto."
        }
        return null
    }

    /** Aviso anti-suplantación: el servidor no es de la plataforma elegida. */
    fun hostMismatch(server: String, platform: PlatformInfo): Boolean {
        if (platform.isCustom) return false
        val host = hostOf(server) ?: return false
        return !platform.matchesHost(host)
    }

    private fun sameEndpoint(url: String, scheme: String, authority: String, segments: List<String>): Boolean {
        val known = LINK.find(url) ?: return false
        if (!known.groupValues[1].equals(scheme, ignoreCase = true)) return false
        if (!normalizeAuthority(known.groupValues[2], scheme).equals(normalizeAuthority(authority, scheme), ignoreCase = true)) return false
        val knownSegments = pathSegments(url)
        return segments.size >= knownSegments.size && segments.take(knownSegments.size) == knownSegments
    }

    private fun normalizeAuthority(authority: String, scheme: String): String {
        val defaultPort = when (scheme.lowercase()) {
            "rtmps", "rtmpts" -> ":443"
            "rtmpt" -> ":80"
            else -> ":1935"
        }
        return authority.removeSuffix(defaultPort)
    }

    private fun pathSegments(url: String): List<String> =
        LINK.find(url)?.groupValues?.get(3)?.split('/')?.filter { it.isNotEmpty() }.orEmpty()

    private fun String.withQuery(query: String?) = if (query == null) this else "$this?$query"
}
