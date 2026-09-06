package com.tvremocon.net

/**
 * The only addresses this app is allowed to talk to.
 *
 * network_security_config.xml has to permit cleartext app-wide — it matches hostnames, and
 * the hub's address is whatever DHCP handed out. The narrowing lives here instead: a private
 * IPv4 literal, port 80, and one of the three KLAP paths. Nothing else can be built.
 */
class HubEndpoint private constructor(val host: String) {

    fun url(path: Path): String = "http://$host:80/app/${path.segment}"

    enum class Path(val segment: String) {
        HANDSHAKE1("handshake1"),
        HANDSHAKE2("handshake2"),
        REQUEST("request"),
    }

    /** [Path.REQUEST] carries the KLAP sequence number as a query parameter. */
    fun requestUrl(seq: Int): String = "${url(Path.REQUEST)}?seq=$seq"

    override fun toString(): String = host

    companion object {
        /** Returns null when [host] is not a private IPv4 literal. */
        fun of(host: String): HubEndpoint? =
            if (isPrivateIpv4(host)) HubEndpoint(host) else null

        /**
         * RFC 1918 plus link-local. Literal digits only — a hostname would need DNS, which
         * would put the lookup outside the pinned Wi-Fi network and outside this check.
         */
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val o = parts.map { it.toIntOrNull() ?: return false }
            if (o.any { it !in 0..255 }) return false
            // Reject non-canonical forms like "010.0.0.1" so one host has one spelling.
            if (parts.any { it.length > 1 && it[0] == '0' }) return false
            return when {
                o[0] == 10 -> true
                o[0] == 192 && o[1] == 168 -> true
                o[0] == 172 && o[1] in 16..31 -> true
                o[0] == 169 && o[1] == 254 -> true
                else -> false
            }
        }
    }
}
