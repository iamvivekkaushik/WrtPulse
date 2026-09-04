package com.vivekkaushik.wrtpulse.ops

/**
 * IPv4 arithmetic for the LAN screen.
 *
 * Kept out of [Parsers] because it answers a different question: not "what did the router
 * say" but "is what the user typed something the router can hold". Every LAN refusal — a
 * pool that falls outside its own subnet, a reservation on the router's own address, a
 * netmask with a hole in it — is decided here, on the phone, before any command is built.
 */
object IpMath {

    /**
     * Dotted quad → 32-bit value, or null when the text is not one.
     *
     * Leading zeros are refused rather than interpreted: `inet_aton` reads `010` as octal 8,
     * so a config written from "192.168.010.1" would not mean what the person typing it
     * expected.
     */
    fun parse(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun valid(ip: String): Boolean = parse(ip) != null

    fun format(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }

    /**
     * Netmask → prefix length. A mask has to be a run of ones followed by a run of zeros;
     * 255.255.0.255 is refused because no kernel will hold it.
     */
    fun prefixOf(netmask: String): Int? {
        val value = parse(netmask) ?: return null
        val inverted = value.inv() and 0xFFFFFFFFL
        // Contiguous ones means the inverse is one less than a power of two.
        if (inverted and (inverted + 1) != 0L) return null
        return 32 - java.lang.Long.bitCount(inverted)
    }

    fun netmaskOf(prefix: Int): String {
        require(prefix in 0..32) { "prefix out of range: $prefix" }
        val value = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return format(value)
    }

    fun networkOf(ip: Long, prefix: Int): Long =
        if (prefix == 0) 0L else ip and ((0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL)

    fun broadcastOf(ip: Long, prefix: Int): Long =
        networkOf(ip, prefix) or ((1L shl (32 - prefix)) - 1)

    /** Addresses a client can actually hold: the subnet minus its network and broadcast. */
    fun usableHosts(prefix: Int): Long = when {
        prefix >= 31 -> 0
        else -> (1L shl (32 - prefix)) - 2
    }

    fun sameSubnet(a: Long, b: Long, prefix: Int): Boolean =
        networkOf(a, prefix) == networkOf(b, prefix)

    /**
     * How far into its subnet an address sits — the ".134" people read off a lease. Null when
     * the address is not in that subnet at all.
     */
    fun offsetIn(ip: Long, network: Long, prefix: Int): Long? =
        if (networkOf(ip, prefix) != network) null else ip - network

    /**
     * The DHCP pool as real addresses. dnsmasq counts `start` and `limit` from the network
     * address, so a /24 with start=100 limit=150 serves .100 through .249.
     */
    fun poolRange(network: Long, prefix: Int, start: Int, limit: Int): LongRange? {
        if (start <= 0 || limit <= 0) return null
        val first = network + start
        val last = first + limit - 1
        val broadcast = broadcastOf(network, prefix)
        if (first >= broadcast) return null
        return first..minOf(last, broadcast - 1)
    }

    /**
     * The lowest address in the subnet that nothing holds, preferring the static range below
     * the DHCP pool — a reservation inside the pool works, but it is the address dnsmasq
     * would have handed out anyway.
     *
     * The scan is bounded: on a /16 or wider the answer is always near the bottom of the
     * subnet, and walking sixteen million addresses to prove it would block the UI thread.
     */
    fun firstFree(
        network: Long,
        prefix: Int,
        taken: Set<Long>,
        pool: LongRange?,
        router: Long,
        scanLimit: Int = 4096,
    ): String? {
        val broadcast = broadcastOf(network, prefix)
        val last = minOf(broadcast - 1, network + scanLimit)
        fun free(candidate: Long) = candidate != router && candidate !in taken
        if (pool != null) {
            for (candidate in (network + 1)..minOf(last, pool.first - 1)) {
                if (free(candidate)) return format(candidate)
            }
        }
        for (candidate in (network + 1)..last) {
            if (free(candidate)) return format(candidate)
        }
        return null
    }
}
