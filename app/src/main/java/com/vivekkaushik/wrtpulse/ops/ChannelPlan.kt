package com.vivekkaushik.wrtpulse.ops

/** What a scan says about one candidate channel. */
data class ChannelAdvice(
    val channel: Int,
    /** Neighbours sitting exactly on it. */
    val onChannel: Int,
    /** Neighbours near enough to interfere without sharing the channel. */
    val overlapping: Int,
    /** What the pick was made on: every interfering neighbour, weighted by how loud it is. */
    val score: Double = 0.0,
) {
    /** Nothing heard on it and nothing bleeding into it. */
    val clear: Boolean get() = onChannel == 0 && overlapping == 0

    /** "2 neighbors, no overlap" — the phrase under the channel chart. */
    val summary: String = buildString {
        append("$onChannel neighbor").append(if (onChannel == 1) "" else "s")
        append(if (overlapping == 0) ", no overlap" else ", $overlapping overlapping")
    }

    /**
     * On a crowded band the winner is still busy, and calling it "clearest" reads as a
     * contradiction next to the count. Say which claim is actually being made.
     */
    val headline: String get() =
        if (clear) "ch $channel is clear" else "ch $channel least busy — $summary"
}

/**
 * Picks the least crowded channel from a survey.
 *
 * Everything is judged in MHz, because channel numbers lie about width. On 2.4 GHz the
 * channels are 5 MHz apart but 20 MHz wide, so anything within four channels bleeds into you —
 * which is why only 1, 6 and 11 are worth using, and why a neighbour on channel 3 hurts a
 * network on channel 1 more than one sitting on channel 1 politely sharing airtime. On 5 GHz
 * the numbers are 20 MHz apart but almost nobody runs 20 MHz: a mesh on primary 40 at 80 MHz
 * owns 36–48, and calling 36 "clear" next to it is how this advisor once put a router right
 * inside its neighbour's block.
 *
 * Loud neighbours count for more. One at −14 dBm is the router in the next room sharing your
 * spectrum; one at −85 dBm barely registers, and counting them the same picked the wrong
 * channel on a real survey.
 */
object ChannelPlan {

    /** The channels worth offering per band; 2.4 GHz deliberately excludes the overlapping ones. */
    fun candidates(band: String): List<Int> = when (band) {
        "5G" -> listOf(36, 40, 44, 48, 149, 153, 157, 161)
        "6G" -> listOf(1, 33, 65, 97, 129, 161, 193)
        else -> listOf(1, 6, 11)
    }

    /**
     * The primaries a radio of this width can actually be put on. At 80 MHz the non-DFS 5 GHz
     * band has two blocks, so there are two real choices, not eight.
     */
    fun candidates(band: String, widthMhz: Int): List<Int> = when {
        band != "5G" || widthMhz <= 20 -> candidates(band)
        widthMhz == 40 -> listOf(36, 44, 149, 157)
        else -> listOf(36, 149)
    }

    /**
     * The TX power values worth offering out of everything the driver accepts: the top few
     * one dB apart, then coarser steps down to a tenth of the power. Every value is one the
     * driver listed, so nothing offered is invented; the list is just short enough to read.
     */
    fun txpowerOptions(accepted: List<Int>): List<Int> {
        val max = accepted.maxOrNull() ?: return emptyList()
        val wanted = listOf(0, 1, 2, 3, 4, 6, 8, 10, 13, 16, 20).map { max - it }
        return wanted.filter { it > 0 && it in accepted }
    }

    /** "VHT80" → 80; an unset mode is 20. */
    fun widthOf(htmode: String): Int = htmode.dropWhile { !it.isDigit() }.toIntOrNull() ?: 20

    /**
     * Where a band's channel numbers sit: centre MHz = base + 5 × channel. (2.4 GHz channel 14
     * is the exception and nobody is offered it.)
     */
    private fun baseMhz(band: String) = when (band) {
        "5G" -> 5000
        "6G" -> 5950
        else -> 2407
    }

    /**
     * The spectrum an operator on [primary] at [widthMhz] occupies, as (low, high) MHz. A wide
     * block's centre is above its lowest primary: +2 channels at 40, +6 at 80, +14 at 160.
     * A 2.4 GHz 20 MHz signal is taken as the 22 MHz its mask really covers, so a neighbour
     * four channels away overlaps and one five away does not — the 1/6/11 rule.
     */
    private fun span(band: String, centerChannel: Int, widthMhz: Int): Pair<Int, Int> {
        val center = baseMhz(band) + 5 * centerChannel
        val half = (if (band == "2.4G" && widthMhz == 20) 22 else widthMhz) / 2
        return (center - half) to (center + half)
    }

    private fun candidateCenter(primary: Int, widthMhz: Int) = primary + (widthMhz / 10 - 2).coerceAtLeast(0)

    /**
     * How much a neighbour at this signal matters, on a scale where −85 dBm is 1 and every
     * 10 dB louder adds one: the router next door at −14 dBm weighs eight of the faint ones.
     */
    private fun weight(signalDbm: Int) = ((signalDbm + 95) / 10.0).coerceAtLeast(1.0)

    fun advise(band: String, cells: List<ScanCell>, widthMhz: Int = 20): ChannelAdvice? {
        if (cells.isEmpty()) return null
        return candidates(band, widthMhz)
            .map { channel ->
                val (lo, hi) = span(band, candidateCenter(channel, widthMhz), widthMhz)
                var on = 0
                var near = 0
                var score = 0.0
                cells.forEach { cell ->
                    val (clo, chi) = span(band, cell.centerChannel, cell.widthMhz)
                    if (clo >= hi || chi <= lo) return@forEach
                    // Sharing a primary means taking turns; merely overlapping means noise.
                    if (cell.channel == channel) {
                        on++
                        score += weight(cell.signalDbm)
                    } else {
                        near++
                        score += 2 * weight(cell.signalDbm)
                    }
                }
                ChannelAdvice(channel, on, near, score)
            }
            // Ties go to the lower channel.
            .minWithOrNull(compareBy({ it.score }, { it.channel }))
    }

    /**
     * The widths a radio can offer, derived from the mode it already runs so the prefix
     * (HT / VHT / HE / EHT) stays whatever the driver put there, and cut down to what the
     * chip reports it can do when that is known — a width hostapd refuses takes the whole
     * AP down with it.
     */
    fun widths(htmode: String, band: String, supported: Set<Int>? = null): List<String> {
        val prefix = htmode.takeWhile { !it.isDigit() }.ifEmpty { if (band == "2.4G") "HT" else "VHT" }
        val steps = if (band == "2.4G") listOf(20, 40) else listOf(20, 40, 80, 160)
        val offered = if (supported.isNullOrEmpty()) steps else steps.filter { it in supported }
        return offered.map { "$prefix$it" }
    }

    /** "HE80" → "80 MHz". */
    fun widthLabel(htmode: String): String {
        val digits = htmode.dropWhile { !it.isDigit() }
        return if (digits.isEmpty()) "—" else "$digits MHz"
    }
}
