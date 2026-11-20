package com.mindtravel.reduction.desktop

import java.net.URI

/**
 * Which requests this computer's own server answers: those addressed to it by a local name.
 *
 * Listening on 127.0.0.1 keeps other machines out, but not other web sites. A page on any site
 * can have its name answer with 127.0.0.1 a moment after it has loaded (DNS rebinding); from then
 * on the browser takes this server for that site, lets the page read every answer, and lets it
 * send any header it likes, X-Reduction included. What such a request cannot change is the name
 * it was sent to: the Host header still says that site, not localhost.
 *
 * So every request must name this server by a local name and its port, and a request from a page
 * (one with an Origin) must come from such a page. The panel, the pet and the phone's curl over SSH
 * all use localhost or 127.0.0.1.
 */
object LocalOrigin {
    private val NAMES = setOf("localhost", "127.0.0.1", "[::1]")

    fun allows(port: Int, host: String?, origin: String?): Boolean {
        if (!isLocal(port, host)) return false
        if (origin == null) return true
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        if (uri.scheme != "http" || uri.rawPath.orEmpty().isNotEmpty() || uri.rawAuthority == null) return false
        return isLocal(port, uri.rawAuthority)
    }

    /** "localhost:8780", "127.0.0.1:8780", "[::1]:8780"; the port may go unsaid only when it is 80. */
    private fun isLocal(port: Int, authority: String?): Boolean {
        val value = authority?.trim()?.lowercase() ?: return false
        if (value.isEmpty() || '@' in value) return false
        val name: String
        val given: Int
        val colon = value.lastIndexOf(':')
        if (colon > value.lastIndexOf(']')) {
            name = value.substring(0, colon)
            given = value.substring(colon + 1).toIntOrNull() ?: return false
        } else {
            name = value
            given = 80
        }
        return name in NAMES && given == port
    }
}
