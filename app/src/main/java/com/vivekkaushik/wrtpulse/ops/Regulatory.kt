package com.vivekkaushik.wrtpulse.ops

import java.util.Locale

/** One entry in the country picker. */
data class RegDomain(val code: String, val name: String) {
    val label: String get() = "$name · $code"
}

/**
 * The Wi-Fi regulatory domain — which channels are legal here and how much power they may
 * use. OpenWrt keeps it per radio (`wireless.radioN.country`), but it describes where the
 * router physically is, so the app sets it on every radio at once.
 *
 * The list comes from the JVM's own ISO 3166 table rather than a literal in this file: it is
 * the same data, it carries localised names, and it cannot drift.
 *
 * This object deliberately does NOT ship a channel-by-channel regulatory database. The
 * router's driver is the authority on what is legal, and a half-complete table here would
 * be worse than none — it would look authoritative while being wrong. What it does carry is
 * the handful of restrictions that are unambiguous and that actually strand routers.
 */
object Regulatory {

    /** mac80211's world domain: the intersection of everyone's rules, so the safest. */
    const val WORLD = "00"

    fun countries(): List<RegDomain> {
        val world = RegDomain(WORLD, "World (most restrictive)")
        val rest = Locale.getISOCountries()
            .map { code -> RegDomain(code, nameOf(code)) }
            .sortedBy { it.name.lowercase() }
        return listOf(world) + rest
    }

    fun nameOf(code: String): String {
        if (code == WORLD) return "World (most restrictive)"
        val name = runCatching {
            Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
        }.getOrNull().orEmpty()
        return name.ifBlank { code }
    }

    /** uci takes a two-character code; anything else will not survive `wifi reload`. */
    fun isValidCode(code: String): Boolean =
        code == WORLD || (code.length == 2 && code.all { it in 'A'..'Z' })

    fun search(term: String): List<RegDomain> {
        val query = term.trim()
        if (query.isBlank()) return countries()
        return countries().filter {
            it.name.contains(query, ignoreCase = true) || it.code.equals(query, ignoreCase = true)
        }
    }

    /** The domain the radios agree on, or null when they disagree or none is set. */
    fun current(radios: List<WifiRadio>): String? {
        val set = radios.map { it.country }.filter { it.isNotBlank() }.toSet()
        return set.singleOrNull()
    }

    /** True when radios carry different domains — legal nonsense worth surfacing. */
    fun disagree(radios: List<WifiRadio>): Boolean =
        radios.map { it.country }.filter { it.isNotBlank() }.toSet().size > 1

    /**
     * Some radios carry a domain and others do not. Not an error, but not harmless either:
     * an unset radio falls back to the world domain and so runs more restricted than the
     * one beside it, on the same router, in the same country. Seen on the reference device,
     * where radio0 was IN and radio1 was unset.
     */
    fun partiallySet(radios: List<WifiRadio>): Boolean {
        val set = radios.count { it.country.isNotBlank() }
        return set > 0 && set < radios.size
    }

    private fun pinned(radio: WifiRadio): Int? =
        radio.channel.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }?.toIntOrNull()

    /**
     * What the user should read before applying. Ordered most concrete first.
     *
     * The two channel rules encoded here are the ones that are not in dispute anywhere:
     * 2.4 GHz channels 12 and 13 are unusable in the US and Canada, and channel 14 exists
     * only in Japan. Everything else is left to the router, and said so.
     */
    fun warnings(country: String, radios: List<WifiRadio>): List<String> = buildList {
        val where = nameOf(country)
        radios.forEach { radio ->
            val channel = pinned(radio) ?: return@forEach
            when {
                channel in 12..13 && country in NO_CHANNEL_12_13 ->
                    add("${radio.section} is pinned to channel $channel, which $where does not " +
                        "allow. Set it to auto, or the radio will not come back up.")
                channel == 14 && country != "JP" ->
                    add("${radio.section} is pinned to channel 14, which only Japan allows.")
                else ->
                    add("${radio.section} is pinned to channel $channel. If $where does not " +
                        "allow it the radio stays down — auto is the safe choice when changing " +
                        "domain.")
            }
        }
        if (country == WORLD) {
            add("The world domain is the intersection of every country's rules, so it is the " +
                "most restrictive setting and may drop channels you use today.")
        }
        add("The domain also caps transmit power, so range can change either way.")
        add("This app does not carry a channel-by-channel legal database — the router's " +
            "driver decides. If a radio does not come back, put its channel on auto.")
    }

    /** US and Canada; the rest of the world permits 12 and 13. */
    private val NO_CHANNEL_12_13 = setOf("US", "CA")
}
