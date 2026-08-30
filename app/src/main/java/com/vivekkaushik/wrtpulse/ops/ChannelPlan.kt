package com.vivekkaushik.wrtpulse.ops

/** What a scan says about one candidate channel. */
data class ChannelAdvice(
    val channel: Int,
    /** Neighbours sitting exactly on it. */
    val onChannel: Int,
    /** Neighbours near enough to interfere without sharing the channel. */
    val overlapping: Int,
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
 * 2.4 GHz is the interesting case: its channels are 5 MHz apart but 20 MHz wide, so anything
 * within four channels bleeds into you. That is why only 1, 6 and 11 are worth using — they
 * are the only trio that doesn't overlap — and why a neighbour on channel 3 hurts a network
 * on channel 1 more than one sitting on channel 1 politely sharing airtime.
 */
object ChannelPlan {

    /** The channels worth offering per band; 2.4 GHz deliberately excludes the overlapping ones. */
    fun candidates(band: String): List<Int> = when (band) {
        "5G" -> listOf(36, 40, 44, 48, 149, 153, 157, 161)
        "6G" -> listOf(1, 33, 65, 97, 129, 161, 193)
        else -> listOf(1, 6, 11)
    }

    fun advise(band: String, cells: List<ScanCell>): ChannelAdvice? {
        if (cells.isEmpty()) return null
        val spread = if (band == "2.4G") 4 else 0
        return candidates(band)
            .map { channel ->
                val on = cells.count { it.channel == channel }
                val near = cells.count { it.channel != channel && kotlin.math.abs(it.channel - channel) <= spread }
                ChannelAdvice(channel, on, near)
            }
            // Overlap is worse than sharing: a co-channel neighbour takes turns, an
            // overlapping one is just noise. Ties go to the lower channel.
            .minWithOrNull(compareBy({ it.overlapping * 2 + it.onChannel }, { it.channel }))
    }

    /**
     * The widths a radio can offer, derived from the mode it already runs so the prefix
     * (HT / VHT / HE / EHT) stays whatever the driver put there.
     */
    fun widths(htmode: String, band: String): List<String> {
        val prefix = htmode.takeWhile { !it.isDigit() }.ifEmpty { if (band == "2.4G") "HT" else "VHT" }
        val steps = if (band == "2.4G") listOf(20, 40) else listOf(20, 40, 80, 160)
        return steps.map { "$prefix$it" }
    }

    /** "HE80" → "80 MHz". */
    fun widthLabel(htmode: String): String {
        val digits = htmode.dropWhile { !it.isDigit() }
        return if (digits.isEmpty()) "—" else "$digits MHz"
    }
}
