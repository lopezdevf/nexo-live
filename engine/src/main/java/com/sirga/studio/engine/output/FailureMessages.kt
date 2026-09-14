// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.output

/** Traduce los motivos técnicos de RootEncoder a mensajes que el usuario pueda resolver. */
object FailureMessages {
    const val AUTH = "La plataforma rechazó las credenciales. Revisa la clave."

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)endpoint malformed") to "El enlace no es válido. Revisa servidor y clave.",
        Regex("(?i)badname|publish\\.denied|unauthorized|forbidden|auth") to "La plataforma rechazó la clave. Cópiala de nuevo desde su panel.",
        Regex("(?i)unknownhost|unable to resolve|no address") to "No se encuentra el servidor. Revisa la dirección o tu conexión.",
        Regex("(?i)refused") to "El servidor rechazó la conexión. Comprueba el puerto y el protocolo.",
        Regex("(?i)timeout|timed out") to "El servidor no respondió a tiempo. Revisa tu conexión.",
        Regex("(?i)handshake") to "El servidor no completó la conexión. ¿Es RTMP en lugar de RTMPS (o al revés)?",
        Regex("(?i)ssl|tls|certificate") to "Falló la conexión cifrada (RTMPS). Prueba el servidor RTMP o revisa la fecha del móvil.",
        Regex("(?i)network is unreachable|no route|broken pipe|connection reset") to "Se perdió la conexión a internet.",
    )

    private val LINK = Regex("""(?i)(rtmp[st]*|srt)://\S+""")

    fun describe(reason: String): String =
        rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(reason) }?.second
            // Por si el motivo trae la URL de publicación: nunca mostrar la clave
            ?: "No se pudo conectar (${reason.replace(LINK, "…").take(120)})."
}
