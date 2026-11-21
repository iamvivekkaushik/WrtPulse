package com.vivekkaushik.wrtpulse.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the one-time Play rating ask has counted so far. Pure, so the rules can be tested off
 * the device; [RatePrompt] keeps it in the app's prefs.
 *
 * The card has to be earned (an install a week old, several opens, a few days on a router, a
 * couple of changes the router kept), then armed by one more kept change, and it shows on a
 * LATER day — never on the visit where the user is still watching their network come back.
 * Days are local epoch days ([LocalDate.toEpochDay]): "later" means the calendar turned over.
 */
data class RateLedger(
    /** Rated, reported, declined, or left alone for [MAX_SHOWN_DAYS]: never shown again. */
    val done: Boolean = false,
    /** Times the app came to the foreground after at least [OPEN_GAP_MS] away. */
    val opens: Int = 0,
    /** When the app last went to the background, for telling a new open from a glance away. */
    val awayMs: Long = NEVER,
    val connectDays: Int = 0,
    val lastConnectDay: Long = NEVER,
    /** Applies the router kept: the rollback disarmed, or there was none to arm. */
    val keptApplies: Int = 0,
    /** The day a kept apply armed the card, once the rest was earned; [NEVER] when not armed. */
    val armedDay: Long = NEVER,
    val shownDays: Int = 0,
    val lastShownDay: Long = NEVER,
) {
    /** The app came to the foreground. Back from less than [OPEN_GAP_MS] is the same visit. */
    fun foreground(nowMs: Long): RateLedger =
        if (done || (awayMs != NEVER && nowMs - awayMs < OPEN_GAP_MS)) this else copy(opens = opens + 1)

    fun background(nowMs: Long): RateLedger = if (done) this else copy(awayMs = nowMs)

    fun connected(today: Long): RateLedger =
        if (done || lastConnectDay == today) this else copy(connectDays = connectDays + 1, lastConnectDay = today)

    /**
     * A kept apply counts, and arms the card once everything else is earned. One that failed
     * or that the router rolled back disarms it until the next clean one.
     */
    fun applied(kept: Boolean, today: Long, installedDay: Long): RateLedger {
        if (done) return this
        if (!kept) return copy(armedDay = NEVER)
        val counted = copy(keptApplies = keptApplies + 1)
        return if (counted.armedDay == NEVER && counted.earned(today, installedDay)) counted.copy(armedDay = today) else counted
    }

    fun earned(today: Long, installedDay: Long): Boolean =
        today - installedDay >= MIN_INSTALL_DAYS && opens >= MIN_OPENS &&
            connectDays >= MIN_CONNECT_DAYS && keptApplies >= MIN_KEPT_APPLIES

    /** Whether the card belongs on today's Dashboard: armed on an earlier day and not shown out. */
    fun due(today: Long, installedDay: Long): Boolean =
        !done && armedDay != NEVER && today > armedDay && earned(today, installedDay) &&
            (shownDays < MAX_SHOWN_DAYS || lastShownDay == today)

    /** The card was on screen today. Ignored for [MAX_SHOWN_DAYS] days, it stops coming back. */
    fun shown(today: Long): RateLedger =
        if (lastShownDay == today) this else copy(shownDays = shownDays + 1, lastShownDay = today)

    /** Any answer at all — rate, report, or no thanks. There is no "later". */
    fun retired(): RateLedger = copy(done = true)

    companion object {
        const val NEVER = Long.MIN_VALUE
        const val MIN_INSTALL_DAYS = 7
        const val MIN_OPENS = 5
        const val MIN_CONNECT_DAYS = 3
        const val MIN_KEPT_APPLIES = 2
        const val MAX_SHOWN_DAYS = 3
        const val OPEN_GAP_MS = 30 * 60 * 1000L
    }
}

/**
 * The one-time ask for a Google Play rating, and the always-there rows in System and About.
 *
 * Everything is counted on this phone, in the app's own prefs; nothing is sent anywhere. The
 * ask only ever hands the user to the Play listing — the app never sees whether they rated.
 */
class RatePrompt private constructor(
    private val prefs: SharedPreferences,
    /**
     * Installed by the Play Store. A build from GitHub has no listing of its own to send
     * anyone to, so it neither asks nor shows the rows.
     */
    val fromPlay: Boolean,
    /** The day the app was first installed — kept across updates, reset by a reinstall. */
    val installedDate: LocalDate,
) {
    var ledger by mutableStateOf(load()); private set

    private val installedDay = installedDate.toEpochDay()
    private fun today() = LocalDate.now().toEpochDay()

    fun foreground() = update(ledger.foreground(System.currentTimeMillis()))
    fun background() = update(ledger.background(System.currentTimeMillis()))
    fun connected() = update(ledger.connected(today()))
    fun applied(kept: Boolean) = update(ledger.applied(kept, today(), installedDay))
    fun shown() = update(ledger.shown(today()))
    fun retire() = update(ledger.retired())

    /** Whether the Dashboard card is due today. Reads [ledger], so a composable recomposes on it. */
    fun due(): Boolean = fromPlay && ledger.due(today(), installedDay)

    private fun update(next: RateLedger) {
        if (next == ledger) return
        ledger = next
        prefs.edit()
            .putBoolean(DONE, next.done)
            .putInt(OPENS, next.opens)
            .putLong(AWAY, next.awayMs)
            .putInt(CONNECT_DAYS, next.connectDays)
            .putLong(LAST_CONNECT_DAY, next.lastConnectDay)
            .putInt(KEPT_APPLIES, next.keptApplies)
            .putLong(ARMED_DAY, next.armedDay)
            .putInt(SHOWN_DAYS, next.shownDays)
            .putLong(LAST_SHOWN_DAY, next.lastShownDay)
            .apply()
    }

    private fun load() = RateLedger(
        done = prefs.getBoolean(DONE, false),
        opens = prefs.getInt(OPENS, 0),
        awayMs = prefs.getLong(AWAY, RateLedger.NEVER),
        connectDays = prefs.getInt(CONNECT_DAYS, 0),
        lastConnectDay = prefs.getLong(LAST_CONNECT_DAY, RateLedger.NEVER),
        keptApplies = prefs.getInt(KEPT_APPLIES, 0),
        armedDay = prefs.getLong(ARMED_DAY, RateLedger.NEVER),
        shownDays = prefs.getInt(SHOWN_DAYS, 0),
        lastShownDay = prefs.getLong(LAST_SHOWN_DAY, RateLedger.NEVER),
    )

    companion object {
        const val PLAY_STORE = "com.android.vending"

        private const val DONE = "rate_done"
        private const val OPENS = "rate_opens"
        private const val AWAY = "rate_away_ms"
        private const val CONNECT_DAYS = "rate_connect_days"
        private const val LAST_CONNECT_DAY = "rate_last_connect_day"
        private const val KEPT_APPLIES = "rate_kept_applies"
        private const val ARMED_DAY = "rate_armed_day"
        private const val SHOWN_DAYS = "rate_shown_days"
        private const val LAST_SHOWN_DAY = "rate_last_shown_day"

        fun of(context: Context, prefs: SharedPreferences): RatePrompt {
            val pm = context.packageManager
            val name = context.packageName
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(name).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(name)
            }.getOrNull()
            val installedMs = runCatching { pm.getPackageInfo(name, 0).firstInstallTime }
                .getOrDefault(System.currentTimeMillis())
            val installed = Instant.ofEpochMilli(installedMs).atZone(ZoneId.systemDefault()).toLocalDate()
            return RatePrompt(prefs, installer == PLAY_STORE, installed)
        }

        /**
         * The app's page in the Play Store app; the web listing when the Play Store is not
         * there to take it. It opens as its own task, so Back from Play returns here.
         */
        fun openListing(context: Context) {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                .setPackage(PLAY_STORE)
                .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            try {
                context.startActivity(market)
            } catch (_: ActivityNotFoundException) {
                val web = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web)) }
            }
        }
    }
}
