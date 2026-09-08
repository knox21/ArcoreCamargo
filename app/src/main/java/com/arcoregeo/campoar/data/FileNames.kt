package com.arcoregeo.campoar.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Normalize display names from SAF / share intents (avoids `msf:39`, `document:12`). */
object FileNames {
    fun sanitize(raw: String?, fallback: String = "archivo"): String {
        if (raw.isNullOrBlank()) return fallback
        var name = decode(raw.trim())
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (name.isEmpty()) return fallback
        // content-provider style ids
        if (name.matches(Regex("""(?i)^(msf|document|raw):\S+$"""))) return fallback
        if (name.matches(Regex("""^\d+$"""))) return fallback
        // e.g. primary:Download%2Fobra.ifc → obra.ifc
        if (':' in name) {
            val after = name.substringAfterLast(':')
            if (after.contains('.')) name = after.substringAfterLast('/').substringAfterLast('\\')
        }
        return name.ifBlank { fallback }
    }

    fun looksLikeContentId(name: String): Boolean {
        val n = name.trim()
        return n.matches(Regex("""(?i)^(msf|document|raw):\S+$""")) ||
            n.matches(Regex("""^\d+$""")) ||
            (!n.contains('.') && ':' in n)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
