package com.tvremocon.net

/**
 * The only addresses this app is allowed to talk to.
 *
 * network_security_config.xml has to permit cleartext app-wide — it matches hostnames, and
 * the hub's address is whatever DHCP handed out. The narrowing lives here instead: a private
 * IPv4 literal, port 80, and one of the three KLAP paths. Nothing else can be built through
 * [of].
 */
class HubEndpoint private constructor(val host: String, val port: Int) {

    fun url(path: Path): String = "http://$host:$port/app/${path.segment}"

    /** [Path.REQUEST] carries the KLAP sequence number as a query parameter. */
    fun requestUrl(seq: Int): String = "${url(Path.REQUEST)}?seq=$seq"

    enum class Path(val segment: String) {
        HANDSHAKE1("handshake1"),
        HANDSHAKE2("handshake2"),
        REQUEST("request"),
    }

    override fun toString(): String = if (port == HUB_PORT) host else "$host:$port"

    companion object {
        const val HUB_PORT = 80

        /** Returns null when [host] is not a private IPv4 literal. */
        fun of(host: String): HubEndpoint? =
            if (isPrivateIpv4(host)) HubEndpoint(host, HUB_PORT) else null

        /**
         * Loopback on an arbitrary port, for tests that stand up a fake hub. Not reachable
         * from [of], so production code cannot end up pointed somewhere unexpected.
         */
        fun loopbackForTest(port: Int): HubEndpoint = HubEndpoint("127.0.0.1", port)

        /**
         * RFC 1918 plus link-local. Literal digits only — a hostname would need DNS, which
         * would put the lookup outside the pinned Wi-Fi network and outside this check.
         */
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            // Reject non-canonical forms like "010.0.0.1" so one host has one spelling.
            if (parts.any { it.length > 1 && it[0] == '0' }) return false
            return when {
                octets[0] == 10 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }
    }
}
