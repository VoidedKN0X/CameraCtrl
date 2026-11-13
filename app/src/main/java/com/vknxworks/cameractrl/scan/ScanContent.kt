package com.vknxworks.cameractrl.scan

import java.net.URI

/** What a scanned code holds, as far as CameraCtrl acts on it. Everything else is plain text. */
sealed interface ScanContent {
    val raw: String

    /** An http or https address. It's opened only when you tap Open, with the full address shown. */
    data class Link(override val raw: String, val url: String) : ScanContent

    /** A Wi-Fi network code (WIFI:T:WPA;S:name;P:password;;). */
    data class Wifi(override val raw: String, val ssid: String, val password: String?, val security: Security, val hidden: Boolean) : ScanContent

    data class Text(override val raw: String) : ScanContent

    enum class Security { OPEN, WEP, WPA, SAE, UNKNOWN }

    companion object {
        /** Codes longer than this are shown as text only. */
        const val MAX_LENGTH = 8192

        fun parse(text: String): ScanContent {
            if (text.length > MAX_LENGTH) return Text(text)
            val t = text.trim()
            if (t.startsWith("WIFI:", ignoreCase = true)) parseWifi(text, t)?.let { return it }
            link(t)?.let { return Link(text, it) }
            return Text(text)
        }

        /** The address, when [t] is a single http(s) URL with a host; null for anything else (no other schemes). */
        fun link(t: String): String? {
            if (t.isEmpty() || t.any { it.isWhitespace() || it.code < 0x20 }) return null
            val scheme = t.substringBefore(':', "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (!t.substring(scheme.length).startsWith("://")) return null
            val uri = runCatching { URI(t) }.getOrNull() ?: return null
            if (uri.host.isNullOrEmpty()) return null
            return t
        }

        /**
         * The Wi-Fi QR format: fields T (WPA, SAE, WEP, nopass), S (name), P (password), H (hidden), separated by
         * unescaped `;`, with `\` escaping `\ ; , : "`.
         */
        fun parseWifi(raw: String, t: String): Wifi? {
            val body = t.substring(5)
            val fields = HashMap<String, String>()
            val cur = StringBuilder()
            var key: String? = null
            var i = 0
            while (i < body.length) {
                val ch = body[i]
                when {
                    ch == '\\' && i + 1 < body.length -> {
                        cur.append(body[i + 1])
                        i++
                    }
                    ch == ':' && key == null -> {
                        key = cur.toString().uppercase()
                        cur.clear()
                    }
                    ch == ';' -> {
                        if (key != null && key !in fields) fields[key] = cur.toString()
                        key = null
                        cur.clear()
                    }
                    else -> cur.append(ch)
                }
                i++
            }
            if (key != null && key !in fields) fields[key] = cur.toString()
            val ssid = fields["S"]?.let(::unquote)?.takeIf { it.isNotEmpty() } ?: return null
            val type = fields["T"].orEmpty().uppercase()
            val password = fields["P"]?.let(::unquote)?.takeIf { it.isNotEmpty() }
            val security = when {
                type == "NOPASS" || (type.isEmpty() && password == null) -> Security.OPEN
                type == "WEP" -> Security.WEP
                type == "WPA" || type == "WPA2" || (type.isEmpty() && password != null) -> Security.WPA
                type == "SAE" || type == "WPA3" -> Security.SAE
                else -> Security.UNKNOWN
            }
            val hidden = fields["H"].equals("true", ignoreCase = true)
            return Wifi(raw, ssid, if (security == Security.OPEN) null else password, security, hidden)
        }

        private fun unquote(s: String) = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
    }
}
